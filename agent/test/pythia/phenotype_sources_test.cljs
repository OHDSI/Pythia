(ns pythia.phenotype-sources-test
  (:require [cljs.test :refer [deftest is async testing]]
            [pythia.http :as http]
            [pythia.phenotype-sources :as ps]))

(def ^:private fake-index
  [{:id 10 :name "Type 2 diabetes mellitus" :name-long "T2DM" :status "Accepted"
    :description "T2DM cohort" :tags ["#endocrine"] :hashtag "#endocrine"
    :entry-domains ["ConditionOccurrence"] :n-primary-criteria 1 :n-inclusion-rules 2
    :n-concept-sets 1 :exit-strategy "fixed_duration" :censor-criteria? false
    :primary-event-limit "First" :expression-limit "First"
    :concept-sets [{:id 1 :name "T2DM" :n-items 5}] :body-path "cohorts/10.json"}
   {:id 11 :name "Type 2 diabetes withdrawn" :name-long "" :status "Withdrawn"
    :description "old diabetes def" :hashtag "" :entry-domains ["ConditionOccurrence"]
    :n-primary-criteria 1 :n-inclusion-rules 0 :n-concept-sets 0
    :concept-sets [] :body-path "cohorts/11.json"}
   {:id 12 :name "Hypertension" :status "Accepted" :description "HTN"
    :hashtag "" :entry-domains ["ConditionOccurrence"] :concept-sets []
    :body-path "cohorts/12.json"}])

;; --- Task 1: OHDSI Library ---

(deftest ohdsi-library-ranks-and-shapes
  (let [hits (ps/rank-ohdsi-library fake-index "type 2 diabetes" 5)
        top  (first hits)]
    ;; only the two "type 2 diabetes" entries match; hypertension filtered out
    (is (= 2 (count hits)))
    ;; Accepted ranks ahead of Withdrawn (JVM status-rank sort)
    (is (= 10 (:id top)))
    (is (= "ohdsi-library" (:source top)))
    (is (= :omop-cohort (:kind top)))
    (is (= "Accepted" (:status top)))
    (is (= "Type 2 diabetes mellitus" (:name top)))
    (is (= ["#endocrine"] (:tags top)))
    ;; circe-summary is a map (JVM shape) with entry-domains + criteria counts
    (is (map? (:circe-summary top)))
    (is (= ["ConditionOccurrence"] (:entry-domains (:circe-summary top))))
    (is (= 2 (:n-inclusion-rules (:circe-summary top))))
    (is (= [{:id 1 :name "T2DM" :n-items 5}] (:concept-sets (:circe-summary top))))
    ;; GitHub human URL built from id (main/inst/cohorts), per JVM
    (is (re-find #"github.com/OHDSI/PhenotypeLibrary/blob/main/inst/cohorts/10.json" (:url top)))
    ;; second hit is the withdrawn one
    (is (= 11 (:id (second hits))))))

(deftest ohdsi-library-no-match-empty
  (is (= [] (ps/rank-ohdsi-library fake-index "leukemia" 5))))

;; --- Task 2: HDR UK ---

(defn- mock-get-json! [route-fn]
  (let [orig http/get-json]
    (set! http/get-json (fn [url opts] (js/Promise.resolve (route-fn url opts))))
    (fn [] (set! http/get-json orig))))

(deftest hdruk-maps-search-results
  (async done
    (let [restore (mock-get-json!
                   (fn [url opts]
                     (is (re-find #"/api/v1/phenotypes/" url))
                     (is (= "cardiovascular" (get-in opts [:query :search])))
                     {:status 200
                      :body {:data [{:phenotype_id "PH6" :name "Cardiovascular Disease"
                                     :publications [{:details "Parisi et al 2015"}]}]}}))]
      (-> (ps/search-hdruk "cardiovascular" 5)
          (.then (fn [items]
                   (let [i (first items)]
                     (is (= "hdruk" (:source i)))
                     (is (= "PH6" (:id i)))
                     (is (= "Cardiovascular Disease" (:name i)))
                     (is (= :clinical-codelist (:kind i)))
                     (is (= "Parisi et al 2015" (:description i)))
                     (is (re-find #"phenotypes.healthdatagateway.org/phenotypes/PH6" (:url i))))
                   (restore)
                   (done)))))))

(deftest hdruk-fail-soft
  (async done
    (let [restore (mock-get-json! (fn [_ _] {:status 500 :body nil}))]
      (-> (ps/search-hdruk "anything" 5)
          (.then (fn [items]
                   (is (= [] items))
                   (restore)
                   (done)))))))

;; --- Task 3: PheKB + forums ---

(deftest phekb-shapes-community
  (async done
    (let [restore (mock-get-json!
                   (fn [url _]
                     (is (re-find #"phekb.org" url))
                     {:status 200
                      :body [{:title "Type 2 Diabetes" :description "T2DM algorithm"
                              :url "https://phekb.org/phenotype/123"}
                             {:title "Unrelated" :description "asthma"}]}))]
      (-> (ps/search-phekb "diabetes" 5)
          (.then (fn [items]
                   (is (= 1 (count items)))
                   (let [i (first items)]
                     (is (= "phekb" (:source i)))
                     (is (= :community (:kind i)))
                     (is (= "Type 2 Diabetes" (:name i)))
                     (is (= "https://phekb.org/phenotype/123" (:url i))))
                   (restore)
                   (done)))))))

(deftest forums-shapes-community
  (async done
    (let [restore (mock-get-json!
                   (fn [url _]
                     (is (re-find #"forums.ohdsi.org" url))
                     {:status 200
                      :body {:topics [{:id 7 :title "Diabetes cohort question" :slug "diabetes-q"}]
                             :posts [{:topic_id 7 :blurb "discussion blurb"}]}}))]
      (-> (ps/search-forums "diabetes" 5)
          (.then (fn [items]
                   (let [i (first items)]
                     (is (= "forums" (:source i)))
                     (is (= :community (:kind i)))
                     (is (= "Diabetes cohort question" (:name i)))
                     (is (= "discussion blurb" (:description i)))
                     (is (= "https://forums.ohdsi.org/t/diabetes-q/7" (:url i))))
                   (restore)
                   (done)))))))

;; --- Task 4: merge-rank ---

(deftest merge-caps-and-tags
  (let [merged (ps/merge-rank
                [{:source "ohdsi-library" :kind :omop-cohort :name "a" :score 5}
                 {:source "hdruk" :kind :clinical-codelist :name "b" :score 3}
                 {:source "phekb" :kind :community :name "c" :score 1}]
                10)]
    (is (= 3 (count merged)))
    (is (= #{"ohdsi-library" "hdruk" "phekb"} (set (map :source merged))))
    (is (= "a" (:name (first merged))))))

(deftest merge-caps-top-n
  (let [merged (ps/merge-rank
                [{:source "a" :name "a" :score 1}
                 {:source "b" :name "b" :score 5}
                 {:source "c" :name "c" :score 3}]
                2)]
    (is (= 2 (count merged)))
    (is (= ["b" "c"] (map :name merged)))))
