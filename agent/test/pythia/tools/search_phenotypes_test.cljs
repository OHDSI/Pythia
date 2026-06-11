(ns pythia.tools.search-phenotypes-test
  (:require [cljs.test :refer [deftest is async testing]]
            [pythia.http :as http]
            [pythia.tools.search-phenotypes :as sp]))

(defn- mock-get-json!
  "Stub http/get-json to dispatch on URL substring -> {:status :body}."
  [route-fn]
  (let [orig http/get-json]
    (set! http/get-json
          (fn [url opts]
            (js/Promise.resolve (route-fn url opts))))
    (fn [] (set! http/get-json orig))))

(deftest empty-query-short-circuits
  (async done
    (-> (js/Promise.resolve ((:run sp/tool) {:query ""} {}))
        (.then (fn [out]
                 (is (= [] (:results out)))
                 (is (= "empty query" (:note out)))
                 (done))))))

(deftest merges-phekb-and-discourse
  (async done
    (let [restore
          (mock-get-json!
           (fn [url _]
             (cond
               (re-find #"phekb" url)
               {:status 200
                :body [{:title "Type 2 Diabetes" :description "T2DM algorithm"
                        :url "https://phekb.org/phenotype/123"}
                       {:title "Unrelated" :description "asthma"}]}

               (re-find #"forums.ohdsi.org" url)
               {:status 200
                :body {:topics [{:id 7 :title "Diabetes cohort question" :slug "diabetes-q"}]
                       :posts [{:topic_id 7 :blurb "discussion blurb"}]}}

               :else {:status 404 :body nil})))]
      (-> ((:run sp/tool) {:query "diabetes"} {})
          (.then (fn [out]
                   (let [by-src (group-by :source (:results out))]
                     ;; PheKB: only the matching row survives
                     (is (= 1 (count (get by-src "PheKB"))))
                     (is (= "Type 2 Diabetes" (:title (first (get by-src "PheKB")))))
                     (is (= "https://phekb.org/phenotype/123" (:url (first (get by-src "PheKB")))))
                     ;; Discourse: topic joined with its post blurb
                     (let [d (first (get by-src "OHDSI Forums"))]
                       (is (= "Diabetes cohort question" (:title d)))
                       (is (= "discussion blurb" (:description d)))
                       (is (= "https://forums.ohdsi.org/t/diabetes-q/7" (:url d)))))
                   (restore)
                   (done)))))))

(deftest tolerates-source-failure
  (async done
    (let [restore (mock-get-json! (fn [_ _] {:status 500 :body nil}))]
      (-> ((:run sp/tool) {:query "diabetes"} {})
          (.then (fn [out]
                   (is (= [] (:results out)))
                   (restore)
                   (done)))))))
