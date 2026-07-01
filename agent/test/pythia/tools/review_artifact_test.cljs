(ns pythia.tools.review-artifact-test
  (:require [cljs.test :refer [deftest is async testing]]
            [pythia.webapi :as webapi]
            [pythia.tools.review-artifact :as ra]))

(defn- mock-request-status!
  "Stub webapi/request-status: resolves (route-fn method path opts). Returns
   [recorded restore] — mirrors the pattern in tools/diagnostics_test.cljs."
  [route-fn]
  (let [recorded (atom [])
        orig webapi/request-status]
    (set! webapi/request-status
          (fn [method path opts]
            (swap! recorded conj {:method method :path path :opts opts})
            (js/Promise.resolve (route-fn method path opts))))
    [recorded (fn [] (set! webapi/request-status orig))]))

(defn- checks-by-id [out]
  (into {} (map (juxt :id identity) (:checks out))))

(deftest unknown-kind-errors
  (async done
    (-> (js/Promise.resolve ((:run ra/tool) {:kind "bogus" :id 1 :intent "x"} {}))
        (.then (fn [out]
                 (is (re-find #"unknown kind" (:error out)))
                 (done))))))

(deftest requires-id
  (async done
    (-> (js/Promise.resolve ((:run ra/tool) {:kind "cohort" :intent "x"} {}))
        (.then (fn [out]
                 (is (re-find #"id is required" (:error out)))
                 (done))))))

(deftest requires-intent
  (async done
    (-> (js/Promise.resolve ((:run ra/tool) {:kind "cohort" :id 1} {}))
        (.then (fn [out]
                 (is (re-find #"intent is required" (:error out)))
                 (done))))))

(deftest not-found-errors
  (async done
    (let [[_ restore] (mock-request-status! (fn [_ _ _] {:status 404 :body nil}))]
      (-> ((:run ra/tool) {:kind "cohort" :id 9 :intent "x"} {})
          (.then (fn [out]
                   (is (= "cohort 9 not found" (:error out)))
                   (restore) (done)))))))

(deftest non-200-errors
  (async done
    (let [[_ restore] (mock-request-status! (fn [_ _ _] {:status 500 :body nil}))]
      (-> ((:run ra/tool) {:kind "cohort" :id 9 :intent "x"} {})
          (.then (fn [out]
                   (is (re-find #"HTTP 500" (:error out)))
                   (restore) (done)))))))

;; ---------- cohort checks ----------

(deftest cohort-checks-flag-missing-entry-event-and-non-standard-concept
  (async done
    (let [body {:PrimaryCriteria {:CriteriaList []}
                :ConceptSets [{:name "Statins"
                               :expression {:items [{:concept {:CONCEPT_NAME "Statin (non-standard)"
                                                                :STANDARD_CONCEPT "C"}}]}}]
                :InclusionRules []}
          [recorded restore] (mock-request-status! (fn [_ _ _] {:status 200 :body body}))]
      (-> ((:run ra/tool) {:kind "cohort" :id 5 :intent "T2DM cohort"} {})
          (.then (fn [out]
                   (is (= "/cohortdefinition/5" (:path (first @recorded))))
                   (let [by-id (checks-by-id out)]
                     (is (false? (:pass (get by-id "entry-event-present"))))
                     (is (false? (:pass (get by-id "observation-window-set"))))
                     (is (false? (:pass (get by-id "exit-strategy-set"))))
                     (is (false? (:pass (get by-id "all-concept-items-standard"))))
                     (is (re-find #"Statin \(non-standard\)" (:detail (get by-id "all-concept-items-standard")))))
                   (restore) (done)))))))

(deftest cohort-checks-pass-when-well-formed
  (async done
    (let [body {:PrimaryCriteria {:CriteriaList [{:ConditionOccurrence {:CodesetId 0}}]
                                   :ObservationWindow {:PriorDays 0 :PostDays 0}}
                :ConceptSets [{:name "T2DM"
                               :expression {:items [{:concept {:CONCEPT_NAME "Type 2 diabetes"
                                                                :STANDARD_CONCEPT "S"}}]}}]
                :InclusionRules [{:name "on metformin"}]
                :EndStrategy {:type "CONTINUOUS_OBSERVATION"}}
          [_ restore] (mock-request-status! (fn [_ _ _] {:status 200 :body body}))]
      (-> ((:run ra/tool) {:kind "cohort" :id 5 :intent "T2DM cohort"} {})
          (.then (fn [out]
                   (is (every? :pass (:checks out)))
                   (is (= body (:artifact out)))
                   (is (re-find #"clinical judgment" (:instruction out)))
                   (restore) (done)))))))

;; ---------- concept_set checks ----------

(deftest concept-set-checks-item-count
  (async done
    (let [[_ restore] (mock-request-status!
                       (fn [_ _ _] {:status 200 :body {:items [{:concept {}}]}}))]
      (-> ((:run ra/tool) {:kind "concept_set" :id 1 :intent "statins"} {})
          (.then (fn [out]
                   (is (true? (:pass (get (checks-by-id out) "items-present"))))
                   (restore) (done)))))))

;; ---------- feature_analysis checks ----------

(deftest feature-analysis-checks-type-and-design
  (async done
    (let [[_ restore] (mock-request-status! (fn [_ _ _] {:status 200 :body {}}))]
      (-> ((:run ra/tool) {:kind "feature_analysis" :id 1 :intent "x"} {})
          (.then (fn [out]
                   (is (false? (:pass (get (checks-by-id out) "type-and-design-present"))))
                   (restore) (done)))))))

;; ---------- characterization checks ----------

(deftest characterization-checks-require-cohort-and-feature-analysis
  (async done
    (let [[_ restore] (mock-request-status!
                       (fn [_ _ _] {:status 200 :body {:cohorts [] :featureAnalyses []}}))]
      (-> ((:run ra/tool) {:kind "characterization" :id 1 :intent "x"} {})
          (.then (fn [out]
                   (let [by-id (checks-by-id out)]
                     (is (false? (:pass (get by-id "has-cohort"))))
                     (is (false? (:pass (get by-id "has-feature-analysis")))))
                   (restore) (done)))))))

;; ---------- pathway checks ----------

(deftest pathway-checks-require-target-and-event-cohorts
  (async done
    (let [[_ restore] (mock-request-status!
                       (fn [_ _ _] {:status 200 :body {:targetCohorts [{:id 1}] :eventCohorts []}}))]
      (-> ((:run ra/tool) {:kind "pathway" :id 1 :intent "x"} {})
          (.then (fn [out]
                   (let [by-id (checks-by-id out)]
                     (is (true? (:pass (get by-id "has-target-cohort"))))
                     (is (false? (:pass (get by-id "has-event-cohort")))))
                   (restore) (done)))))))

;; ---------- incidence_rate checks ----------

(deftest incidence-rate-checks-require-targets-outcomes-and-time-at-risk
  (async done
    (let [[_ restore] (mock-request-status!
                       (fn [_ _ _] {:status 200 :body {:targetIds [1] :outcomeIds [] :timeAtRisk nil}}))]
      (-> ((:run ra/tool) {:kind "incidence_rate" :id 1 :intent "x"} {})
          (.then (fn [out]
                   (let [by-id (checks-by-id out)]
                     (is (true? (:pass (get by-id "has-target-ids"))))
                     (is (false? (:pass (get by-id "has-outcome-ids"))))
                     (is (false? (:pass (get by-id "time-at-risk-set")))))
                   (restore) (done)))))))
