(ns pythia.tools.results-test
  "Tests for the tools that read generated results: characterization and
   incidence rate (get_analysis_results), cohort samples and patient profiles."
  (:require [cljs.test :refer [deftest is async]]
            [pythia.webapi :as webapi]
            [pythia.tools.get-analysis-results :as gar]
            [pythia.tools.get-cohort-samples :as gcs]
            [pythia.tools.get-person-profile :as gpp]))

(defn- with-routes!
  "Stub webapi/request with a {path body} map (a fn value is called with opts).
   Returns [recorded restore]."
  [routes]
  (let [recorded (atom [])
        orig webapi/request]
    (set! webapi/request
          (fn [_ path opts]
            (swap! recorded conj {:path path :opts opts})
            (let [v (get routes path)]
              (js/Promise.resolve (if (fn? v) (v opts) v)))))
    [recorded (fn [] (set! webapi/request orig))]))

(def ^:private sources [{:sourceId 2 :sourceKey "SYNPUF"}])

;; ---------- characterization ----------

(def ^:private cc-rows
  [{:cohortId 20 :analysisName "DemographicsGender" :resultType "PREVALENCE" :strataId 0
    :covariateId 8507001 :covariateName "gender = MALE" :conceptName "MALE" :count 30 :avg 0.3}
   {:cohortId 20 :analysisName "DemographicsGender" :resultType "PREVALENCE" :strataId 0
    :covariateId 8532001 :covariateName "gender = FEMALE" :conceptName "FEMALE" :count 70 :avg 0.7}
   {:cohortId 20 :analysisName "CharlsonIndex" :resultType "DISTRIBUTION" :strataId 0
    :covariateId 1901 :covariateName "Charlson index" :conceptName "No matching concept"
    :count 100 :avg 3.2 :median 3 :p25 1 :p75 5 :min 0 :max 12}
   {:cohortId 20 :analysisName "ConditionGroupEraLongTerm" :resultType "PREVALENCE" :strataId 0
    :covariateId 201826 :covariateName "condition: Type 2 diabetes" :conceptName "Type 2 diabetes" :count 90 :avg 0.9}
   {:cohortId 20 :analysisName "ConditionGroupEraLongTerm" :resultType "PREVALENCE" :strataId 0
    :covariateId 316866 :covariateName "condition: Hypertension" :conceptName "Hypertension" :count 40 :avg 0.4}
   {:cohortId 16 :analysisName "ConditionGroupEraLongTerm" :resultType "PREVALENCE" :strataId 0
    :covariateId 201826 :covariateName "condition: Type 2 diabetes" :conceptName "Type 2 diabetes" :count 25 :avg 0.5}
   {:cohortId 16 :analysisName "ConditionGroupEraLongTerm" :resultType "PREVALENCE" :strataId 1
    :strataName "Men" :covariateId 201826 :covariateName "condition: Type 2 diabetes" :count 10 :avg 0.5}])

(def ^:private cc-routes
  {"/cohort-characterization/1/generation" [{:id 68 :status "FAILED" :sourceKey "SYNPUF"}
                                            {:id 69 :status "COMPLETED" :sourceKey "SYNPUF"}]
   "/cohort-characterization/generation/69/result" cc-rows
   "/cohortdefinition" [{:id 20 :name "Glipizide tutorial"} {:id 16 :name "Glipizide study"}]
   "/source/sources" sources
   "/cohortdefinition/20/info" [{:id {:sourceId 2} :status "COMPLETE" :personCount 100}]
   "/cohortdefinition/16/info" [{:id {:sourceId 2} :status "COMPLETE" :personCount 50}]})

(deftest characterization-reports-features-per-cohort
  (async done
    (let [[_ restore] (with-routes! cc-routes)]
      (-> ((:run gar/tool) {:analysisType "characterization" :analysisId 1} {:source-key "SYNPUF"})
          (.then (fn [out]
                   (let [[c20 c16] (get-in out [:summary :cohorts])]
                     (is (= "SYNPUF" (:sourceKey out)))
                     (is (= [20 16] [(:cohortId c20) (:cohortId c16)]))
                     (is (= ["Glipizide tutorial" 100] [(:name c20) (:persons c20)]))
                     (is (= [{:covariate "MALE" :count 30 :pct 30} {:covariate "FEMALE" :count 70 :pct 70}]
                            (get-in c20 [:analyses "DemographicsGender"])))
                     (is (= 3 (:median (first (get-in c20 [:analyses "CharlsonIndex"])))))
                     (is (= ["Type 2 diabetes" "Hypertension"]
                            (mapv :covariate (get-in c20 [:analyses "ConditionGroupEraLongTerm"]))))
                     (is (= 50 (:pct (first (get-in c16 [:analyses "ConditionGroupEraLongTerm"])))))
                     (is (= ["Men"] (get-in out [:summary :strata-not-shown])))
                     (let [diffs (get-in out [:summary :largest-differences :covariates])
                           t2dm (some #(when (= "Type 2 diabetes" (:covariate %)) %) diffs)]
                       ;; ordered by |std-diff|; absent from a cohort counts as 0%
                       (is (= (sort-by - (map #(js/Math.abs (:std-diff %)) diffs))
                              (map #(js/Math.abs (:std-diff %)) diffs)))
                       (is (= [90 50] [(:pct-first t2dm) (:pct-second t2dm)]))
                       (is (= 0.97 (:std-diff t2dm)))))
                   (restore) (done)))))))

(deftest characterization-search-and-cohort-filter
  (async done
    (let [[_ restore] (with-routes! cc-routes)]
      (-> ((:run gar/tool) {:analysisType "characterization" :analysisId 1 :cohortId 20 :search "hyper"}
                           {:source-key "SYNPUF"})
          (.then (fn [out]
                   (let [cohorts (get-in out [:summary :cohorts])]
                     (is (= [20] (mapv :cohortId cohorts)))
                     (is (= {"ConditionGroupEraLongTerm" [{:covariate "Hypertension" :count 40 :pct 40}]}
                            (:analyses (first cohorts))))
                     (is (nil? (get-in out [:summary :largest-differences]))))
                   (restore) (done)))))))

(deftest characterization-without-completed-run
  (async done
    (let [[_ restore] (with-routes! {"/cohort-characterization/1/generation" [{:id 68 :status "FAILED"}]})]
      (-> ((:run gar/tool) {:analysisType "characterization" :analysisId 1} {:source-key "SYNPUF"})
          (.then (fn [out]
                   (is (re-find #"No completed generation.*FAILED" (:error out)))
                   (restore) (done)))))))

;; ---------- incidence rate ----------

(deftest incidence-rate-reads-every-pair-from-the-expression
  (async done
    (let [[recorded restore]
          (with-routes!
           {"/ir/2" {:id 2 :expression (js/JSON.stringify
                                        (clj->js {:targetIds [20 24] :outcomeIds [25]
                                                  :timeAtRisk {:start {:DateField "StartDate" :Offset 0}}}))}
            "/ir/2/info" [{:executionInfo {:id {:analysisId 2 :sourceId 2} :status "COMPLETE"}}]
            "/source/sources" sources
            "/cohortdefinition" [{:id 20 :name "Glipizide"} {:id 24 :name "Metformin"} {:id 25 :name "Hypoglycemia"}]
            "/ir/2/report/SYNPUF" (fn [{:keys [query]}]
                                    {:summary (if (= 24 (:targetId query))
                                                {:totalPersons 14674 :timeAtRisk 1610 :cases 23}
                                                {:totalPersons 114 :timeAtRisk 11 :cases 0})})})]
      ;; the session source has no run; the tool reports the source it ran on
      (-> ((:run gar/tool) {:analysisType "incidenceRate" :analysisId 2} {:source-key "EUNOMIA"})
          (.then (fn [out]
                   (is (= "SYNPUF" (:sourceKey out)))
                   (is (= 2 (count (:results out))))
                   (let [metformin (second (:results out))]
                     (is (= "Metformin" (get-in metformin [:target :name])))
                     (is (= "Hypoglycemia" (get-in metformin [:outcome :name])))
                     (is (= 1610 (:person-years metformin)))
                     (is (= 14.29 (:rate-per-1000-person-years metformin)))
                     (is (= 1.57 (:proportion-per-1000-persons metformin))))
                   (is (= [{:targetId 20 :outcomeId 25} {:targetId 24 :outcomeId 25}]
                          (->> @recorded (filter #(= "/ir/2/report/SYNPUF" (:path %)))
                               (mapv #(get-in % [:opts :query])))))
                   (restore) (done)))))))

(deftest incidence-rate-without-run
  (async done
    (let [[_ restore] (with-routes! {"/ir/2" {:id 2 :expression "{\"targetIds\":[1],\"outcomeIds\":[2]}"}
                                     "/ir/2/info" []
                                     "/source/sources" sources})]
      (-> ((:run gar/tool) {:analysisType "incidenceRate" :analysisId 2} {:source-key "SYNPUF"})
          (.then (fn [out]
                   (is (re-find #"no completed run" (:error out)))
                   (restore) (done)))))))

;; ---------- cohort samples ----------

(deftest samples-lists-and-reads-newest
  (async done
    (let [[recorded restore]
          (with-routes!
           {"/cohortsample/20/SYNPUF" {:generationStatus "COMPLETE"
                                       :samples [{:id 1 :name "old" :size 5}
                                                 {:id 2 :name "ten" :size 10 :gender {:conceptIds [8532]}}]}
            "/cohortsample/20/SYNPUF/2" {:id 2 :name "ten" :size 10 :gender {:conceptIds [8532]}
                                         :elements [{:rank 1 :personId "9" :genderConceptId 8532 :age 70 :recordCount 5}
                                                    {:rank 0 :personId "7" :genderConceptId 8532 :age 60}
                                                    {:rank 2 :personId "8" :genderConceptId 8507 :age 80}]}})]
      (-> ((:run gcs/tool) {:cohortId 20} {:source-key "SYNPUF"})
          (.then (fn [out]
                   (is (= [1 2] (mapv :sampleId (:samples out))))
                   (is (= ["female"] (:gender (second (:samples out)))))
                   (is (= {:fields "elements"} (get-in (second @recorded) [:opts :query])))
                   (let [shown (:shown out)]
                     (is (= 2 (:sampleId shown)))
                     (is (= ["7" "9" "8"] (mapv :personId (:persons shown))))
                     (is (= {"female" 2 "male" 1} (:gender-counts shown)))
                     (is (= {:min 60 :median 70 :max 80} (:age shown))))
                   (restore) (done)))))))

(deftest samples-none-saved
  (async done
    (let [[_ restore] (with-routes! {"/cohortsample/20/SYNPUF" {:generationStatus "COMPLETE" :samples []}})]
      (-> ((:run gcs/tool) {:cohortId 20} {:source-key "SYNPUF"})
          (.then (fn [out]
                   (is (= [] (:samples out)))
                   (is (re-find #"No samples" (:interpretation out)))
                   (restore) (done)))))))

;; ---------- person profile ----------

(deftest profile-condenses-records-around-index
  (async done
    (let [[recorded restore]
          (with-routes!
           {"/cohortdefinition" [{:id 20 :name "New users of glipizide"}]
            "/SYNPUF/person/11188"
            {:gender "FEMALE" :yearOfBirth 1943 :ageAtIndex 66 :recordCount 6
             :observationPeriods [{:type "insurance" :x1 -413 :x2 591}]
             :cohorts [{:cohortDefinitionId 20 :startDate 1234828800000 :endDate 1244332800000}]
             :records [{:domain "drug" :conceptId 1 :conceptName "glipizide 5 MG" :startDay 0}
                       {:domain "drug" :conceptId 1 :conceptName "glipizide 5 MG" :startDay 0}
                       {:domain "drug" :conceptId 1 :conceptName "glipizide 5 MG" :startDay 30}
                       {:domain "drugera" :conceptId 2 :conceptName "glipizide" :startDay 0}
                       {:domain "condition" :conceptId 3 :conceptName "Type 2 diabetes" :startDay -200}
                       {:domain "condition" :conceptId 0 :conceptName "No matching concept" :startDay -5}
                       {:domain "condition" :conceptId 5 :conceptName "Old fracture" :startDay -900}]}})]
      (-> ((:run gpp/tool) {:personId 11188 :cohortId 20} {:source-key "SYNPUF"})
          (.then (fn [out]
                   (is (= {:cohort 20} (get-in (first @recorded) [:opts :query])))
                   (is (re-find #"cohort 20" (:day-0 out)))
                   (let [p (:profile out)]
                     (is (= 66 (:age-at-index p)))
                     (is (= [{:cohortId 20 :name "New users of glipizide" :start "2009-02-17" :end "2009-06-07"}]
                            (:cohorts p)))
                     (is (= #{"drug" "condition"} (set (keys (:domains p)))))
                     (is (= [{:concept "glipizide 5 MG" :records 3 :first-day 0}]
                            (get-in p [:domains "drug" :top-concepts])))
                     ;; the fracture is outside the 365-day window
                     (is (= 2 (get-in p [:domains "condition" :records])))
                     ;; the duplicate glipizide record and the unmapped condition are dropped
                     (is (= [[0 "glipizide 5 MG"] [30 "glipizide 5 MG"]]
                            (mapv (juxt :day :concept) (:around-day-0 p)))))
                   (restore) (done)))))))

(deftest profile-requires-person
  (async done
    (-> ((:run gpp/tool) {} {})
        (.then (fn [out]
                 (is (re-find #"personId is required" (:error out)))
                 (done))))))
