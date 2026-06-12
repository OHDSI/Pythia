(ns pythia.tools.search-phenotypes-test
  (:require [cljs.test :refer [deftest is async testing]]
            [pythia.http :as http]
            [pythia.phenotype-sources :as ps]
            [pythia.tools.search-phenotypes :as sp]))

(defn- mock-get-json!
  "Stub http/get-json to dispatch on URL substring -> {:status :body}."
  [route-fn]
  (let [orig http/get-json]
    (set! http/get-json
          (fn [url opts]
            (js/Promise.resolve (route-fn url opts))))
    (fn [] (set! http/get-json orig))))

(def ^:private fake-index
  [{:id 10 :name "Type 2 diabetes mellitus" :status "Accepted"
    :description "T2DM cohort" :tags ["#endocrine"] :hashtag "#endocrine"
    :entry-domains ["ConditionOccurrence"] :n-inclusion-rules 2
    :concept-sets [] :body-path "cohorts/10.json"}])

(defn- with-fake-index! []
  (reset! @#'ps/index-cache fake-index)
  (fn [] (ps/reset-cache!)))

(deftest empty-query-short-circuits
  (async done
    (-> (js/Promise.resolve ((:run sp/tool) {:query ""} {}))
        (.then (fn [out]
                 (is (= [] (:results out)))
                 (is (= "empty query" (:note out)))
                 (done))))))

(deftest merges-four-sources
  (async done
    (let [restore-idx (with-fake-index!)
          restore
          (mock-get-json!
           (fn [url _]
             (cond
               (re-find #"healthdatagateway" url)
               {:status 200
                :body {:data [{:phenotype_id "PH6" :name "Diabetes codelist"
                               :publications [{:details "HDR UK ref"}]}]}}

               (re-find #"phekb" url)
               {:status 200
                :body [{:title "Type 2 Diabetes" :description "T2DM algorithm"
                        :url "https://phekb.org/phenotype/123"}]}

               (re-find #"forums.ohdsi.org" url)
               {:status 200
                :body {:topics [{:id 7 :title "Diabetes cohort question" :slug "diabetes-q"}]
                       :posts [{:topic_id 7 :blurb "discussion blurb"}]}}

               :else {:status 404 :body nil})))]
      (-> ((:run sp/tool) {:query "diabetes"} {})
          (.then (fn [out]
                   (let [by-src (group-by :source (:results out))]
                     (is (= #{"ohdsi-library" "hdruk" "phekb" "forums"} (set (keys by-src))))
                     (is (= ["ohdsi-library" "hdruk" "phekb" "forums"] (:sources out)))
                     ;; OHDSI Library hit from the injected index
                     (let [o (first (get by-src "ohdsi-library"))]
                       (is (= 10 (:id o)))
                       (is (= :omop-cohort (:kind o)))
                       (is (map? (:circe-summary o))))
                     ;; HDR UK hit
                     (let [h (first (get by-src "hdruk"))]
                       (is (= "PH6" (:id h)))
                       (is (= :clinical-codelist (:kind h))))
                     ;; community sources still present
                     (is (= "Type 2 Diabetes" (:name (first (get by-src "phekb")))))
                     (is (= "Diabetes cohort question" (:name (first (get by-src "forums"))))))
                   (restore)
                   (restore-idx)
                   (done)))))))

(deftest tolerates-source-failure
  (async done
    (let [restore-idx (with-fake-index!)
          restore (mock-get-json! (fn [_ _] {:status 500 :body nil}))]
      ;; live sources all fail; OHDSI library (no query match) is empty too here
      (-> ((:run sp/tool) {:query "leukemia"} {})
          (.then (fn [out]
                   (is (= [] (:results out)))
                   (restore)
                   (restore-idx)
                   (done)))))))
