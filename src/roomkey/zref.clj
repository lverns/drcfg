(ns roomkey.zref
  "Dynamic Distributed Run-Time configuration"
  (:import [org.apache.zookeeper KeeperException KeeperException$Code])
  (:require [zookeeper :as zoo]
            [clojure.tools.logging :as log]))

;; https://github.com/liebke/zookeeper-clj
;; https://github.com/torsten/zookeeper-atom

(defn ^:dynamic *deserialize* [b] {:pre [(instance? (Class/forName "[B") b)]} (read-string (String. b "UTF-8")))

(defn ^:dynamic *serialize* [obj] {:post [(instance? (Class/forName "[B") %)]} (.getBytes (binding [*print-dup* true] (pr-str obj))))

(def ^:dynamic *max-update-attempts* 50)

(defn- validate!
  [validator v]
  (when validator
    (when-not (validator v)
      (throw (IllegalStateException. "Invalid reference state")))))

;;; A Reference type persisted in a zookeeper cluster.  The semantics are similar to a Clojure Atom
;;; with the following major differences:
;;;  * The read state (accessed via deref) may lag successful write operations (e.g. swap!)
;;;  * Read-only metadata is available which represents the zookeeper Stat data structure
;;;  * No updates are possible while disconnected
;;;  * The compare-and-set semantics are tightened to insist that updates can only apply to the
;;;    current value AND current version.
;;;  * The swap operation can fail if there is too much contention for a znode.

(defprotocol VersionedUpdate
  (compareVersionAndSet [this current-version new-value]))

(defprotocol UpdateableZNode
  (zConnect [this client] "Using the given client, enable updates and start the watcher")
  (zDisconnect [this] "Disassociate the client and disable updates")
  (zProcessUpdate [this new-zdata] "Process the zookeeper update and return this (the znode)"))

(deftype ZRef [client path cache validator watches]
  UpdateableZNode
  (zConnect [this c]
    (reset! client c)
    (let [{v :version l :dataLength :as stat} (zoo/exists c path)]
      (when (or (nil? stat) (and (zero? l) (zero? v)))
        (let [d (.deref this)]
          (if stat
            (do
              (log/infof "Updating degenerate node %s with default value" path)
              (assert (.compareVersionAndSet this v d) "Can't update degenerate node"))
            (do
              (log/debugf "Node %s does not exist, creating it and assigning default value" path)
              (assert (zoo/create-all c path :data (-> d *serialize*) :persistent? true)
                      (format "Can't create node %s" path)))))))
    (.zProcessUpdate this {:path path :event-type ::boot}))
  (zDisconnect [this] (reset! client nil))
  ;; https://zookeeper.apache.org/doc/trunk/zookeeperProgrammers.html#ch_zkWatches
  (zProcessUpdate [this {:keys [event-type keeper-state] path' :path}]
    (log/debugf "Change %s %s %s" path' event-type keeper-state)
    (assert (= path path') (format "ZNode at path %s got event (%s %s %s)" path path' event-type keeper-state))
    (case [event-type keeper-state]
      [:NodeDeleted :SyncConnected]
      (log/infof "Node %s deleted" path)
      ([::boot nil] [:NodeDataChanged :SyncConnected]) ; two cases, identical behavior
      (when-let [c @client]
        (try (let [new-z (update (zoo/data c path :watcher (fn [x] (.zProcessUpdate this x)))
                                 :data *deserialize*) ; memfn?
                   old-z (deref cache)
                   new-d (-> new-z :data)
                   old-d (-> old-z :data)
                   new-v (-> new-z :stat :version)
                   old-v (-> old-z :stat :version)]
               (validate! @validator new-d)
               (reset! cache new-z)
               (when (and (pos? old-v) (not= 1 (- new-v old-v)))
                 (log/warnf "Received non-sequential version [%d -> %d] for %s (%s %s)"
                            old-v new-v path event-type keeper-state))
               (future (doseq [[k w] @watches] (try (w k this old-d new-d)
                                              (catch Exception e (log/errorf e "Error in watcher %s" k))))))
             (catch Exception e
               (log/errorf e "Error processing inbound update from %s [%s]" path keeper-state))))
      ;; default
      (log/warnf "Unexpected event:state [%s:%s] while watching %s" event-type keeper-state path))
    this)
  clojure.lang.IDeref
  (deref [this] (-> cache deref :data))
  clojure.lang.IMeta
  ;; https://zookeeper.apache.org/doc/trunk/zookeeperProgrammers.html#sc_timeInZk
  (meta [this] (-> cache deref :stat))
  ;; Observe Interface
  clojure.lang.IRef
  (setValidator [this f]
    (validate! f (.deref this))
    (reset! validator f)
    this)
  (getValidator [this] @validator)
  (getWatches [this] @watches)
  (addWatch [this k f] (swap! watches assoc k f) this)
  (removeWatch [this k] (swap! watches dissoc k) this)
  ;; Write interface
  VersionedUpdate
  (compareVersionAndSet [this current-version newval]
    (when-not @client (throw (RuntimeException. "Not connected")))
    (validate! @validator newval)
    (boolean (try (zoo/set-data @client path (*serialize* newval) current-version)
                  (catch KeeperException e
                    (when-not (= (.code e) KeeperException$Code/BADVERSION)
                      (throw e))))))
  clojure.lang.IAtom
  (reset [this value] (.compareVersionAndSet this -1 value) value)
  (compareAndSet [this oldval newval]
    (let [current @cache
          version (-> current :stat :version)]
      (boolean (and (= oldval (:data current))
                    (.compareVersionAndSet this version newval)))))
  (swap [this f]
    (loop [n 1 i *max-update-attempts*]
      (let [current @cache
            value (-> current :data f)
            version (-> current :stat :version)]
        (if (.compareVersionAndSet this version value)
          value
          (do
            (when-not (pos? i) (throw (RuntimeException.
                                       (format "Aborting update of %s after %d failures over ~%dms"
                                               path *max-update-attempts* (* 2 n)))))
            (Thread/sleep n)
            (recur (* 2 n) (dec i)))))))
  (swap [this f x] (.swap this (fn [v] (f v x))))
  (swap [this f x y] (.swap this (fn [v] (f v x y))))
  (swap [this f x y args] (.swap this (fn [v] (apply f v x y args)))))

(defn zref
  [path default & options]
  (let [{validator :validator} (apply hash-map options)]
    (validate! validator default)
    (->ZRef (atom nil) path (atom {:data default :stat {:version -1}}) (atom validator) (atom {}))))

(defn client
  [cstr]
  (let [client (zoo/connect cstr)]
    (try
      (zoo/create client "/" :persistent? true)
      (catch org.apache.zookeeper.KeeperException$NodeExistsException e
        (log/debugf "Root node exists at %s" cstr)))
    client))

(defn connect
  [client z]
  (.zConnect z client))

(defn disconnect
  [z]
  (.zDisconnect z))

(defn connected?
  [z]
  (boolean (when-let [c @(.client z)] (.. c getState isConnected))))

(defn path [z] (.path z))
