(ns pythia.tools.review-artifact-test
  (:require [cljs.test :refer [deftest is async testing]]
            [pythia.webapi :as webapi]
            [pythia.tools.review-artifact :as ra]))

(defn- mock-request-status!
  "Stub webapi/request-status: resolves (route-fn method path opts). Returns
   [recorded restore] — mirrors the pattern in tools/diagnostics_test.cljs.
   Also stubs webapi/request (the vocabulary lookup) with `lookup-fn`, which
   gets the posted ids; it defaults to a failed lookup (nil). Lookup calls
   are recorded too."
  ([route-fn] (mock-request-status! route-fn (constantly nil)))
  ([route-fn lookup-fn]
   (let [recorded (atom [])
         orig webapi/request-status
         orig-request webapi/request]
     (set! webapi/request-status
           (fn [method path opts]
             (swap! recorded conj {:method method :path path :opts opts})
             (js/Promise.resolve (route-fn method path opts))))
     (set! webapi/request
           (fn [method path opts]
             (swap! recorded conj {:method method :path path :opts opts})
             (js/Promise.resolve (lookup-fn (:body opts)))))
     [recorded (fn []
                 (set! webapi/request-status orig)
                 (set! webapi/request orig-request))])))

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
                ;; id 0 so the entry event's CodesetId actually resolves — a
                ;; criterion pointing at an undefined concept set matches no one.
                :ConceptSets [{:id 0 :name "T2DM"
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

;; ---------- cohort semantics: the checks that catch inverted logic ----------
;;
;; These come from a real failure. The agent was asked for "adults with
;; osteoarthritis starting ibuprofen, excluding anyone with a previous GI bleed
;; or peptic ulcer" and saved a single rule named "Osteoarthritis qualification
;; and prior GI safety exclusions" whose three criteria ALL required the event —
;; so the cohort required a GI bleed AND a peptic ulcer. It generated fine, the
;; rule name read reassuringly, and nothing anywhere said the logic was
;; inverted. Presence checks cannot see this; only the cardinality can.

(def ^:private mixed-rule-body
  {:PrimaryCriteria {:CriteriaList [{:DrugExposure {:CodesetId 0}}]
                     :ObservationWindow {:PriorDays 365 :PostDays 0}}
   :ConceptSets [{:id 0 :name "Ibuprofen" :expression {:items [{:concept {:CONCEPT_ID 1 :STANDARD_CONCEPT "S"}}]}}
                 {:id 1 :name "Osteoarthritis" :expression {:items [{:concept {:CONCEPT_ID 2 :STANDARD_CONCEPT "S"}}]}}
                 {:id 2 :name "GI hemorrhage" :expression {:items [{:concept {:CONCEPT_ID 3 :STANDARD_CONCEPT "S"}}]}}]
   :InclusionRules
   [{:name "Osteoarthritis qualification and prior GI safety exclusions"
     :expression {:CriteriaList [{:Criteria {:ConditionOccurrence {:CodesetId 1}}
                                  :Occurrence {:Type 2 :Count 1}}
                                 {:Criteria {:ConditionOccurrence {:CodesetId 2}}
                                  :Occurrence {:Type 2 :Count 1}}]}}]
   :EndStrategy {:DateOffset {:Offset 0}}})

(deftest cohort-checks-flag-a-rule-that-mixes-required-and-excluded
  (async done
    (let [[_ restore] (mock-request-status! (fn [_ _ _] {:status 200 :body mixed-rule-body}))]
      (-> ((:run ra/tool) {:kind "cohort" :id 78 :intent "OA patients on ibuprofen without prior GI bleed"} {})
          (.then (fn [out]
                   (let [by-id (checks-by-id out)]
                     ;; every criterion requires its event, so nothing is excluded
                     (is (re-find #"requires 2 and excludes 0"
                                  (:detail (get by-id "rule-directions-readable"))))
                     ;; the rule name mentions exclusions but excludes nothing
                     (is (false? (:pass (get by-id "exclusions-encoded-not-just-named"))))
                     (is (re-find #"invert" (:detail (get by-id "exclusions-encoded-not-just-named")))))
                   (restore) (done)))))))

(deftest cohort-checks-pass-when-the-exclusion-carries-zero-cardinality
  (async done
    (let [body (assoc mixed-rule-body :InclusionRules
                      [{:name "Osteoarthritis diagnosis before index"
                        :expression {:CriteriaList [{:Criteria {:ConditionOccurrence {:CodesetId 1}}
                                                     :Occurrence {:Type 2 :Count 1}}]}}
                       {:name "Exclude prior GI bleed"
                        :expression {:CriteriaList [{:Criteria {:ConditionOccurrence {:CodesetId 2}}
                                                     :Occurrence {:Type 0 :Count 0}}]}}])
          [_ restore] (mock-request-status! (fn [_ _ _] {:status 200 :body body}))]
      (-> ((:run ra/tool) {:kind "cohort" :id 79 :intent "OA on ibuprofen, no prior GI bleed"} {})
          (.then (fn [out]
                   (let [by-id (checks-by-id out)]
                     (is (true? (:pass (get by-id "exclusions-encoded-not-just-named"))))
                     (is (true? (:pass (get by-id "one-direction-per-rule"))))
                     (is (true? (:pass (get by-id "all-codesets-resolvable")))))
                   (restore) (done)))))))

(deftest cohort-checks-flag-a-criterion-pointing-at-an-empty-or-missing-codeset
  (async done
    (let [body (-> mixed-rule-body
                   (assoc :ConceptSets [{:id 0 :name "Ibuprofen"
                                         :expression {:items [{:concept {:CONCEPT_ID 1 :STANDARD_CONCEPT "S"}}]}}
                                        {:id 1 :name "Osteoarthritis" :expression {:items []}}]))
          [_ restore] (mock-request-status! (fn [_ _ _] {:status 200 :body body}))]
      (-> ((:run ra/tool) {:kind "cohort" :id 80 :intent "x"} {})
          (.then (fn [out]
                   (let [d (:detail (get (checks-by-id out) "all-codesets-resolvable"))]
                     (is (false? (:pass (get (checks-by-id out) "all-codesets-resolvable"))))
                     ;; codeset 1 is defined but empty; codeset 2 is not defined at all
                     (is (re-find #"has no concepts" d))
                     (is (re-find #"not defined" d)))
                   (restore) (done)))))))

;; From a real failure: a rule built without a cardinality was saved with no
;; Occurrence. It read as "at least one" in the editor, but circe-be could not
;; render the cohort at all — the live preview and generation both failed with
;; a NullPointerException.
(deftest cohort-checks-flag-a-criterion-circe-cannot-render
  (async done
    (let [window {:Start {:Coeff -1} :End {:Coeff 1}}
          body (assoc mixed-rule-body :InclusionRules
                      [{:name "At least 1 osteoarthritis diagnosis"
                        :expression {:CriteriaList [{:Criteria {:ConditionOccurrence {:CodesetId 1}}
                                                     :StartWindow window}]}}
                       {:name "Exclude prior GI bleed"
                        :expression {:CriteriaList [{:Criteria {:ConditionOccurrence {:CodesetId 2}}
                                                     :StartWindow window
                                                     :Occurrence {:Type 0 :Count 0}}]}}])
          [_ restore] (mock-request-status! (fn [_ _ _] {:status 200 :body body}))]
      (-> ((:run ra/tool) {:kind "cohort" :id 80 :intent "OA patients without prior GI bleed"} {})
          (.then (fn [out]
                   (let [check (get (checks-by-id out) "criteria-renderable")]
                     (is (false? (:pass check)))
                     (is (re-find #"InclusionRules\[0\]\.expression\.CriteriaList\[0\] has no Occurrence"
                                  (:detail check)))
                     (is (not (re-find #"InclusionRules\[1\]" (:detail check)))))
                   (restore) (done)))))))
;; ---------- live vocabulary ----------
;;
;; The saved snapshot says Standard; what matters is the source being analysed.
;; WebAPI returns valid concepts with INVALID_REASON "V" and leaves out ids the
;; source does not have.

(defn- vocab [& concepts]
  (fn [ids] (filterv #(some #{(:CONCEPT_ID %)} ids) concepts)))

(deftest vocabulary-check-flags-concepts-that-drifted-in-the-source
  (async done
    (let [lookup (vocab {:CONCEPT_ID 1 :STANDARD_CONCEPT "S" :INVALID_REASON "V"}
                        {:CONCEPT_ID 2 :STANDARD_CONCEPT "N" :INVALID_REASON "D"})
          [recorded restore] (mock-request-status! (fn [_ _ _] {:status 200 :body mixed-rule-body}) lookup)]
      (-> ((:run ra/tool) {:kind "cohort" :id 81 :intent "x"} {:source-key "SYNPUF"})
          (.then (fn [out]
                   (let [lookup-call (second @recorded)
                         check (get (checks-by-id out) "concepts-current-in-source-vocabulary")]
                     (is (= "POST" (:method lookup-call)))
                     (is (= "/vocabulary/SYNPUF/lookup/identifiers" (:path lookup-call)))
                     (is (= [1 2 3] (:body (:opts lookup-call))))
                     (is (false? (:pass check)))
                     ;; the snapshot said Standard for all three
                     (is (true? (:pass (get (checks-by-id out) "all-concept-items-standard"))))
                     (is (re-find #"\(2, concept set \"Osteoarthritis\"\) is invalid .*INVALID_REASON=D" (:detail check)))
                     (is (re-find #"\(3, concept set \"GI hemorrhage\"\) is not in this source's vocabulary" (:detail check)))
                     (is (not (re-find #"\(1," (:detail check)))))
                   (restore) (done)))))))

(deftest vocabulary-check-passes-when-every-concept-is-current
  (async done
    (let [lookup (vocab {:CONCEPT_ID 1 :STANDARD_CONCEPT "S" :INVALID_REASON "V"}
                        {:CONCEPT_ID 2 :STANDARD_CONCEPT "S" :INVALID_REASON "V"}
                        {:CONCEPT_ID 3 :STANDARD_CONCEPT "S" :INVALID_REASON "V"})
          [_ restore] (mock-request-status! (fn [_ _ _] {:status 200 :body mixed-rule-body}) lookup)]
      (-> ((:run ra/tool) {:kind "cohort" :id 82 :intent "x"} {})
          (.then (fn [out]
                   (let [check (get (checks-by-id out) "concepts-current-in-source-vocabulary")]
                     (is (true? (:pass check)))
                     (is (nil? (:skipped check))))
                   (restore) (done)))))))

(deftest vocabulary-check-says-not-verified-when-the-lookup-fails
  (async done
    (let [[_ restore] (mock-request-status! (fn [_ _ _] {:status 200 :body mixed-rule-body}))]
      (-> ((:run ra/tool) {:kind "cohort" :id 83 :intent "x"} {})
          (.then (fn [out]
                   (let [check (get (checks-by-id out) "concepts-current-in-source-vocabulary")]
                     (is (true? (:skipped check)))
                     (is (re-find #"NOT VERIFIED" (:detail check))))
                   (restore) (done)))))))

(deftest vocabulary-check-covers-concept-sets-and-skips-excluded-items
  (async done
    (let [body {:items [{:concept {:CONCEPT_ID 10 :CONCEPT_NAME "Atorvastatin" :STANDARD_CONCEPT "S"}}
                        {:concept {:CONCEPT_ID 11 :CONCEPT_NAME "Old statin"} :isExcluded true}]}
          [recorded restore] (mock-request-status! (fn [_ _ _] {:status 200 :body body})
                                                   (vocab {:CONCEPT_ID 10 :STANDARD_CONCEPT "C" :INVALID_REASON "V"}))]
      (-> ((:run ra/tool) {:kind "concept_set" :id 4 :intent "statins"} {})
          (.then (fn [out]
                   (let [check (get (checks-by-id out) "concepts-current-in-source-vocabulary")]
                     (is (= [10] (:body (:opts (second @recorded)))))
                     (is (false? (:pass check)))
                     (is (re-find #"Atorvastatin \(10\) is not Standard" (:detail check))))
                   (restore) (done)))))))

(deftest no-vocabulary-lookup-for-kinds-without-concepts
  (async done
    (let [[recorded restore] (mock-request-status!
                              (fn [_ _ _] {:status 200 :body {:targetIds [1] :outcomeIds [2] :timeAtRisk {}}}))]
      (-> ((:run ra/tool) {:kind "incidence_rate" :id 1 :intent "x"} {})
          (.then (fn [out]
                   (is (= 1 (count @recorded)))
                   (is (nil? (get (checks-by-id out) "concepts-current-in-source-vocabulary")))
                   (restore) (done)))))))

;; ---------- plan goal ----------

(def ^:private plan-doc
  (str "## Goal\nNew users of ibuprofen with osteoarthritis, no prior GI bleed.\n\n"
       "## Approach\nReuse existing concept sets.\n\n"
       "## Success criteria\n- GI bleed is an exclusion (zero cardinality)\n"))

(deftest plan-goal-extracts-goal-and-success-criteria
  (is (= {:goal "New users of ibuprofen with osteoarthritis, no prior GI bleed."
          :success-criteria "- GI bleed is an exclusion (zero cardinality)"}
         (ra/plan-goal {:document plan-doc})))
  (is (nil? (ra/plan-goal {:document "just some notes"})))
  (is (nil? (ra/plan-goal nil))))

(deftest review-returns-the-plan-goal-written-before-the-work
  (async done
    (let [[_ restore] (mock-request-status! (fn [_ _ _] {:status 200 :body mixed-rule-body}))]
      (-> ((:run ra/tool) {:kind "cohort" :id 84 :intent "OA patients on ibuprofen"}
                          {:plan {:document plan-doc :steps [{:id "s1"}]}})
          (.then (fn [out]
                   (is (re-find #"no prior GI bleed" (get-in out [:plan-goal :goal])))
                   (is (re-find #"plan-goal" (:instruction out)))
                   (restore) (done)))))))

(deftest review-without-a-plan-has-no-plan-goal
  (async done
    (let [[_ restore] (mock-request-status! (fn [_ _ _] {:status 200 :body mixed-rule-body}))]
      (-> ((:run ra/tool) {:kind "cohort" :id 85 :intent "x"} {})
          (.then (fn [out]
                   (is (not (contains? out :plan-goal)))
                   (is (not (re-find #"plan-goal" (:instruction out))))
                   (restore) (done)))))))
