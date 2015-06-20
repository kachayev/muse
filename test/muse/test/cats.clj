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

(deftest cats-api
  (is (satisfies? MuseAST
                  (m/with-monad ast-monad
                    (m/fmap count (List. 10)))))
  (is (satisfies? MuseAST
                  (m/with-monad ast-monad
                    (m/bind (Single. 10) (fn [num] (Single. (inc num))))))))

(deftest runner-macros
  (is (= 5 (<!! (run! (m/fmap count (List. 5))))))
  (is (= 10 (run!! (m/fmap count (List. 10))))))

(deftest cats-syntax
  (is (= 30 (run!! (m/mlet [x (List. 5)
                            y (List. 10)
                            z (Single. 15)]
                           (m/return (+ (count x) (count y) z)))))))
