(ns w3a.example.html
  (:require [clojure.test :refer [deftest is]]
            [io.pedestal.log :as log]
            [net.cgrand.enlive-html :as html]
            [net.thegeez.w3a.test :as w3a-test]
            [kerodon.core :as k]
            [kerodon.test :as t]
            [peridot.core :as p]
            [user :as user]))

(defn ring-handler []
  (w3a-test/system->ring-handler (user/dev-system user/dev-config)))

;; broken in kerodon for text only links when links with nested html
;; on a page
(defn follow [state link]
  (k/follow state (if (string? link)
                    (html/pred #(= (first (:content %)) link))
                    link)))

(deftest new-snippet
  (-> (k/session (ring-handler))
      (p/header "Accept" "text/html")
      (k/visit "/")
      (follow "http://testhost:-1/snippets")
      (k/within [:#content]
                (t/has (t/link? "http://testhost:-1/snippets/new"))
                (t/has (t/link? "http://testhost:-1/snippets/1"))
                (t/has (t/link? "http://testhost:-1/snippets/2")))
      (follow "http://testhost:-1/snippets/new")
      (k/follow-redirect)
      (k/within [:div#flash]
                (t/has (t/text? "Authentication required")))
      (k/within [[:form (html/attr= :action "http://testhost:-1/login?return-to=http://testhost:-1/snippets/new")]]
                ((fn [res]
                   (log/info :res res)
                   res))
                (k/fill-in "Name" "amy")
                (k/fill-in "Password" "amy")
                (k/press "Submit"))
      (k/follow-redirect)
      (k/within [:ul.breadcrumb]
                (k/within [[:li html/first-of-type]]
                          (t/has (t/link? "Root" "http://testhost:-1/")))
                (k/within [[:li (html/nth-of-type 2)]]
                          (t/has (t/link? "Snippet List" "http://testhost:-1/snippets")))
                (k/within [[:li html/last-of-type]]
                          (t/has (t/link? "Snippet Create" "http://testhost:-1/snippets/new"))))
      (k/press "Submit")
      (k/within [[:form (html/attr= :action "http://testhost:-1/snippets/new")]]
                (k/within [:span.help-block]
                          (t/has (t/text? "Title can't be empty"))))
      (k/fill-in "Title" "New snippet title")
      (k/check "Fast")
      (k/check "Good")
      (k/check "Cheap")
      (k/press "Submit")
      (k/within [[:div.form-group html/last-of-type]]
                (k/within [:span.help-block]
                          (t/has (t/text? "Sorry can only pick two"))))
      (k/uncheck "Good")
      (k/press "Submit")
      ((fn [state]
         (is (= (get-in state [:response :headers "Location"])
                "http://testhost:-1/snippets/102"))
         state))
      (k/follow-redirect)
      (k/within [:#flash]
                (t/has (t/text? "Snippet created")))
      (t/has (t/some-text? ":title \"New snippet title\""))
      (t/has (t/some-text? ":quality {:cheap true, :fast true}"))
      (k/within [:ul.breadcrumb [:li html/last-of-type]]
                (t/has (t/link? "Snippet Instance" "http://testhost:-1/snippets/102")))
      (follow "http://testhost:-1/snippets/102/edit")
      (k/fill-in "Title" "Edited snippet title")
      (k/press "Submit")
      (k/follow-redirect)
      (k/within [:#flash]
                (t/has (t/text? "Snippet updated")))
      (t/has (t/some-text? ":title \"Edited snippet title\""))
      (follow "http://testhost:-1/snippets/102/delete")
      (k/press "Submit")
      (k/follow-redirect)
      (k/within [:#flash]
                (t/has (t/text? "Snippet deleted")))
      ))
