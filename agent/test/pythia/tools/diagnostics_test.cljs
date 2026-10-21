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
                           (if (= "/source/sources" path)
                             [{:sourceId 2 :sourceKey "EUNOMIA"}]
                             [{:id {:cohortDefinitionId 3 :sourceId 2} :status "COMPLETE" :personCount 0}])))]
      (-> ((:run gcs/tool) {:cohortId 3} {:auth "t" :source-key "EUNOMIA"})
          (.then (fn [out]
                   (is (= "/cohortdefinition/3/info" (:path (first @recorded))))
                   (is (= "EUNOMIA" (get-in out [:summary :source-key])))
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

(def ^:private inclusion-report
  "Three rules; treemap leaves are rule bit strings (char i = rule i passed)."
  {:summary {:baseCount 1000 :finalCount 40 :percentMatched "4.00%"}
   :inclusionRuleStats [{:id 0 :name "1. adult" :countSatisfying 1000 :percentExcluded "0.00%"}
                        {:id 1 :name "2. prior T2DM" :countSatisfying 500 :percentExcluded "50.00%"}
                        {:id 2 :name "3. no insulin" :countSatisfying 900 :percentExcluded "10.00%"}]
   :treemapData (js/JSON.stringify
                 (clj->js {:name "Everyone"
                           :children [{:name "111" :size 40} {:name "110" :size 400}
                                      {:name "101" :size 500} {:name "100" :size 60}]}))})

(deftest sa-sequential-attrition-from-treemap
  (async done
    (let [[recorded restore] (mock-request! (fn [_ _ _] inclusion-report))]
      (-> ((:run sa/tool) {:cohortId 4} {:auth "t" :source-key "EUNOMIA"})
          (.then (fn [out]
                   (is (= "/cohortdefinition/4/report/EUNOMIA/inclusion" (:path (first @recorded))))
                   (is (= {:mode 1} (get-in (first @recorded) [:opts :query])))
                   (is (= "persons" (:counting out)))
                   (is (= [1000 440 40] (mapv :remaining-after-rule (:rules out))))
                   (is (= [1000 500 900] (mapv :satisfying-on-its-own (:rules out))))
                   (is (= 90.9 (:drop-from-previous-pct (nth (:rules out) 2))))
                   (is (= [3] (mapv :rule (:flagged out))))
                   (is (re-find #"removes no one" (:note (first (:rules out)))))
                   (restore) (done)))))))

(deftest sa-event-mode
  (async done
    (let [[recorded restore] (mock-request! (fn [_ _ _] inclusion-report))]
      (-> ((:run sa/tool) {:cohortId 4 :mode "event"} {:source-key "EUNOMIA"})
          (.then (fn [out]
                   (is (= {:mode 0} (get-in (first @recorded) [:opts :query])))
                   (is (= "events" (:counting out)))
                   (restore) (done)))))))

(deftest sa-names-sources-where-generated
  (async done
    (let [[_ restore]
          (mock-request! (fn [_ path _]
                           (case path
                             "/cohortdefinition/4/info" [{:id {:sourceId 7} :status "COMPLETE"}]
                             "/source/sources" [{:sourceId 7 :sourceKey "SYNPUF"}]
                             nil)))]
      (-> ((:run sa/tool) {:cohortId 4} {:source-key "EUNOMIA"})
          (.then (fn [out]
                   (is (re-find #"generated on: SYNPUF" (:error out)))
                   (restore) (done)))))))

(deftest sa-no-report
  (async done
    (let [[_ restore] (mock-request! (fn [_ _ _] nil))]
      (-> ((:run sa/tool) {:cohortId 4} {})
          (.then (fn [out]
                   (is (re-find #"No inclusion report" (:error out)))
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
             (case path
               "/source/sources" [{:sourceId 2 :sourceKey "EUNOMIA"}]
               "/cohortdefinition/1/info" [{:id {:sourceId 2} :status "COMPLETE" :personCount 100}]
               "/cohortdefinition/2/info" [{:id {:sourceId 2} :status "COMPLETE" :personCount 80}]
               nil)))]
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
                           (case path
                             "/source/sources" [{:sourceId 2 :sourceKey "EUNOMIA"}]
                             "/cohortdefinition/1/info" [{:id {:sourceId 2} :status "COMPLETE" :personCount 100}]
                             nil)))]
      (-> ((:run gco/tool) {:cohortIds [1 2]} {:source-key "EUNOMIA"})
          (.then (fn [out]
                   (is (re-find #"no completed generation for cohort id\(s\) 2" (:error out)))
                   (restore) (done)))))))
