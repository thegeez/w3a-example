(ns user
  (:require [ns-tracker.core :refer [ns-tracker]]
            [com.stuartsierra.component :as component]
            [clojure.tools.namespace.repl :as repl]
            [io.pedestal.log :as log]
            [io.pedestal.http :as http]
            [net.thegeez.w3a.server :as server]
            [net.thegeez.w3a.system.sql-database :as database]
            [ring.middleware.session.memory]
            [w3a.example.fixtures :as fixtures]
            [w3a.example.migrations :as migrations]
            [w3a.example.service :as service]))

(def modified-namespaces (ns-tracker "src"))

(defn dev-service [service]
    (-> service ;; start with production configuration
      (merge {:env :dev
              ;; do not block thread that starts web server
              ::http/join? false
              ;; Routes can be a function that resolve routes,
              ;;  we can use this to set the routes to be reloadable
              ::http/routes #(do
                                 (doseq [ns-sym (modified-namespaces)]
                                   (require ns-sym :reload))
                                 (deref #'service/routes))
              ;; all origins are allowed in dev mode
              ::http/allowed-origins {:creds true :allowed-origins (constantly true)}})
      ;; Wire up interceptor chains
      http/default-interceptors
      http/dev-interceptors))

(defn dev-system [config-options]
  (log/info :msg "Hello world, this is the development system!")
  (let [{:keys [db-connect-string port migrations]} config-options]
    (component/system-map
     :session-options {:store (ring.middleware.session.memory/memory-store)}
     :server (component/using
              (server/pedestal-component (dev-service
                                          (assoc service/service
                                            ::http/port port)))
              {:database :db
               :session-options :session-options})
     :jetty (component/using
             (server/jetty-component)
             [:server])
     :db (database/database db-connect-string)
     :db-migrator (component/using
                   (database/dev-migrator migrations)
                   {:database :db})
     :fixtures (component/using
                (fixtures/fixtures)
                {:database :db
                 :db-migrator :db-migrator}))))

(def dev-config {:db-connect-string "jdbc:derby:memory:snippets;create=true"
                 :port 8080
                 :migrations migrations/migrations})

(def system nil)

(defn init []
  (alter-var-root #'system
                  (constantly (dev-system dev-config))))

(defn start []
  (alter-var-root #'system component/start)
  :started)

(defn stop []
  (alter-var-root #'system
    (fn [s] (when s (component/stop s) nil))))

(defn go []
  (if system
    "System not nil, use (reset) ?"
    (do (init)
        (start))))

(defn reset []
  (stop)
  (repl/refresh :after 'user/go))

;; lein trampoline run -m user/run
(defn run []
  (go)
  (.addShutdownHook (Runtime/getRuntime)
                    (Thread. (fn []
                               (stop)))))
