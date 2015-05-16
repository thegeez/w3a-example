(ns w3a.example.main
  (:require [io.pedestal.log :as log]
            [com.stuartsierra.component :as component]
            [clojure.string :as string]
            [net.thegeez.w3a.system.sql-database :as sql-database]
            [w3a.example.core :as core]
            [w3a.example.migrations :as migrations])
  (:gen-class))

(defn -main [& args]
  (log/info :main "Running main" :args args)
  (let [port (try (Long/parseLong (first args))
                  (catch Exception _ -1))
        _ (assert (pos? port) (str "Something is wrong with the port argument: " (first args)))
        database-url (let [db-url (second args)]
                       (assert (.startsWith db-url "postgres:")
                               (str "Something is wrong with the database argument: " (second args)))
                       (sql-database/db-url-for-heroku db-url))
        system (core/prod-system {:db-connect-string database-url
                                  :port port
                                  :migrations migrations/migrations})]
    (component/start system)
    (.addShutdownHook (Runtime/getRuntime)
                      (Thread. (fn []
                                 (log/info :main "Shutting down main")
                                 (component/stop system))))))
