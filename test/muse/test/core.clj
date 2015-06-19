(ns muse.test.core
  (:use clojure.test)
  (:use muse.core)
  (:require [clojure.core.async :refer [go <!!]]))

(defrecord List [size]
  DataSource
  (fetch [_] (go (range size)))
  LabeledSource
  (resource-id [_] size))

(defrecord Single [seed]
  DataSource
  (fetch [_] (go seed))
  LabeledSource
  (resource-id [_] seed))

(defrecord Pair [seed]
  DataSource
  (fetch [_] (go [seed seed]))
  LabeledSource
  (resource-id [_] seed))

(defn mk-pair [seed] (Pair. seed))

(defn sum-pair [[a b]] (+ a b))

(deftest datasource-ast
  (is (= 10 (count (<!! (run! (List. 10))))))
  (is (= 20 (count (run!! (List. 20)))))
  (is (= 30 (run!! (fmap count (List. 30)))))
  (is (= 40 (run!! (fmap inc (fmap count (List. 39))))))
  (is (= 50 (run!! (fmap count (fmap concat (List. 30) (List. 20))))))
  (is (= [15 15] (run!! (flat-map mk-pair (Single. 15)))))
  (is (= 60 (run!! (fmap sum-pair (flat-map mk-pair (Single. 30)))))))

(deftest higher-level-api
  (is (= [0 1] (run!! (collect [(Single. 0) (Single. 1)]))))
  (is (= [[0 0] [1 1]] (run!! (traverse mk-pair (List. 2))))))

(defn recur-next [seed]
  (if (= 5 seed)
    (value seed)
    (flat-map recur-next (Single. (inc seed)))))

(deftest recur-with-value
  (is (= 10 (run!! (value 10))))
  (is (= 5 (run!! (flat-map recur-next (Single. 0))))))

;; attention! never do such mutations within "fetch" in real code
(defrecord Trackable [tracker seed]
  DataSource
  (fetch [_] (go (swap! tracker inc) seed))
  LabeledSource
  (resource-id [_] seed))

(defrecord TrackableId [tracker id]
  DataSource
  (fetch [_] (go (swap! tracker inc) id)))

(deftest caching
  ;; w explicit source labeling
  (let [t (atom 0)]
    (is (= 40 (run!! (fmap + (Trackable. t 10) (Trackable. t 10) (Trackable. t 20)))))
    (is (= 2 @t)))
  ;; w/o explicit source labeling
  (let [t2 (atom 0)]
    (is (= 100 (run!! (fmap * (TrackableId. t2 10) (TrackableId. t2 10)))))
    (is (= 1 @t2)))
  ;; different tree branches/levels
  (let [t3 (atom 0)]
    (is (= 140 (run!! (fmap +
                            (Trackable. t3 50)
                            (fmap (fn [[a b]] (+ a b))
                                  (collect [(Trackable. t3 40) (Trackable. t3 50)]))))))
    (is (= 2 @t3))))
