(ns muse.cats-spec
  #?(:clj
     (:require [clojure.test :refer (deftest is)]
               [manifold.deferred :as d]
               [muse.core :as muse]
               [cats.core :as m])
     :cljs
     (:require [cljs.test :refer-macros (deftest is async)]
               [cljs.core.async :as a :refer (take!)]
               [muse.core :as muse]
               [cats.core :as m]))
  #? (:cljs (:require-macros [cljs.core.async.macros :refer (go)]))
  (:refer-clojure :exclude (run!)))

(defrecord DList [size]
  muse/DataSource
  (fetch [_] (d/future (range size)))
  muse/LabeledSource
  (resource-id [_] #?(:clj size :cljs [:DList size])))

(defrecord Single [seed]
  muse/DataSource
  (fetch [_] (d/future seed))
  muse/LabeledSource
  (resource-id [_] #?(:clj seed :cljs [:Single seed])))

(deftest cats-api
  (is (satisfies? muse/MuseAST (m/fmap count (muse/value (range 10)))))
  (is (satisfies? muse/MuseAST
                  (m/with-monad muse/ast-monad
                    (m/fmap count (DList. 10)))))
  (is (satisfies? muse/MuseAST
                  (m/with-monad muse/ast-monad
                    (m/bind (Single. 10) (fn [num] (Single. (inc num))))))))

(defn assert-ast [expected ast-factory]
  #?(:clj (is (= expected (muse/run!! (ast-factory))))
     :cljs (async done (take! (muse/run! (ast-factory)) (fn [r] (is (= expected r)) (done))))))

(deftest runner-macros
  #?(:clj (is (= 5 @(muse/run! (m/fmap count (DList. 5))))))
  (assert-ast 10 (fn [] (m/fmap count (DList. 10))))
  (assert-ast 15 (fn [] (m/bind (Single. 10) (fn [num] (Single. (+ 5 num)))))))

#?(:clj
   (deftest cats-syntax
     (assert-ast 30 (fn [] (m/mlet [x (DList. 5)
                                    y (DList. 10)
                                    z (Single. 15)]
                                   (m/return (+ (count x) (count y) z)))))))
