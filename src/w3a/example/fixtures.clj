(ns w3a.example.fixtures
  (:require [buddy.hashers :as hashers]
            [com.stuartsierra.component :as component]
            [io.pedestal.log :as log]
            [clojure.java.jdbc :as jdbc]))

(defrecord Fixtures [database]
  component/Lifecycle
  (start [component]
    (log/info :msg "Starting fixture loader")
    (when-not (:loaded-fixtures component)
      (try
        (let [db (:connection database)
              now (.getTime (java.util.Date.))]
          (jdbc/insert! db :users
                        {:name "amy"
                         :password_encrypted (hashers/encrypt "amy")
                         :created_at now
                         :updated_at now}
                        {:name "bob"
                         :password_encrypted (hashers/encrypt "bob")
                         :created_at now
                         :updated_at now})
          (let [[amy-id bob-id] (map #(:id (first (jdbc/query db ["select id from users where name = ?" %]))) ["amy" "bob"])]
            (apply
             jdbc/insert! db :snippets
             {:title "My first snippet"
              :owner amy-id
              :quality_fast true
              :created_at now
              :updated_at now}
             {:title "My second snippet"
              :owner bob-id
              :quality_cheap true
              :created_at now
              :updated_at now}
             (repeatedly 99
                         (fn []
                           {:title (str "Snippet " (long (* 10000000000 (rand))))
                            :owner (rand-nth [amy-id bob-id])
                            :quality_good (rand-nth [true false])
                            :quality_fast (rand-nth [true false])
                            :quality_cheap (rand-nth [true false])
                            :created_at now
                            :updated_at now})))))
        (catch Exception e
          (log/info :loading-fixtures-failed (.getMessage e)))))
    (assoc component :loaded-fixtures true))

  (stop [component]
    (log/info :msg "Stopping fixture loader")
    component))

(defn fixtures []
  (map->Fixtures {}))
