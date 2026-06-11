(ns pythia.tools.external-tools-test
  (:require [cljs.test :refer [deftest is async testing]]
            [pythia.http :as http]
            [pythia.tools.search-ohdsi-studies :as studies]
            [pythia.tools.web-search :as ws]))

;; ---------- search_ohdsi_studies ----------

(deftest studies-searches-github-and-shapes
  (async done
    (let [recorded (atom nil)
          orig http/get-json]
      (set! http/get-json
            (fn [url opts]
              (reset! recorded {:url url :opts opts})
              (js/Promise.resolve
               {:status 200
                :body {:items [{:full_name "ohdsi-studies/Covid19"
                                :name "Covid19"
                                :description "vaccine safety"
                                :html_url "https://github.com/ohdsi-studies/Covid19"
                                :stargazers_count 12
                                :topics ["covid"]
                                :updated_at "2024-01-01"}]}})))
      (-> ((:run studies/tool) {:query "covid vaccine"} {})
          (.then (fn [out]
                   (let [{:keys [url opts]} @recorded]
                     (is (= "https://api.github.com/search/repositories" url))
                     (is (re-find #"org:ohdsi-studies" (get-in opts [:query :q]))))
                   (is (= 1 (count (:results out))))
                   (let [r (first (:results out))]
                     (is (= "ohdsi-studies" (:source r)))
                     (is (= "ohdsi-studies/Covid19" (:name r)))
                     (is (= "Covid19" (:title r)))
                     (is (= 12 (:stars r)))
                     (is (= ["covid"] (:topics r))))
                   (set! http/get-json orig)
                   (done)))))))

(deftest studies-empty-query
  (async done
    (-> (js/Promise.resolve ((:run studies/tool) {:query ""} {}))
        (.then (fn [out]
                 (is (= [] (:results out)))
                 (is (= "empty query" (:note out)))
                 (done))))))

(deftest studies-403-rate-limit
  (async done
    (let [orig http/get-json]
      (set! http/get-json (fn [_ _] (js/Promise.resolve {:status 403 :body {}})))
      (-> ((:run studies/tool) {:query "x"} {})
          (.then (fn [out]
                   (is (= [] (:results out)))
                   (is (string? (:note out)))
                   (set! http/get-json orig)
                   (done)))))))

;; ---------- web_search ----------

(def ^:private ddg-html
  (str "<table>"
       "<a href=\"https://example.com/a\" class=\"result-link\">First Result</a>"
       "<td class=\"result-snippet\">Snippet <b>one</b> here</td>"
       "<a href=\"https://example.com/b\" class=\"result-link\">Second Result</a>"
       "<td class=\"result-snippet\">Snippet two</td>"
       "</table>"))

(deftest web-search-parse-html
  (let [out (ws/parse-html ddg-html 5)]
    (is (= 2 (count out)))
    (is (= "https://example.com/a" (:url (first out))))
    (is (= "First Result" (:title (first out))))
    (is (= "Snippet one here" (:snippet (first out))) "tags stripped")
    (is (= "Snippet two" (:snippet (second out))))))

(deftest web-search-parse-html-respects-n
  (is (= 1 (count (ws/parse-html ddg-html 1)))))

(deftest web-search-posts-form-and-returns-results
  (async done
    (let [recorded (atom nil)
          orig http/post-form]
      (set! http/post-form
            (fn [url opts]
              (reset! recorded {:url url :opts opts})
              (js/Promise.resolve {:status 200 :text ddg-html})))
      (-> ((:run ws/tool) {:query "type 2 diabetes" :num_results 2} {})
          (.then (fn [out]
                   (let [{:keys [url opts]} @recorded]
                     (is (= "https://lite.duckduckgo.com/lite/" url))
                     (is (= "type 2 diabetes" (get-in opts [:form :q]))))
                   (is (= 2 (count (:results out))))
                   (is (= "https://example.com/a" (:url (first (:results out)))))
                   (set! http/post-form orig)
                   (done)))))))

(deftest web-search-blank-query
  (async done
    (-> (js/Promise.resolve ((:run ws/tool) {:query "  "} {}))
        (.then (fn [out]
                 (is (= "query is required" (:error out)))
                 (done))))))

(deftest web-search-non-200
  (async done
    (let [orig http/post-form]
      (set! http/post-form (fn [_ _] (js/Promise.resolve {:status 503 :text ""})))
      (-> ((:run ws/tool) {:query "x"} {})
          (.then (fn [out]
                   (is (re-find #"HTTP 503" (:error out)))
                   (set! http/post-form orig)
                   (done)))))))
