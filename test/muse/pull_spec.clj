(ns muse.pull-spec
  (:require [clojure.test :refer :all]
            [muse.deferred :as muse]
            [muse.protocol :as proto]
            [muse.pull :as pull]
            [manifold.deferred :as d])
  (:refer-clojure :exclude (run!)))

;;
;; data sources
;;

(defn fetch-messages-by-thread [_]
  (range 2))

(defn fetch-message-from-db [id]
  (d/success-deferred
    {:id id
     :text "The best argument against..."
     :sender-id (rand-int 10)}))

(defrecord User [id]
  muse/DataSource
  (fetch [_]
    (d/success-deferred
      {:id id
       :firstName "Winston"
       :lastName "Churchill"})))

(defrecord Message [message-id]
  muse/DataSource
  (fetch [_]
    (d/chain'
      (fetch-message-from-db message-id)
      (fn [{:keys [sender-id] :as message}]
        (assoc message :sender (User. sender-id)))))
  muse/LabeledSource
  (resource-id [_] (str "message/" message-id)))

(defrecord ChatThread [thread-id]
  muse/DataSource
  (fetch [_]
    (d/chain'
      (fetch-messages-by-thread thread-id)
      (fn [ids]
        {:thread thread-id
         :messages (map #(Message. %) ids)})))
  muse/LabeledSource
  (resource-id [_] (str "thread/" thread-id)))

;;
;; test cases
;;

(deftest invalid-spec-throws
  (is (thrown? AssertionError
               (pull/pull (Message. 1) :id)))
  (is (thrown? AssertionError
               (pull/pull (Message. 1) {:id '*})))
  (is (thrown? AssertionError
               (pull/pull (Message. 1) ['* '* '*]))))

(deftest no-pull-spec-given
  (let [r (muse/run!! (Message. 1))]
    (is (= 1 (:id r)))
    (is (some? (:sender r)))))

(deftest pull-everything-as-is
  (let [r (muse/run!! (pull/pull (Message. 1) '*))]
    (is (= 1 (:id r)))
    (is (some? (:sender r)))
    (is (= "Winston" (-> r :sender :firstName)))))

(deftest pull-a-single-key-from-map
  (let [r (muse/run!! (pull/pull (Message. 1) [:text]))]
    (is (string? (:text r)))
    (is (nil? (:id r)))
    (is (nil? (:sender-id r)))
    (is (nil? (:sender r)))))

(deftest pull-map-recursively
  (let [r (muse/run!! (pull/pull (Message. 1) [{:sender [:firstName]}]))]
    (is (= "Winston" (-> r :sender :firstName)))
    (is (nil? (-> r :sender :lastName)))))

(deftest pull-map-with-default-spec
  (let [r (muse/run!! (pull/pull (Message. 1) [{:sender '*}]))]
    (is (= "Winston" (-> r :sender :firstName)))
    (is (= "Churchill" (-> r :sender :lastName)))))

(deftest pull-vector-recursively
  (let [r (muse/run!! (pull/pull (ChatThread. 1) [{:messages [:text {:sender [:id]}]}]))]
    (is (int? (-> r :messages first :sender :id)))))
