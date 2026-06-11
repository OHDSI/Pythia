(ns pythia.tools.diagnostics-test
  "Tests for the WebAPI-GET diagnostic tools."
  (:require [cljs.test :refer [deftest is async testing]]
            [pythia.webapi :as webapi]
            [pythia.tools.verify-concept-mapping :as vcm]
            [pythia.tools.get-artifact :as ga]
            [pythia.tools.get-cohort-generation-summary :as gcs]
            [pythia.tools.summarise-attrition :as sa]
            [pythia.tools.get-cohort-overlap :as gco]))

(defn- mock-request!
  "Stub webapi/request: resolves (route-fn method path opts). Returns [recorded restore]."
  [route-fn]
  (let [recorded (atom [])
        orig webapi/request]
    (set! webapi/request
          (fn [method path opts]
            (swap! recorded conj {:method method :path path :opts opts})
            (js/Promise.resolve (route-fn method path opts))))
    [recorded (fn [] (set! webapi/request orig))]))

(defn- mock-request-status! [route-fn]
  (let [recorded (atom [])
        orig webapi/request-status]
    (set! webapi/request-status
          (fn [method path opts]
            (swap! recorded conj {:method method :path path :opts opts})
            (js/Promise.resolve (route-fn method path opts))))
    [recorded (fn [] (set! webapi/request-status orig))]))

;; ---------- verify_concept_mapping ----------

(deftest vcm-ok-when-domain-and-vocab-match
  (async done
    (let [[recorded restore]
          (mock-request! (fn [_ _ _]
                           {:CONCEPT_ID 201826 :CONCEPT_NAME "Type 2 diabetes mellitus"
                            :DOMAIN_ID "Condition" :VOCABULARY_ID "SNOMED"
                            :STANDARD_CONCEPT "S"}))]
      (-> ((:run vcm/tool) {:conceptId 201826 :expectedDomain "Condition" :expectedVocabulary "SNOMED"}
                           {:auth "Bearer t"})
          (.then (fn [out]
                   (is (= "/vocabulary/EUNOMIA/concept/201826" (:path (first @recorded))))
                   (is (true? (:ok out)))
                   (is (= [] (:issues out)))
                   (is (= "OK — safe to use" (:verdict out)))
                   (restore) (done)))))))

(deftest vcm-flags-domain-mismatch
  (async done
    (let [[_ restore]
          (mock-request! (fn [_ _ _]
                           {:CONCEPT_ID 1 :CONCEPT_NAME "x" :DOMAIN_ID "Drug"
                            :VOCABULARY_ID "RxNorm" :STANDARD_CONCEPT "S"}))]
      (-> ((:run vcm/tool) {:conceptId 1 :expectedDomain "Condition"} {:auth "t"})
          (.then (fn [out]
                   (is (false? (:ok out)))
                   (is (some #(re-find #"domain mismatch" %) (:issues out)))
                   (restore) (done)))))))

(deftest vcm-missing-id
  (async done
    (-> ((:run vcm/tool) {} {})
        (.then (fn [out]
                 (is (false? (:ok out)))
                 (is (= ["conceptId is required (numeric)"] (:errors out)))
                 (done))))))

(deftest vcm-not-found
  (async done
    (let [[_ restore] (mock-request! (fn [_ _ _] nil))]
      (-> ((:run vcm/tool) {:conceptId 999} {})
          (.then (fn [out]
                   (is (false? (:ok out)))
                   (is (string? (first (:errors out))))
                   (restore) (done)))))))

;; ---------- get_artifact ----------

(deftest ga-200-returns-artifact
  (async done
    (let [[recorded restore]
          (mock-request-status! (fn [_ _ _] {:status 200 :body {:id 5 :name "c"}}))]
      (-> ((:run ga/tool) {:kind "cohort" :id 5} {:auth "t"})
          (.then (fn [out]
                   (is (= "/cohortdefinition/5" (:path (first @recorded))))
                   (is (= "cohort" (:kind out)))
                   (is (= {:id 5 :name "c"} (:artifact out)))
                   (restore) (done)))))))

(deftest ga-concept-set-uses-expression-path
  (async done
    (let [[recorded restore]
          (mock-request-status! (fn [_ _ _] {:status 200 :body {}}))]
      (-> ((:run ga/tool) {:kind "concept_set" :id 9} {})
          (.then (fn [_]
                   (is (= "/conceptset/9/expression" (:path (first @recorded))))
                   (restore) (done)))))))

(deftest ga-404
  (async done
    (let [[_ restore] (mock-request-status! (fn [_ _ _] {:status 404 :body nil}))]
      (-> ((:run ga/tool) {:kind "cohort" :id 7} {})
          (.then (fn [out]
                   (is (= "cohort 7 not found" (:error out)))
                   (restore) (done)))))))

(deftest ga-unknown-kind
  (async done
    (-> ((:run ga/tool) {:kind "bogus" :id 1} {})
        (.then (fn [out]
                 (is (re-find #"unknown kind" (:error out)))
                 (done))))))

;; ---------- get_cohort_generation_summary ----------

(deftest gcs-zero-population-interpretation
  (async done
    (let [[recorded restore]
          (mock-request! (fn [_ path _]
                           [{:sourceKey "EUNOMIA" :status "COMPLETE" :personCount 0}]))]
      (-> ((:run gcs/tool) {:cohortId 3} {:auth "t" :source-key "EUNOMIA"})
          (.then (fn [out]
                   (is (= "/cohortdefinition/3/info" (:path (first @recorded))))
                   (is (= 3 (:cohortId out)))
                   (is (= 0 (get-in out [:summary :person-count])))
                   (is (re-find #"person-count is 0" (:interpretation out)))
                   (restore) (done)))))))

(deftest gcs-no-info
  (async done
    (let [[_ restore] (mock-request! (fn [_ _ _] nil))]
      (-> ((:run gcs/tool) {:cohortId 3} {})
          (.then (fn [out]
                   (is (re-find #"no /cohortdefinition" (:error out)))
                   (restore) (done)))))))

;; ---------- summarise_attrition ----------

(deftest sa-flags-large-drop
  (async done
    (let [[recorded restore]
          (mock-request!
           (fn [_ _ _]
             {:inclusionRuleStats
              [{:ruleId 0 :name "base" :personCount 1000}
               {:ruleId 1 :name "narrow" :personCount 50}]}))]
      (-> ((:run sa/tool) {:cohortId 4} {:auth "t" :source-key "EUNOMIA"})
          (.then (fn [out]
                   (is (= "/cohortdefinition/4/report/EUNOMIA" (:path (first @recorded))))
                   (is (= 2 (count (:rules out))))
                   (is (= 1 (count (:flagged out))))
                   (is (re-find #">=90%" (:interpretation out)))
                   (restore) (done)))))))

(deftest sa-no-report
  (async done
    (let [[_ restore] (mock-request! (fn [_ _ _] nil))]
      (-> ((:run sa/tool) {:cohortId 4} {})
          (.then (fn [out]
                   (is (re-find #"no report" (:error out)))
                   (restore) (done)))))))

;; ---------- get_cohort_overlap ----------

(deftest gco-requires-two-ids
  (async done
    (-> ((:run gco/tool) {:cohortIds [1]} {})
        (.then (fn [out]
                 (is (re-find #"at least 2" (:error out)))
                 (done))))))

(deftest gco-counts-and-pairs
  (async done
    (let [[recorded restore]
          (mock-request!
           (fn [_ path _]
             (cond
               (re-find #"/1/report/" path) {:totalRecords 100}
               (re-find #"/2/report/" path) {:totalRecords 80}
               :else nil)))]
      (-> ((:run gco/tool) {:cohortIds [1 2]} {:auth "t" :source-key "EUNOMIA"})
          (.then (fn [out]
                   (is (= "EUNOMIA" (:sourceKey out)))
                   (is (= 100 (get-in out [:counts 1])))
                   (is (= 80 (get-in out [:counts 2])))
                   (is (= 1 (count (:pairs out))))
                   (is (= 1 (:cohortA (first (:pairs out)))))
                   (is (= 2 (:cohortB (first (:pairs out)))))
                   (restore) (done)))))))

(deftest gco-missing-report
  (async done
    (let [[_ restore]
          (mock-request! (fn [_ path _]
                           (if (re-find #"/1/report/" path) {:totalRecords 100} nil)))]
      (-> ((:run gco/tool) {:cohortIds [1 2]} {})
          (.then (fn [out]
                   (is (re-find #"no report found" (:error out)))
                   (restore) (done)))))))
