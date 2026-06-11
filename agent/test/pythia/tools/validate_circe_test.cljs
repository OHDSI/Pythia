(ns pythia.tools.validate-circe-test
  (:require [cljs.test :refer [deftest is async testing]]
            [pythia.trex :as trex]
            [pythia.tools.validate-circe :as vc]))

(def ^:private minimal-circe
  {:PrimaryCriteria
   {:CriteriaList [{:ConditionOccurrence {:CodesetId 0}}]
    :ObservationWindow {:PriorDays 0 :PostDays 0}
    :PrimaryCriteriaLimit {:Type "First"}}
   :ConceptSets []
   :InclusionRules []})

;; --- pure helpers ---------------------------------------------------------

(deftest coerce-expression-passes-string-through
  (is (= "{\"a\":1}" (vc/coerce-expression "{\"a\":1}")))
  (is (nil? (vc/coerce-expression nil)))
  (is (re-find #"PrimaryCriteria" (vc/coerce-expression minimal-circe))))

(deftest valid-circe-shape-detects-criteria-list
  (is (true? (vc/valid-circe-shape? {:PrimaryCriteria {:CriteriaList []}})))
  (is (true? (vc/valid-circe-shape? {"PrimaryCriteria" {"CriteriaList" []}})))
  (is (false? (vc/valid-circe-shape? {:PrimaryCriteria {}})))
  (is (false? (vc/valid-circe-shape? {})))
  (is (false? (vc/valid-circe-shape? "not a map"))))

(deftest pre-parse-rejects-bad-json
  (let [out (vc/pre-parse "{not json")]
    (is (false? (:ok out)))
    (is (re-find #"not valid JSON" (first (:errors out))))))

(deftest pre-parse-rejects-non-circe-shape
  (let [out (vc/pre-parse "{\"foo\":1}")]
    (is (false? (:ok out)))
    (is (re-find #"PrimaryCriteria.CriteriaList" (first (:errors out))))))

(deftest pre-parse-accepts-valid-circe
  (is (:ok (vc/pre-parse (js/JSON.stringify (clj->js minimal-circe))))))

(deftest circe-error-detects-inline-prefix
  (is (true? (vc/circe-error? "/* circe error: boom */")))
  (is (true? (vc/circe-error? "   /* circe error: boom */")))
  (is (false? (vc/circe-error? "CREATE TEMP TABLE Codesets ...")))
  (is (false? (vc/circe-error? nil))))

(deftest first-cell-extracts-result-value
  ;; aliased scalar select keys by the alias, not column0
  (is (= "SQL" (trex/first-cell #js [#js {:sql "SQL"}])))
  ;; devx table-fn convention
  (is (= "X" (trex/first-cell #js [#js {:column0 "X"}])))
  (is (= "" (trex/first-cell #js []))))

(deftest b64-encode-roundtrips
  (is (= "{\"a\":1}" (js/atob (vc/b64-encode "{\"a\":1}"))))
  ;; UTF-8 safe
  (is (= "café" (.decode (js/TextDecoder.)
                         (js/Uint8Array.from (js/atob (vc/b64-encode "café"))
                                             (fn [c] (.charCodeAt c 0)))))))

;; --- run with mocked trex/query -------------------------------------------

(defn- mock-trex! [available? query-fn]
  (let [orig-avail trex/available?
        orig-query trex/query]
    (set! trex/available? (fn [] available?))
    (set! trex/query (fn [sql] (js/Promise.resolve (query-fn sql))))
    (fn [] (set! trex/available? orig-avail) (set! trex/query orig-query))))

(deftest run-empty-expression
  (async done
    (-> (js/Promise.resolve ((:run vc/tool) {} {}))
        (.then (fn [out]
                 (is (false? (:ok out)))
                 (is (re-find #"required" (first (:errors out))))
                 (done))))))

(deftest run-in-process-renders-sql
  (async done
    (let [recorded (atom nil)
          restore (mock-trex! true (fn [sql]
                                     (reset! recorded sql)
                                     "CREATE TEMP TABLE Codesets (codeset_id int);"))]
      (-> ((:run vc/tool) {:expression minimal-circe} {})
          (.then (fn [out]
                   (is (true? (:ok out)))
                   (is (re-find #"CREATE TEMP TABLE Codesets" (:sql out)))
                   (is (= [] (:warnings out)))
                   ;; base64 of the expression flows into circe_json_to_sql
                   (is (re-find #"circe_json_to_sql" @recorded))
                   (is (re-find #"circe_sql_render_translate" @recorded))
                   (restore)
                   (done)))))))

(deftest run-in-process-surfaces-circe-error
  (async done
    (let [restore (mock-trex! true (fn [_] "/* circe error: java.lang.RuntimeException: boom */"))]
      (-> ((:run vc/tool) {:expression minimal-circe} {})
          (.then (fn [out]
                   (is (false? (:ok out)))
                   (is (re-find #"circe error" (first (:errors out))))
                   (restore)
                   (done)))))))

(deftest run-pre-parse-failure-short-circuits
  (async done
    (let [called (atom false)
          restore (mock-trex! true (fn [_] (reset! called true) "x"))]
      (-> ((:run vc/tool) {:expression {:foo 1}} {})
          (.then (fn [out]
                   (is (false? (:ok out)))
                   (is (false? @called) "trex/query must not be called for bad shape")
                   (restore)
                   (done)))))))

(deftest run-falls-back-to-webapi-when-trex-absent
  (async done
    (let [restore (mock-trex! false (fn [_] (throw (js/Error. "should not run in-process"))))]
      ;; webapi/request-status hits the network and will reject/return status 0;
      ;; we only assert that the in-process path was NOT taken (no throw).
      (-> ((:run vc/tool) {:expression minimal-circe} {:auth "Bearer x"})
          (.then (fn [out]
                   (is (false? (:ok out)) "no WebAPI in test env -> not ok")
                   (restore)
                   (done)))
          (.catch (fn [e] (restore) (is false (str "unexpected throw: " e)) (done)))))))
