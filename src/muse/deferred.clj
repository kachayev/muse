(ns muse.deferred
  (:require [muse.protocol :as proto]
            [manifold.deferred :as d]
            [cats.core :refer (with-monad)])
  (:refer-clojure :exclude (run!)))

(def ast-monad proto/ast-monad)
(def fmap proto/fmap)
(def flat-map proto/flat-map)
(def value proto/value)
(def <$> proto/<$>)
(def >>= proto/>>=)
(def collect proto/collect)
(def traverse proto/traverse)
(def DataSource proto/DataSource)
(def fetch proto/fetch)
(def LabeledSource proto/LabeledSource)
(def resource-id proto/resource-id)
(def BatchedSource proto/BatchedSource)
(def fetch-multi proto/fetch-multi)

(defn- attach-resource-name [name d]
  (d/chain d (fn [result] [name result])))

(defn fetch-group [[resource-name group]]
  (attach-resource-name
   resource-name
   (let [all-res (->> group
                      (group-by proto/cache-id)
                      (map (fn [[_ v]] (first v))))
         [head & tail] all-res]
     (if (empty? tail)
       (d/chain
        (fetch head)
        #(-> {(proto/cache-id head) %}))
       (if (satisfies? BatchedSource head)
         (d/chain
          (fetch-multi head tail)
          (fn [results]
            (if (map? results)
              results
              (if (not= (count all-res) (count results))
                (throw (ex-info "Illegal output from BatchedSource fetch-multi"
                                {:inputs group
                                 :output-size (count results)
                                 :expected-size (count all-res)}))
                (into {} (map vector (map proto/cache-id all-res) results))))))
         (d/chain'
          (apply d/zip (map fetch all-res))
          (fn [fetch-results]
            (let [ids (map proto/cache-id all-res)]
              (into {} (map vector ids fetch-results))))))))))

(defn interpret-ast
  [ast]
  (d/loop [ast-node ast cache {}]
    (let [fetches (proto/next-level ast-node)]
      (if (empty? fetches)
        (if (proto/done? ast-node)
          (:value ast-node)
          (d/recur (proto/inject-into {:cache cache} ast-node) cache))
        (d/chain'
         (let [by-type (group-by proto/resource-name fetches)]
           (apply d/zip (map fetch-group by-type)))
         (fn [fetch-groups]
           (let [to-cache (into {} fetch-groups)
                 next-cache (into cache to-cache)]
             (d/recur (proto/inject-into {:cache next-cache} ast-node) next-cache))))))))

(defmacro run!
  "Asynchronously executes the body, returning immediately to the
   calling thread. Rebuild body AST in order to:
   * fetch data sources async (when possible)
   * cache result of previously made fetches
   * batch calls to the same data source (when applicable)
   Returns a channel which will receive the result of
   the body when completed."
  [ast]
  `(with-monad proto/ast-monad (interpret-ast ~ast)))

(defmacro run!!
  "takes a val from the channel returned by (run! ast).
   Will block if nothing is available. The variant taking
   a timeout will return timeout-val if the timeout
   (in milliseconds) is reached before a value is available."
  ([ast]
   `(deref (run! ~ast)))
  ([ast timeout-ms timeout-val]
   `(deref (d/timeout! (run! ~ast) ~timeout-ms ~timeout-val))))
