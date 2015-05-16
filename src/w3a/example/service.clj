(ns w3a.example.service
  (:require [buddy.hashers :as hashers]
            [clojure.java.jdbc :as jdbc]
            [io.pedestal.log :as log]
            [io.pedestal.http :as http]
            [io.pedestal.http.route :as route]
            [io.pedestal.http.body-params :as body-params]
            [io.pedestal.http.ring-middlewares :as middlewares]
            [io.pedestal.http.route.definition :refer [defroutes]]
            [io.pedestal.interceptor :as interceptor]
            [io.pedestal.impl.interceptor :as impl-interceptor]
            [net.cgrand.enlive-html :as html]
            [net.thegeez.w3a.binding :as binding]
            [net.thegeez.w3a.edn-wrap :as edn-wrap]
            [net.thegeez.w3a.form :as form]
            [net.thegeez.w3a.interceptor :refer [combine] :as w3ainterceptor]
            [net.thegeez.w3a.link :as link]
            [net.thegeez.w3a.pagination :as pagination]))

(def application-frame (html/html-resource "templates/application.html"))
(def breadcrumbs-html (-> (html/html-resource "templates/breadcrumbs.html")
                          (html/select [:ul.breadcrumb])))

(defn breadcrumbs [breadcrumbs]
  (html/at breadcrumbs-html
           [:ul [:li html/first-of-type]] (html/clone-for [crumb breadcrumbs]
                                                          [:li :a] (html/content (:title crumb))
                                                          [:li :a] (html/set-attr :href (:link crumb)))
           [:ul [:li html/last-of-type]] (html/set-attr :class "active")))

(defn login-box-html [context]
  (if-let [auth (get-in context [:response :data :auth])]
    (html/transform-content
     [:a#name] (html/content (:name auth))
     [:a#user] (html/set-attr :href (:url auth))
     [:form#logout] (html/do->
                     (html/prepend
                      (html/html [:input {:type "hidden"
                                          :name "__anti-forgery-token"
                                          :value (get-in context [:request :io.pedestal.http.csrf/anti-forgery-token])}]))
                     (html/set-attr :action (str (:logout auth)
                                                 "?return-to=" (get context :self)))))
    (html/transform-content
     [:li]
     (html/content (html/html [:a {:href (link/link context ::login :query-params {:return-to (link/self context)})}
                               "Login"])))))

(defn flash-html [context]
  (if-let [msg (get-in context [:response :data :flash :message])]
    (html/before
     (html/html [:div {:id "flash"
                       :class "alert alert-info"} msg]))
    identity))

(def with-html
  (interceptor/interceptor
   {:leave (fn [context]
             (cond-> context
                     (edn-wrap/for-html? context)
                     (->
                      (assoc-in [:response :headers "Content-Type"] "text/html")
                      (update-in [:response :body]
                                 (fn [body]
                                   (apply str (html/emit*
                                               (html/at application-frame
                                                        [:#login-box]
                                                        (login-box-html context)

                                                        [:#content]
                                                        (flash-html context)

                                                        [:#content]
                                                        (html/before (breadcrumbs (get-in context [:response :data :breadcrumbs])))
                                                        [:#content]
                                                        (if-let [pagination (get-in context [:response :data :snippets])]
                                                          (html/before (pagination/html pagination))
                                                          identity)
                                                        [:#content] (html/html-content
                                                                     (edn-wrap/html-edn context))
                                                        [:#content] (html/append
                                                                     (map html/html (edn-wrap/forms-edn context)))))))))))}))

(def with-login-html
  (interceptor/interceptor
   {:leave (fn [context]
             (cond-> context
                     (edn-wrap/for-html? context)
                     (->
                      (assoc-in [:response :headers "Content-Type"] "text/html")
                      (update-in [:response :body]
                                 (fn [body]
                                   (apply str (html/emit*
                                               (html/at application-frame
                                                        [:.navbar] nil
                                                        [:#content]
                                                        (flash-html context)

                                                        [:#content] (html/before
                                                                     (html/html [:h1 "w3a example login"]))
                                                        [:#content] (html/append (html/html [:div "Login with amy/amy or bob/bob"]))
                                                        [:#content] (html/append
                                                                     (map html/html (edn-wrap/forms-edn context)))))))))))}))


(def home
  (interceptor/interceptor
   {:leave (fn [context]
             (combine context
                      {:response
                       {:status 200
                        :data {:breadcrumbs (get-in context [:response ::link/breadcrumbs])
                               :links {:signup (link/link context ::signup)
                                       :login (link/link context ::login)
                                       :users (link/link context ::users)
                                       :snippets (link/link context ::snippets)}}}}))}))

(defn user-resource [context data]
  (let [{:keys [id]} data]
    (-> data
        (dissoc :id)
        (assoc :url (link/link context ::user :params {:id id}))
        (update-in [:snippets] (fn [snippets]
                                 (map #(link/link context ::snippet :params {:id (:id %)}) snippets))))))

(defn get-users [db]
  (jdbc/query db ["SELECT id, name, created_at, updated_at FROM users"]))

(def users
  (interceptor/interceptor
   {:leave (fn [context]
             (combine
              context
              {:response
               {:status 200
                :data {:flash (get-in context [:request :flash])
                       :breadcrumbs (get-in context [:response ::link/breadcrumbs])
                       :signup (link/link context ::signup)
                       :users (->> (get-users (:database context))
                                   (map (partial user-resource context)))}}}))}))

(def signup-binding
  {:user
   [{:id :name
     :label "Name"
     :type :string
     :validator (fn [{{{:keys [name]} :params} :request :as context}]
                  (when (not (seq name))
                    {:name ["Name can't be empty"]}
))}
    {:id :password
     :label "Password"
     :type :password
     :validator (fn [{{{:keys [password]} :params} :request :as context}]
                  (when (not (seq password))
                    {:password ["Password can't be empty"]}
                    ))}
    {:id :password-confirm
     :label "Password (repeat)"
     :type :password
     :validator (fn [{{{:keys [password password-confirm]} :params} :request :as context}]
                  (when-not (= password password-confirm)
                    {:password ["Passwords don't match"]
                     :password-confirm ["Passwords don't match"]}))}]})

(def signup
  (interceptor/interceptor
   {:leave (fn [context]
             (combine context
                      {:response
                       {:status 200
                        :data {:flash (get-in context [:request :flash])
                               :breadcrumbs (get-in context [:response ::link/breadcrumbs])}}}))}))

(def signup-post
  (interceptor/interceptor
   {:leave (fn [context]
             (let [values (get-in context [:request :values])
                   user {:name (:name values)
                         :password_encrypted (hashers/encrypt (:password values))}
                   id-or-errors
                   (try
                     (let [res (jdbc/insert! (:database context)
                                             :users
                                             (merge user
                                                    (let [now (.getTime (java.util.Date.))]
                                                      {:created_at now
                                                       :updated_at now})))]
                       (when-not (= 1 (count res))
                         (throw (Exception.)))
                       (:id (first (jdbc/query (:database context) ["SELECT id FROM users WHERE name = ?" (:name user)]))))
                     (catch Exception _
                       ;; assume name unique violation
                       {:errors {:name ["Name already exists"]}}
                       ))]
               (if-let [errors (:errors id-or-errors)]
                 (combine context
                          {:response
                           {:status 400
                            :data {:user {:values values
                                          :errors errors}}}})
                 (combine context
                          {:response
                           {:status 201
                            :headers {"Location" (link/link context ::user :params {:id id-or-errors})}
                            :flash {:message "User created"}}}))))}))

(def login-binding
  {:user
   [{:id :name
     :label "Name"
     :type :string
     :validator (fn [{{{:keys [name]} :params} :request :as context}]
                  (when (not (seq name))
                    {:name ["Name can't be empty"]}
))}
    {:id :password
     :label "Password"
     :type :password
     :validator (fn [{{{:keys [password]} :params} :request :as context}]
                  (when (not (seq password))
                    {:password ["Password can't be empty"]}
                    ))}]})

(def login
  (interceptor/interceptor
   {:leave (fn [context]
             (combine context
                      {:response
                       {:status 200
                        :data {:flash (get-in context [:request :flash])}}}))}))

(defn get-auth [db values]
  (let [{:keys [name password]} values]
    (when-let [user (first (jdbc/query db ["SELECT * FROM users WHERE name = ?" name]))]
      (when (hashers/check password (:password_encrypted user))
        (dissoc user :password_encrypted)))))

(def login-post
  (interceptor/interceptor
   {:leave (fn [context]
             (let [values (get-in context [:request :values])]
               (if-let [auth (get-auth (:database context) values)]
                 (combine context
                          {:response
                           {:status 303
                            :headers {"Location"
                                      (or (get-in context [:request :query-params :return-to])
                                          (link/link context ::user :params {:id (:id auth)}))}
                            :session {:auth {:id (:id auth)}}
                            :flash {:message "Login successful"}}})
                 (combine context
                          {:response
                           {:status 303
                            :headers {"Location" (get-in context [:self])}
                            :flash {:message "Login failed"}}}))))}))

(def logout-post
  (interceptor/interceptor
   {:leave (fn [context]
             (combine context
                      {:response
                       {:status 303
                        :headers {"Location" (or (get-in context [:request :query-params :return-to])
                                                 (link/link context ::home))}
                        :session {}
                        :flash {:message "Logout successful"}}}))}))

(defn get-user [db id]
  (when-let [user (first (jdbc/query db ["SELECT id, name, created_at, updated_at FROM users WHERE id = ?" id]))]
    (assoc user :snippets (jdbc/query db ["SELECT id FROM snippets WHERE owner = ?" (:id user)]))))

(def with-user-auth
  (interceptor/interceptor
   {:enter (fn [context]
             (if-let [user (when-let [id (get-in context [:request :session :auth :id])]
                             (get-user (:database context) id))]
               (combine context {:auth user})
               context))}))

(defn auth-resource [context]
  (let [{:keys [id] :as auth} (:auth context)]
    (-> auth
        (assoc :url (link/link context ::user :params {:id id})
               :logout (link/link context ::logout-post))
        (dissoc :snippets))))

(def with-login-data
  (interceptor/interceptor
   {:enter (fn [context]
             (cond-> context
                     (:auth context)
                     (combine
                      (let [user (auth-resource context)]
                        {:response {:data {:auth user}}}))))}))

(def with-user
  (interceptor/interceptor
   {:enter (fn [context]
             (let [id (get-in context [:request :path-params :id])]
               (if-let [user (get-user (:database context) id)]
                 (assoc context :user user)
                 (impl-interceptor/terminate
                  (combine context
                           {:response
                            {:status 404
                             :body "not found"}})))))}))

(def add-username-breadcrumb
  (interceptor/interceptor
   {:enter (fn [context]
             (let [user (:user context)
                   title (str "User " (:name user) "'s Page")]
               (link/add-breadcrumb context title ::user)))}))

(def user
  (interceptor/interceptor
   {:leave (fn [context]
             (combine context
                      {:response
                       {:status 200
                        :data {:flash (get-in context [:request :flash])
                               :breadcrumbs (get-in context [:response ::link/breadcrumbs])
                               :user (user-resource context (get-in context [:user]))}}}))}))

(defn get-snippets [db pagination]
  (let [{:keys [page limit]} pagination
        offset (* (dec page) limit)]
    {:results
     (->> (jdbc/query db ["SELECT s.*, u.name FROM snippets s JOIN users u ON s.owner = u.id"])
          ;; derby doesn't do LIMIT :(
          (drop offset)
          (take limit))
     :count (:count (first (jdbc/query db ["SELECT count(*) as count FROM snippets s"])))}))

(defn snippet-auth-is-owner [context snippet]
  (let [id (:id (:auth context))
        owner (:owner snippet)]
    (and id owner (= id owner))))

(def snippet-auth-owner-only
  (interceptor/interceptor
   {:enter (fn [context]
             (if (snippet-auth-is-owner context (:snippet context))
               context
               (impl-interceptor/terminate
                (combine context
                         {:response {:status 401
                                     :body "not allowed"}}))))}))

(defn snippet-resource [context data]
  (let [{:keys [id]} data]
    (-> data
        (dissoc :id)
        (assoc :url (link/link context ::snippet :params {:id id}))
        (assoc :user (link/link context ::user :params {:id (:owner data)}))
        (cond->
         (snippet-auth-is-owner context data)
         (assoc :edit (link/link context ::snippet#edit :params {:id id})
                :delete (link/link context ::snippet#delete :params {:id id}))))))

(defmethod form/render-binding :suffix-text-box
  [def value errors]
  (-> (form/render-binding (dissoc def :widget) value errors)
      (update-in [3] conj [:div.input-group-addon (:widget/suffix def)])))

(defmethod form/render-binding :multi-checkbox
  [def value errors]
    (let [{:keys [id label type]} def
        field-name (or label (name id))]
    [:div
     {:class (str "form-group"
                  (when errors
                    " has-error"))}
     [:label.control-label
      {:for (name id)}
      field-name]
     [:div
      (for [[key val] (:widget/options def)]
         (let [id (str (name id) "[" (name key) "]")]
           [:div.checkbox
            [:label {:for id}
             [:input
              (cond->
               {:type "checkbox"
                :name id
                :id id}
               (boolean (get value key))
               (assoc :checked "checked") )]
             val]]))]
     (when errors
       [:div.errors
        (for [error errors]
          [:span.help-block error])])]))

(def snippet-binding
  {:snippet
   [{:id :title
     :label "Title"
     :type :string
     :widget :suffix-text-box
     :widget/suffix "suffix"
     :validator (fn [{{{:keys [title]} :params} :request :as context}]
                  (when (not (seq title))
                    {:title ["Title can't be empty"]}
))}
    {:id :quality
     :label "Quality"
     :widget :multi-checkbox
     :widget/options {:good "Good"
                      :fast "Fast"
                      :cheap "Cheap"}
     :validator (fn [{{{:keys [quality]} :params} :request :as context}]
                  (when (= 3 (count quality))
                    {:quality ["Sorry can only pick two"]}))}]})

(def snippets
  (interceptor/interceptor
   {:leave (fn [context]
             (combine context
                      {:response
                       {:status 200
                        :data {:flash (get-in context [:request :flash])
                               :breadcrumbs (get-in context [:response ::link/breadcrumbs])
                               :new (link/link context ::snippet#new)
                               :pagination (pagination/pagination-params context)
                               :snippets (let [pagination (pagination/pagination-params context)
                                               {:keys [results count]} (get-snippets (:database context) pagination)]
                                           (merge
                                            {:results (map (partial snippet-resource context) results)
                                             :count count}
                                            (pagination/links context ::snippets pagination count)))}}}))}))

(def parse-path-param-id
  (interceptor/interceptor
   {:enter (fn [context]
             (update-in context [:request :path-params :id]
                        #(try (Long/parseLong %)
                              (catch Exception _ nil))))}))

(defn normalize-snippet-values [values]
  (-> values
      (assoc :quality_fast (boolean (get-in values [:quality :fast])))
      (assoc :quality_good (boolean (get-in values [:quality :good])))
      (assoc :quality_cheap (boolean (get-in values [:quality :cheap])))
      (dissoc :quality)))

(defn get-snippet [db id]
  (let [snippet (first (jdbc/query db ["SELECT s.*, u.name AS owner_name FROM snippets s JOIN users u ON s.owner = u.id WHERE s.id = ?" id]))]
    (-> snippet
        (cond->
         (:quality_good snippet)
         (update-in [:quality] assoc :good true)
         (:quality_fast snippet)
         (update-in [:quality] assoc :fast true)
         (:quality_cheap snippet)
         (update-in [:quality] assoc :cheap true))
        (dissoc :quality_good :quality_fast :quality_cheap))))

(def with-snippet
  (interceptor/interceptor
   {:enter (fn [context]
             (let [id (get-in context [:request :path-params :id])]
               (if-let [snippet (get-snippet (:database context) id)]
                 (combine context {:snippet (snippet-resource context snippet)})
                 (impl-interceptor/terminate
                  (combine context
                           {:response {:status 404
                                       :body "not found"}})))))}))

(def snippet
  (interceptor/interceptor
   {:leave (fn [context]
             (combine context
                      {:response
                       {:status 200
                        :data {:flash (get-in context [:request :flash])
                               :breadcrumbs (get-in context [:response ::link/breadcrumbs])
                               :snippet (get-in context [:snippet])}}}))}))

(def require-auth
  (interceptor/interceptor
   {:enter (fn [context]
             (if (:auth context)
               context
               (impl-interceptor/terminate
                (combine context
                         {:response {:status 303
                                     :headers {"Location" (link/link context ::login
                                                                     :params {:return-to (get-in context [:self])})}
                                     :flash {:message "Authentication required"}}}))))}))

(def snippet#new
  (interceptor/interceptor
   {:enter (fn [context]
             (let [name (get-in context [:request :path-params :name])]
               (combine context
                        {:response {:status 200
                                    :data {:breadcrumbs (get-in context [:response ::link/breadcrumbs])
                                           :self (get-in context [:self])}}})))}))

(def snippet#new-post
  (interceptor/interceptor
   {:leave (fn [context]
             (let [values (get-in context [:request :values])
                   values (normalize-snippet-values values)
                   id-or-errors
                   (try
                     (let [res (jdbc/insert! (:database context)
                                             :snippets
                                             (merge values
                                                    (let [now (.getTime (java.util.Date.))]
                                                      {:created_at now
                                                       :updated_at now
                                                       :owner (:id (:auth context))})))]
                       (when-not (= 1 (count res))
                         (throw (Exception.)))
                       (:id (first (jdbc/query (:database context) ["SELECT id FROM snippets WHERE title = ?" (:title values)]))))
                     (catch Exception e
                       ;; assume title unique violation
                       {:errors {:title ["Title already exists"]}}
                       ))]
               (if-let [errors (:errors id-or-errors)]
                 (combine context
                          {:response
                           {:status 400
                            :data {:snippet {:values values
                                             :errors errors}}}})
                 (combine context
                          {:response
                           {:status 201
                            :headers {"Location" (link/link context ::snippet :params {:id id-or-errors})}
                            :flash {:message "Snippet created"}}}))))}))

(def snippet#edit-post
  (interceptor/interceptor
   {:leave (fn [context]
             (let [id (get-in context [:request :path-params :id])
                   values (get-in context [:request :values])
                   values (normalize-snippet-values values)
                   res (try
                         (jdbc/update! (:database context)
                                       :snippets
                                       (merge values
                                              {:updated_at (.getTime (java.util.Date.))})
                                       ["id = ?" id])
                         nil
                         (catch Exception _
                           ;; assume title unique violation
                           {:errors {:title ["Title already exists"]}}
                           ))]
               (if-let [errors (:errors res)]
                 (combine context
                          {:response
                           {:status 400
                            :data {:snippet {:values values
                                             :errors errors}}}})
                 (combine context
                          {:response
                           {:status 201
                            :headers {"Location" (link/link context ::snippet :params {:id id})}
                            :flash {:message "Snippet updated"}}}))))}))

(def snippet#delete-post
  (interceptor/interceptor
   {:leave (fn [context]
             (let [id (get-in context [:request :path-params :id])
                   res (jdbc/delete! (:database context)
                                     :snippets
                                     ["id = ?" id])]
               (combine context
                        {:response
                         {:status 201
                          :headers {"Location" (link/link context ::snippets)}
                          :flash {:message "Snippet deleted"}}})))}))

;; (io.pedestal.http.route/print-routes routes)
(defroutes
  routes
  [[["/"
     ^:interceptors [edn-wrap/wrap-edn
                     with-html
                     with-user-auth
                     with-login-data
                     (link/breadcrumb "Root" ::home)]
     {:get home}
     ["/signup"
      ^:interceptors [(link/breadcrumb "User Signup" ::signup)
                      (binding/with-binding signup-binding)]
      {:get signup
       :post signup-post}]
     ["/login"
      ^:interceptors [(link/breadcrumb "Login" ::login)
                      with-login-html
                      (binding/with-binding login-binding)]
      {:get login
       :post login-post}]
     ["/logout" {:post logout-post}]
     ["/users"
      ^:interceptors [(link/breadcrumb "Users" ::users)]
      {:get [::users users]}
      ["/:id"
       ^:interceptors [parse-path-param-id
                       with-user
                       add-username-breadcrumb]
       {:get user}]]
     ["/snippets"
      ^:interceptors [(link/breadcrumb "Snippet List" ::snippets)]
      {:get snippets}
      ["/new"
       ^:interceptors [(link/breadcrumb "Snippet Create" ::snippet#new)
                       require-auth
                       (binding/with-binding snippet-binding)]
       {:get [::snippet#new snippet#new]
        :post [::snippet#new-post snippet#new-post]}]
      ["/:id"
       ^:interceptors [parse-path-param-id
                       (link/breadcrumb "Snippet Instance" ::snippet)
                       with-snippet]
       {:get snippet}

       ["/edit"
        ^:interceptors [(link/breadcrumb "Snippet Edit" ::snippet#edit)
                        snippet-auth-owner-only
                        (binding/with-binding snippet-binding)]
        {:get [::snippet#edit snippet]
         :post [::snippet#edit-post snippet#edit-post]}]
       ["/delete"
        ^:interceptors [(link/breadcrumb "Snippet Delete" ::snippet#delete)
                        snippet-auth-owner-only
                        (binding/with-binding nil)]
        {:get [::snippet#delete snippet]
         :post [::snippet#delete-post snippet#delete-post]}]]]]]])

(def bootstrap-webjars-resource-path "META-INF/resources/webjars/bootstrap/3.3.4")
(def jquery-webjars-resource-path "META-INF/resources/webjars/jquery/1.11.1")

(def service
  {:env :prod
   ::http/router :linear-search ;; we have snippets/new and snippets/:id
   ::http/routes routes

   ::http/resource-path "/public"

   ::http/default-interceptors [(middlewares/resource bootstrap-webjars-resource-path)
                                (middlewares/resource jquery-webjars-resource-path)]

   ::http/type :jetty
   })
