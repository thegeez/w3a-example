(ns w3a.example.migrations
  (:require [io.pedestal.log :as log]
            [clojure.java.jdbc :as jdbc]))

(defn serial-id [db]
  (if (.contains (:connection-uri db) "derby")
    [:id "INTEGER NOT NULL GENERATED ALWAYS AS IDENTITY (START WITH 1, INCREMENT BY 1)"]
    [:id :serial "PRIMARY KEY"]))

(def migrations
  [
   (let [table :migration_version]
     [1 {:up (fn [db]
               (jdbc/db-do-commands
                db (jdbc/create-table-ddl
                    table
                    [:id :int]
                    [:version :int]))
               (jdbc/insert! db
                             table {:id 0
                                    :version 1}))
         :down (fn [db]
                 (jdbc/db-do-commands
                  db (jdbc/drop-table-ddl
                      table)))}])
   (let [table :users]
     [2 {:up (fn [db]
               (jdbc/db-do-commands
                db (jdbc/create-table-ddl
                    table
                    (serial-id db)
                    [:name "VARCHAR(256) UNIQUE NOT NULL"]
                    [:password_encrypted "VARCHAR(256) NOT NULL"]
                    [:created_at "BIGINT"]
                    [:updated_at "BIGINT"])))
         :down (fn [db]
                 (jdbc/db-do-commands
                  db (jdbc/drop-table-ddl
                      table)))}])
   (let [table :snippets]
     [3 {:up (fn [db]
               (jdbc/db-do-commands
                db (jdbc/create-table-ddl
                    table
                    (serial-id db)
                    [:title "VARCHAR(256) UNIQUE NOT NULL"]
                    [:quality_good "BOOLEAN DEFAULT FALSE"]
                    [:quality_fast "BOOLEAN DEFAULT FALSE"]
                    [:quality_cheap "BOOLEAN DEFAULT FALSE"]
                    [:owner "BIGINT"]
                    [:created_at "BIGINT"]
                    [:updated_at "BIGINT"])))
         :down (fn [db]
                 (jdbc/db-do-commands
                  db (jdbc/drop-table-ddl
                      table)))}])])
