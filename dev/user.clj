(ns user
  (:require [muse.deferred :as muse]
            [muse.protocol :as proto]
            [manifold.deferred :as d])
  (:refer-clojure :exclude (run!)))

(defn fetch-messages-by-thread [_]
  (range 2))

(defn fetch-message-from-db [id]
  (d/success-deferred
   {:id id
    :text "The best argument against..."
    :sender-id (rand-int 10)}))

(defrecord User [id]
  muse/DataSource
  (fetch [_]
    (d/success-deferred
     {:id id
      :firstName "Winston"
      :lastName "Churchill"})))

(defrecord Message [message-id]
  muse/DataSource
  (fetch [_]
    (d/chain'
     (fetch-message-from-db message-id)
     (fn [{:keys [sender-id] :as message}]
       (assoc message :sender (User. sender-id)))))
  muse/LabeledSource
  (resource-id [_] (str "message/" message-id)))

(defrecord ChatThread [thread-id]
  muse/DataSource
  (fetch [_]
    (d/chain'
     (fetch-messages-by-thread thread-id)
     (fn [ids]
       {:thread thread-id
        :messages (map #(Message. %) ids)})))
  muse/LabeledSource
  (resource-id [_] (str "thread/" thread-id)))

(defprotocol PullSource
  (pull-from [this spec]))

(defn pull [data spec]
  (cond
    (nil? spec)
    nil

    (= '* spec)
    data

    (not (vector? spec))
    (throw (IllegalArgumentException. "invalid pull spec"))

    (satisfies? proto/DataSource data)
    (muse/flat-map #(pull % spec) data)

    (satisfies? PullSource data)
    (pull-from data spec)

    :else
    data))

(defn fetch? [r]
  (or (satisfies? proto/DataSource r)
      (satisfies? proto/BatchedSource r)
      (satisfies? proto/MuseAST r)))

;; xxx: sets and lists
;; xxx: reduce code duplication
;; xxx: documentation
;; xxx: quick links in muse readme
(extend-protocol PullSource
  clojure.lang.IPersistentList
  (pull-from [this spec]
    (throw (RuntimeException. "not implemented")))
  clojure.lang.IPersistentSet
  (pull-from [this spec]
    (throw (RuntimeException. "not implemented")))
  clojure.lang.ISeq
  (pull-from [this spec]
    (let [blocks (map #(pull % spec) this)]
      (if-not (some fetch? blocks)
        (muse/value blocks)
        (muse/collect (map #(if (fetch? %) % (muse/value %)) blocks)))))
  clojure.lang.IPersistentVector
  (pull-from [this spec]
    (let [blocks (map #(pull % spec) this)]
      (if-not (some fetch? blocks)
        (muse/value (into [] blocks))
        (->> (muse/collect (map #(if (fetch? %) % (muse/value %)) blocks))
             (muse/fmap (partial into []))))))
  clojure.lang.IPersistentMap
  (pull-from [this spec]
    (let [blocks (->> spec
                      (map (fn [k]
                             (let [[key next-spec] (if (map? k)
                                                     (first k)
                                                     [k '*])
                                   next-node (get this key)]
                               (when (some? next-node)
                                 [key (pull next-node next-spec)]))))
                      (remove nil?)
                      (group-by #(fetch? (second %))))
          ready (get blocks false)
          to-fetch (get blocks true)]
      (if (empty? to-fetch)
        (muse/value (into {} ready))
        (->> (muse/collect (map second to-fetch))
             (muse/fmap #(->> %
                              (map vector (map first to-fetch))
                              (concat ready)
                              (into {}))))))))

(comment

  (muse/run!! (Message. 1))
  ;; {:id 1, :text "The best argument against...", :sender-id 2}
  
  (muse/run!! (Message. 1) {:pull [:text]})
  ;; {:text "The best argument against..."}

  (muse/run!! (Message. 1) {:pull [:id
                                   :text
                                   :sender ;; eq to '*
                                   {:sender [{:profile [:firstName]}]}
                                   {:sender '*}
                                   {:sender [:firstName :lastName]}]})
  ;; {:text "The best argument against...", :sender {:firstName "Winston", :lastName "Churchill"}}

  ;; this one is tricky:
  (muse/run!! (ChatThread. 1) {:pull [:messages ;; eq to *
                                      {:messages '*}
                                      {:messages [:text {:sender [:id]}]}]})
  
  )
