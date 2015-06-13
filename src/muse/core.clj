(ns muse.core
  (:require [clojure.core.async :as async :refer [go <! >! <!! >!!]]))

(defprotocol DataSource
  (fetch [this]))

(defprotocol LabeledSource
  (resource-id [this]))

(defprotocol BatchedSource
  (fetch-multi [this resources]))

;; xxx: what to do with "reify"?
(defn resource-name [v]
  (.getName (.getClass v)))

(defprotocol MuseAST
  (inject [this env])
  (done? [this]))

(defprotocol ComposedAST
  (compose-ast [this f]))

(defrecord MuseDone [value]
  MuseAST
  (done? [_] true)
  (inject [this _] this))

(defn cache-id [res]
  (if (satisfies? LabeledSource res)
    (resource-id res)
    (:id res)))

(defn cache-path [res]
  [(resource-name res) (cache-id res)])

(defn cached-or [env res]
  (let [cached (get-in env (cons :cache (cache-path res)) ::not-found)]
    (if (= ::not-found cached)
      res
      (MuseDone. cached))))

(defn inject-into [env node]
  (if (satisfies? DataSource node)
    (cached-or env node)
    (inject node env)))

(defrecord MuseMap [f values]
  ComposedAST
  (compose-ast [_ f2] (MuseMap. (comp f2 f) values))
  MuseAST
  (done? [_] false)
  (inject [_ env]
    (let [next (map (partial inject-into env) values)]
      (if (= (count next) (count (filter done? next)))
        (MuseDone. (apply f (map :value next)))
        (MuseMap. f next)))))

(defn assert-ast! [ast]
  (assert (or (satisfies? MuseAST ast)
              (satisfies? DataSource ast))))

(defrecord MuseFlatMap [f values]
  MuseAST
  (done? [_] false)
  (inject [_ env]
    (let [next (map (partial inject-into env) values)]
      (if (= (count next) (count (filter done? next)))
        (let [result (apply f (map :value next))]
          ;; xxx: refactor to avoid dummy leaves creation
          (if (satisfies? DataSource result) (MuseMap. identity [result]) result))
        (MuseFlatMap. f next)))))

(defrecord MuseValue [value]
  ComposedAST
  (compose-ast [_ f2] (MuseValue. (f2 value)))
  MuseAST
  (done? [_] false)
  (inject [_ env] (MuseDone. value)))

(defn value [v]
  (MuseValue. v))

(defn fmap [f muse & muses]
  (if (and (not (seq muses))
           (satisfies? ComposedAST muse))
    (compose-ast muse f)
    (MuseMap. f (cons muse muses))))

;; xxx: make it compatible with algo.generic and cats libraries
(defn flat-map [f muse & muses]
  (MuseFlatMap. f (cons muse muses)))

;; xxx: use macro instead for auto-partial function building
(def <$> fmap)
(defn >>= [muse f] (flat-map f muse))

(defn collect [muses]
  (apply (partial fmap vector) muses))

(defn traverse [f muses]
  (flat-map #(collect (map f %)) muses))

(defn next-level [ast-node]
  (if (satisfies? DataSource ast-node)
    (list ast-node)
    (if-let [values (:values ast-node)] (mapcat next-level values) '())))

(defn fetch-group [[rname [head & tail]]]
  (go [rname
       (if (not (seq tail))
         (let [res (<! (fetch head))] {(cache-id head) res})
         (if (satisfies? BatchedSource head)
           (<! (fetch-multi head tail))
           (let [all-res (map (fn [[k v]] (first v)) (group-by cache-id (cons head tail)))]
             ;; xxx: refactor
             (<! (go (let [ids (map cache-id all-res)
                           fetch-results (<! (async/map vector (map fetch all-res)))]
                       (into {} (map vector ids fetch-results))))))))]))

;; xxx: catch & propagate exceptions
(defn run!
  "Asynchronously executes the body, returning immediately to the
  calling thread. Rebuild body AST in order to:
  * fetch data sources async (when possible)
  * cache result of previously made fetches
  * batch calls to the same data source (when applicable)
  Returns a channel which will receive the result of
  the body when completed."
  [ast]
  (go
   (loop [ast-node ast cache {}]
     (let [fetches (next-level ast-node)]
       (if (not (seq fetches))
         (:value ast-node) ;; xxx: should be MuseDone, assert & throw exception otherwise
         (let [by-type (group-by resource-name fetches)
               fetch-groups (<! (async/map vector (map fetch-group by-type)))
               to-cache (into {} fetch-groups)
               next-cache (into cache to-cache)]
           (recur (inject-into {:cache next-cache} ast-node) next-cache)))))))

(defn run!!
  "takes a val from the channel return by (run! ast).
  Will block if nothing is available."
  [ast]
  (<!! (run! ast)))
