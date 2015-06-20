(ns muse.test.cats
  (:use clojure.test)
  (:use muse.core)
  (:require [clojure.core.async :refer [go <!!]]
            [cats.core :as m]))

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

(deftest runner-macros
  (is (= 5 (<!! (run! (m/fmap count (List. 5))))))
  (is (= 10 (run!! (m/fmap count (List. 10))))))

(deftest cats-syntax
  (is (= 15 (run!! (m/mlet [x (List. 10)
                            y (List. 5)]
                           (m/return (+ (count x) (count y))))))))
