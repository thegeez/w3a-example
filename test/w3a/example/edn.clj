(ns w3a.example.edn
  (:require [clojure.test :refer [deftest is]]
            [io.pedestal.log :as log]
            [net.thegeez.w3a.test :as w3a-test]
            [peridot.core :as p]
            [user :as user]))

(defn ring-handler []
  (w3a-test/system->ring-handler (user/dev-system user/dev-config)))

;; curl -vv -X POST -H "Content-Type: application/edn" -d
;; '{:title "new title"}' http://localhost:8080/snippets/new

(deftest new-snippet
  (-> (p/session (ring-handler))
      (p/header "Accept" "application/edn")
      (p/request "/")
      ((fn [res]
         (let [csrf-token (get-in res [:response :headers "X-TEST-HELPER-CSRF"])]
           (-> res
               (p/header "X-CSRF-TOKEN" csrf-token)
               ((fn [res]
                  (let [snippets (get-in res [:response :edn :links :snippets])]
                    (is (= "http://testhost:-1/snippets" snippets))
                    (-> res
                        (p/request snippets)
                        ((fn [res]
                           (is (= 101 (get-in res [:response :edn :snippets :count])))
                           (is (= 10 (count (get-in res [:response :edn :snippets :results]))))
                           (let [new (get-in res [:response :edn :new])]
                             (is (= "http://testhost:-1/snippets/new" new))
                             (-> res
                                 (p/request new)
                                 ((fn [res]
                                    (is (= (-> res :response :status) 303))
                                    (-> res
                                        (p/content-type "application/edn")
                                        (p/request (get-in res [:response :headers "Location"])
                                                   :request-method :post
                                                   :params ^:edn {:name "amy"
                                                                  :password "amy"})
                                        ((fn [res]
                                           (is (= (-> res :response :status) 303))
                                           (-> res
                                               (p/request (get-in res [:response :headers "Location"]))
                                               ((fn [res]
                                                  (is (= new (-> res :response :edn :breadcrumbs last :link)))
                                                  (-> res
                                                      (p/request new
                                                                 :request-method :post
                                                                 :params ^:edn {:title nil
                                                                                :quality
                                                                                {:fast true
                                                                                 :cheap true
                                                                                 :good true}})
                                                      ((fn [res]
                                                         (is (= (-> res :response :status) 400))
                                                         (is (= (get-in res [:response :edn :snippet :errors])
                                                                {:title ["Title can't be empty"]
                                                                 :quality ["Sorry can only pick two"]}))
                                                         res))
                                                      (p/request new
                                                                 :request-method :post
                                                                 :params ^:edn {:title "New test snippet"
                                                                                :quality {:fast true :cheap true}})
                                                      ((fn [res]
                                                         (is (= (-> res :response :status) 201))
                                                         (let [loc (get-in res [:response :headers "Location"])]
                                                           (is (= loc "http://testhost:-1/snippets/102"))
                                                           (-> res
                                                               (p/request loc)
                                                               ((fn [res]
                                                                  (is (= (get-in res [:response :edn :snippet :title]) "New test snippet"))
                                                                  (is (= (get-in res [:response :edn :snippet :quality]) {:fast true :cheap true}))
                                                                  res)))))))
                                                  ))))))))))))))))))))))

(deftest edit-snippet
  (-> (p/session (ring-handler))
      (p/header "Accept" "application/edn")
      (p/request "/")
      ((fn [res]
         (let [csrf-token (get-in res [:response :headers "X-TEST-HELPER-CSRF"])]
           (-> res
               (p/header "X-CSRF-TOKEN" csrf-token)
               ((fn [res]
                  (log/info :res res)
                  (let [snippets (get-in res [:response :edn :links :snippets])]
                    (is (= "http://testhost:-1/snippets" snippets))
                    (-> res
                        (p/request snippets)
                        ((fn [res]
                           (is (= 101 (get-in res [:response :edn :snippets :count])))
                           (log/info :res (get-in res [:response :edn :snippets]))
                           (let [url (-> res :response :edn :snippets :results first :url)
                                 edit (str url "/edit")]
                             (is (= "http://testhost:-1/snippets/1/edit" edit))
                             (-> res
                                 (p/request edit)
                                 ((fn [res]
                                    (is (= (-> res :response :status) 401))
                                    (-> res
                                        (p/content-type "application/edn")
                                        (p/request "http://testhost:-1/login"
                                                   :request-method :post
                                                   :params ^:edn {:name "amy"
                                                                  :password "amy"})
                                        ((fn [res]
                                           (is (= (-> res :response :status) 303))
                                           (-> res
                                               (p/request (get-in res [:response :headers "Location"]))
                                               ((fn [res]

                                                  (is (= "http://testhost:-1/users/1" (-> res :response :edn :breadcrumbs last :link)))))))))
                                    (-> res
                                        (p/content-type "application/edn")
                                        (p/request edit
                                                   :request-method :post
                                                   :params ^:edn {:title nil})
                                        ((fn [res]
                                           (is (= (-> res :response :status) 400))
                                           (is (= (get-in res [:response :edn :snippet :errors])
                                                  {:title ["Title can't be empty"]}))
                                           res))
                                        (p/request edit
                                                   :request-method :post
                                                   :params ^:edn {:title "Edited snippet title"})
                                        ((fn [res]
                                           (is (= (-> res :response :status) 201))
                                           (let [loc (get-in res [:response :headers "Location"])]
                                             (is (= loc "http://testhost:-1/snippets/1"))
                                             (-> res
                                                 (p/request loc)
                                                 ((fn [res]
                                                    (is (= (get-in res [:response :edn :snippet :title]) "Edited snippet title"))
                                                    res)))))))
                                    ))))))))))))))))

