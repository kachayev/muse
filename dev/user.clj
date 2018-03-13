(ns user
  (:require [muse.deferred :as muse]
            [manifold.deferred :as d])
  (:refer-clojure :exclude (run!)))

(deftype PullEntry [entry])

(deftype PullCollection [entries])

(defn fetch-messages-by-thread [id]
  (range 5))

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
       (assoc message :sender (PullEntry. (User. sender-id))))))
  muse/LabeledSource
  (resource-id [_] message-id))

(defrecord ChatThread [thread-id]
  muse/DataSource
  (fetch [_]
    (d/chain'
     (fetch-messages-by-thread thread-id)
     (fn [ids]
       {:thread thread-id
        :messages (PullCollection. (map #(Message. %) ids))})))
  muse/LabeledSource
  (resource-id [_] thread-id))

(comment

  (muse/run!! (Message. 1))
  ;; {:id 1, :text "The best argument against...", :sender-id 2}
  
  (muse/run!! (Message. 1) {:pull [:text]})
  ;; {:text "The best argument against..."}

  (muse/run!! (Message. 1) {:pull [:text {:sender [:firstName :lastName]}]})
  ;; {:text "The best argument against...", :sender {:firstName "Winston", :lastName "Churchill"}}

  ;; this one is tricky:
  (muse/run!! (ChatThread. 1) {:pull [{:messages [:text {:sender [:id]}]}]})
  
  )
