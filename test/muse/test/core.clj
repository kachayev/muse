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
  (is (= [] (run!! (collect []))))
  (is (= [[0 0] [1 1]] (run!! (traverse mk-pair (List. 2)))))
  (is (= [] (run!! (traverse mk-pair (List. 0))))))

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

(defrecord TrackableName [tracker seed]
  DataSource
  (fetch [_] (go (swap! tracker inc) seed))
  LabeledSource
  (resource-id [_] [:name seed]))

(defrecord TrackableId [tracker id]
  DataSource
  (fetch [_] (go (swap! tracker inc) id)))

;; w explicit source labeling
(deftest caching-explicit-labels
  (let [t (atom 0)]
    (is (= 40 (run!! (fmap + (Trackable. t 10) (Trackable. t 10) (Trackable. t 20)))))
    (is (= 2 @t)))
  (let [t1 (atom 0)]
    (is (= 400 (run!! (fmap + (TrackableName. t1 100) (TrackableName. t1 100) (TrackableName. t1 200)))))
    (is (= 2 @t1))))

;; w/o explicit source labeling
(deftest caching-implicit-labels
  (let [t2 (atom 0)]
    (is (= 100 (run!! (fmap * (TrackableId. t2 10) (TrackableId. t2 10)))))
    (is (= 1 @t2))))

;; different tree branches/levels
(deftest caching-multiple-trees
  (let [t3 (atom 0)]
    (is (= 140 (run!! (fmap +
                            (Trackable. t3 50)
                            (fmap (fn [[a b]] (+ a b))
                                  (collect [(Trackable. t3 40) (Trackable. t3 50)]))))))
    (is (= 2 @t3)))
  (let [t4 (atom 0)]
    (is (= 1400 (run!! (fmap +
                             (TrackableName. t4 500)
                             (fmap (fn [[a b]] (+ a b))
                                   (collect [(TrackableName. t4 400) (TrackableName. t4 500)]))))))
    (is (= 2 @t4))))


;; resouce should be identifiable: both Name and ID

#_(defrecord Country [iso-id]
    DataSource
    (fetch [_] (go {:regions [{:code 1} {:code 2} {:code 3}]})))

#_(defrecord Region [country-iso-id url-id]
    DataSource
    (fetch [_] (go (inc url-id))))

#_(deftest disabled-caching
    (is (nil? (try (run!! (->> (Country. "es")
                               (fmap :regions)
                               (traverse #(Region. "es" (:code %)))))
                   (catch Exception e e)))))
