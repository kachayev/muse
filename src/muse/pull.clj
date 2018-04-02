(ns muse.pull
  (:require [muse.deferred :as muse]
            [muse.protocol :as proto]))

(defprotocol PullSource
  (pull-from [this spec]))

(defn pull
  "Takes muse AST and returns a new AST that would perform *only* fetches
   specified by given `spec` (applied recursively).

   `spec` might be one of the following:

   - `'*` stands for 'everything as is'
   - vector of per-key specifications in the form of `{:key :value-spec}`

   A single keyword in the vector is equal to `{:key '*}`.
   See `muse.pull-spec` tests for samples of the code."
  ([data]
   (pull data '*))
  ([data spec]
   {:pre [(or (nil? spec)
              (= '* spec)
              (vector? spec))]}
   (cond
     (nil? spec)
     nil

     (satisfies? proto/DataSource data)
     (muse/flat-map #(pull % spec) data)

     (satisfies? PullSource data)
     (pull-from data spec)

     (= '* spec)
     data

     :else
     data)))

(defn- fetch? [r]
  (or (satisfies? proto/DataSource r)
      (satisfies? proto/BatchedSource r)
      (satisfies? proto/MuseAST r)))

(defn- pull-from-collection [reduce-into this spec]
  (let [blocks (map #(pull % spec) this)]
    (if-not (some fetch? blocks)
      (muse/value (into reduce-into blocks))
      (->> (muse/collect (map #(if (fetch? %) % (muse/value %)) blocks))
           (muse/fmap (partial into reduce-into))))))

(extend-protocol PullSource
  clojure.lang.IPersistentList
  (pull-from [this spec]
    (pull-from-collection '() this spec))

  clojure.lang.IPersistentSet
  (pull-from [this spec]
    (pull-from-collection #{} this spec))

  clojure.lang.ISeq
  (pull-from [this spec]
    (let [blocks (map #(pull % spec) this)]
      (if-not (some fetch? blocks)
        (muse/value blocks)
        (muse/collect (map #(if (fetch? %) % (muse/value %)) blocks)))))

  clojure.lang.IPersistentVector
  (pull-from [this spec]
    (pull-from-collection [] this spec))

  clojure.lang.IPersistentMap
  (pull-from [this spec]
    (let [blocks (->> (if (= '* spec) (keys this) spec)
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
