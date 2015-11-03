(ns muse.core-spec
  #?(:clj
     (:require [clojure.test :refer (deftest is)]
               [clojure.core.async :refer (go <!!)]
               [muse.core :as muse :refer (fmap flat-map)])
     :cljs
     (:require [cljs.test :refer-macros (deftest is async)]
               [cljs.core.async :refer (take!)]
               [muse.core :as muse :refer (fmap flat-map)]))
  #? (:cljs (:require-macros [cljs.core.async.macros :refer (go)]))
  (:refer-clojure :exclude (run!)))

(defrecord DList [size]
  muse/DataSource
  (fetch [_] (go (range size)))
  muse/LabeledSource
  (resource-id [_] #?(:clj size :cljs [:DList size])))

(defrecord Single [seed]
  muse/DataSource
  (fetch [_] (go seed))
  muse/LabeledSource
  (resource-id [_] #?(:clj seed :cljs [:Single seed])))

(defrecord Pair [seed]
  muse/DataSource
  (fetch [_] (go [seed seed]))
  muse/LabeledSource
  (resource-id [_] #?(:clj seed :cljs [:Pair seed])))

(defn- mk-pair [seed] (Pair. seed))

(defn- sum-pair [[a b]] (+ a b))

(defn- assert-ast
  ([expected ast] (assert-ast expected ast nil))
  ([expected ast callback]
   #?(:clj (is (= expected (muse/run!! ast)))
      :cljs (async done (take! (muse/run! ast)
                               (fn [r]
                                 (is (= expected r))
                                 (when callback (callback))
                                 (done)))))))

(deftest datasource-ast
  #?(:clj (is (= 10 (count (<!! (muse/run! (DList. 10)))))))
  #?(:clj (is (= 20 (count (muse/run!! (DList. 20))))))
  (assert-ast 30 (fmap count (DList. 30)))
  (assert-ast 40 (fmap inc (fmap count (DList. 39))))
  (assert-ast 50 (fmap count (fmap concat (DList. 30) (DList. 20))))
  (assert-ast [15 15] (flat-map mk-pair (Single. 15)))
  (assert-ast 60 (fmap sum-pair (flat-map mk-pair (Single. 30)))))

(deftest higher-level-api
  (assert-ast [0 1] (muse/collect [(Single. 0) (Single. 1)]))
  (assert-ast [] (muse/collect []))
  (assert-ast [[0 0] [1 1]] (muse/traverse mk-pair (DList. 2)))
  (assert-ast [] (muse/traverse mk-pair (DList. 0))))

(defn- recur-next [seed]
  (if (= 5 seed)
    (muse/value seed)
    (flat-map recur-next (Single. (inc seed)))))

(deftest recur-with-value
  (assert-ast 10 (muse/value 10))
  (assert-ast 5 (flat-map recur-next (Single. 0))))

(defn- assert-failed? [f]
  (is (thrown? #?(:clj AssertionError :cljs js/Error) (f))))

(deftest value-from-ast
  (assert-failed? #(muse/value (Single. 0)))
  (assert-failed? #(muse/value (fmap inc (muse/value 0)))))

;; attention! never do such mutations within "fetch" in real code
(defrecord Trackable [tracker seed]
  muse/DataSource
  (fetch [_] (go (swap! tracker inc) seed))
  muse/LabeledSource
  (resource-id [_] #?(:clj seed :cljs [:Trackable seed])))

(defrecord TrackableName [tracker seed]
  muse/DataSource
  (fetch [_] (go (swap! tracker inc) seed))
  muse/LabeledSource
  (resource-id [_] [:name seed]))

;; note, that automatic name resolution doesn't work in ClojureScript
#?(:clj
   (defrecord TrackableId [tracker id]
     muse/DataSource
     (fetch [_] (go (swap! tracker inc) id))))

;; w explicit source labeling
#?(:clj
   (deftest caching-explicit-labels
     (let [t (atom 0)]
       (assert-ast 40 (fmap + (Trackable. t 10) (Trackable. t 10) (Trackable. t 20)))
       (is (= 2 @t)))
     (let [t1 (atom 0)]
       (assert-ast 400 (fmap + (TrackableName. t1 100) (TrackableName. t1 100) (TrackableName. t1 200)))
       (is (= 2 @t1)))))

#?(:cljs
   (deftest caching-explict-labels
     (let [t (atom 0)]
       (assert-ast 40 (fmap + (Trackable. t 10) (Trackable. t 10) (Trackable. t 20))
                   (fn [] (is (= 2 @t)))))))

#?(:cljs
   (deftest caching-explicit-labels-namespaced
     (let [t1 (atom 0)]
       (assert-ast 400 (fmap + (TrackableName. t1 100) (TrackableName. t1 100) (TrackableName. t1 200))
                   (fn [] (is (= 2 @t1)))))))

;; w/o explicit source labeling
#?(:clj
   (deftest caching-implicit-labels
     (let [t2 (atom 0)]
       (assert-ast 100 (fmap * (TrackableId. t2 10) (TrackableId. t2 10)))
       (is (= 1 @t2)))))

;; different tree branches/levels
#?(:clj
   (deftest caching-multiple-trees
     (let [t3 (atom 0)]
       (assert-ast 140 (fmap +
                             (Trackable. t3 50)
                             (fmap (fn [[a b]] (+ a b))
                                   (muse/collect [(Trackable. t3 40) (Trackable. t3 50)]))))
       (is (= 2 @t3)))
     (let [t4 (atom 0)]
       (assert-ast 1400 (fmap +
                              (TrackableName. t4 500)
                              (fmap (fn [[a b]] (+ a b))
                                    (muse/collect [(TrackableName. t4 400) (TrackableName. t4 500)]))))
       (is (= 2 @t4)))))

#?(:cljs
   (deftest caching-multiple-trees
     (let [t3 (atom 0)]
       (assert-ast 140 (fmap +
                             (Trackable. t3 50)
                             (fmap (fn [[a b]] (+ a b))
                                   (muse/collect [(Trackable. t3 40) (Trackable. t3 50)])))
                   (fn [] (is (= 2 @t3)))))))

#?(:cljs
   (deftest caching-multiple-trees-namespaced
     (let [t4 (atom 0)]
       (assert-ast 1400 (fmap +
                              (TrackableName. t4 500)
                              (fmap (fn [[a b]] (+ a b))
                                    (muse/collect [(TrackableName. t4 400) (TrackableName. t4 500)])))
                   (fn [] (is (= 2 @t4)))))))

;; resouce should be identifiable: both Name and ID

#_(defrecord Country [iso-id]
    muse/DataSource
    (fetch [_] (go {:regions [{:code 1} {:code 2} {:code 3}]})))

#_(defrecord Region [country-iso-id url-id]
    muse/DataSource
    (fetch [_] (go (inc url-id))))

#_(deftest disabled-caching
    (is (nil? (try (run!! (->> (Country. "es")
                               (fmap :regions)
                               (traverse #(Region. "es" (:code %)))))
                   (catch Exception e e)))))
