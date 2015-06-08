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
