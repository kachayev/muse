(ns muse.core
  #?(:cljs (:require-macros [muse.core :refer (run!)]
                            [cljs.core.async.macros :refer (go)])
     :clj (:require [cats.core :refer (with-monad)]
                    [clojure.core.async :as async :refer (go <! <!!)]
                    [muse.protocol :as proto]))
  #?(:cljs (:require [cljs.core.async :as async :refer (<!)]
                     [muse.protocol :as proto]))
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

(defn fetch-group [[resource-name group]]
  (go
    [resource-name
     (let [all-res (->> group
                        (group-by proto/cache-id)
                        (map (fn [[_ v]] (first v))))
           ids (map proto/cache-id all-res)
           [head & tail] all-res]
       (if (empty? tail)
         {(proto/cache-id head) (<! (fetch head))}
         (if (satisfies? BatchedSource head)
           (let [fetch-results (<! (fetch-multi head tail))]
             (if (map? fetch-results)
               fetch-results
               (if (not= (count ids) (count fetch-results))
                 (throw (ex-info "Illegal output from BatchedSource fetch-multi"
                                 {:inputs group
                                  :output-size (count fetch-results)
                                  :expected-size (count ids)}))
                (into {} (map vector ids fetch-results)))))
           (let [fetch-results (<! (async/map vector (map fetch all-res)))]
             (into {} (map vector ids fetch-results))))))]))

(defn interpret-ast [ast]
  (go
    (loop [ast-node ast cache {}]
      (let [fetches (proto/next-level ast-node)]
        (if (empty? fetches)
          (if (proto/done? ast-node)
            (:value ast-node)
            (recur (proto/inject-into {:cache cache} ast-node) cache))
          (let [by-type (group-by proto/resource-name fetches)
                fetch-groups (<! (async/map vector (map fetch-group by-type)))
                to-cache (into {} fetch-groups)
                next-cache (into cache to-cache)]
            (recur (proto/inject-into {:cache next-cache} ast-node) next-cache)))))))

#?(:clj
   (defmacro run!
     "Asynchronously executes the body, returning immediately to the
      calling thread. Rebuild body AST in order to:
      * fetch data sources async (when possible)
      * cache result of previously made fetches
      * batch calls to the same data source (when applicable)
      Returns a channel which will receive the result of
      the body when completed."
     [ast]
     `(with-monad proto/ast-monad (interpret-ast ~ast))))

#?(:clj
   (defmacro run!!
     "takes a val from the channel returned by (run! ast).
      Will block if nothing is available. Not available on
      ClojureScript. The variant taking a timeout will return
      timeout-val if the timeout (in milliseconds) is reached
      before a value is available."
     ([ast]
      `(<!! (run! ~ast)))
     ([ast timeout-ms timeout-val]
      `(let [ret-chan# (run! ~ast)
             timeout-chan# (async/timeout ~timeout-ms)
             [ret# timeout#] (async/alts!! [ret-chan# timeout-chan#])]
         (if ret# ret# ~timeout-val)))))
