(ns pythia.tools.search-concepts-test
  (:require [cljs.test :refer [deftest is async testing]]
            [pythia.webapi :as webapi]
            [pythia.tools.search-concepts :as sc]))

(defn- with-mock-request
  "Stub pythia.webapi/request, capturing args, resolving `rows`."
  [rows f]
  (let [recorded (atom nil)
        orig webapi/request]
    (set! webapi/request
          (fn [method path opts]
            (reset! recorded {:method method :path path :opts opts})
            (js/Promise.resolve rows)))
    (let [restore (fn [] (set! webapi/request orig))]
      (f recorded restore))))

(deftest run-posts-to-vocabulary-search-and-shapes-output
  (async done
    (with-mock-request
      [{:CONCEPT_ID 201826 :CONCEPT_NAME "Type 2 diabetes mellitus"
        :DOMAIN_ID "Condition" :VOCABULARY_ID "SNOMED" :STANDARD_CONCEPT "S"}
       {:CONCEPT_ID 999 :CONCEPT_NAME "non std" :DOMAIN_ID "Condition"
        :VOCABULARY_ID "ICD10CM" :STANDARD_CONCEPT "C"}]
      (fn [recorded restore]
        (-> ((:run sc/tool) {:query "type 2 diabetes"} {:source-key "EUNOMIA"
                                                        :auth "Bearer xyz"})
            (.then (fn [out]
                     (let [{:keys [method path opts]} @recorded]
                       (is (= "POST" method))
                       (is (= "/vocabulary/EUNOMIA/search" path))
                       (is (= "Bearer xyz" (:auth opts)))
                       (is (= "type 2 diabetes" (get-in opts [:body :QUERY]))))
                     ;; only STANDARD_CONCEPT='S' rows survive
                     (is (= 1 (count (:results out))))
                     (is (= 201826 (:conceptId (first (:results out)))))
                     (is (= "Type 2 diabetes mellitus" (:conceptName (first (:results out)))))
                     (is (= 1 (get-in out [:ambiguity :n-results])))
                     (restore)
                     (done))))))))

(deftest run-empty-query-short-circuits
  (async done
    (with-mock-request
      []
      (fn [recorded restore]
        (-> ((:run sc/tool) {:query ""} {:source-key "EUNOMIA"})
            (.then (fn [out]
                     (is (nil? @recorded) "no WebAPI call for empty query")
                     (is (= [] (:results out)))
                     (restore)
                     (done))))))))

(deftest run-defaults-source-key-to-eunomia
  (async done
    (with-mock-request
      []
      (fn [recorded restore]
        (-> ((:run sc/tool) {:query "asthma"} {})
            (.then (fn [_]
                     (is (= "/vocabulary/EUNOMIA/search" (:path @recorded)))
                     (restore)
                     (done))))))))

(deftest ingredient-ranks-above-products-the-vocabulary-lists-first
  ;; A full vocabulary returns thousands of rows for "lisinopril" with the
  ;; ingredient thousands of rows down; cutting to 15 before ranking left
  ;; only packaged products, so the agent concluded the ingredient was absent.
  (async done
    (with-mock-request
      (conj (vec (for [i (range 20)]
                   {:CONCEPT_ID (+ 21000000 i) :CONCEPT_NAME (str "lisinopril " (* 5 (inc i)) " MG Oral Tablet [Box of 10]")
                    :DOMAIN_ID "Drug" :VOCABULARY_ID "RxNorm" :CONCEPT_CLASS_ID "Quant Clinical Drug" :STANDARD_CONCEPT "S"}))
            {:CONCEPT_ID 1308216 :CONCEPT_NAME "lisinopril" :DOMAIN_ID "Drug"
             :VOCABULARY_ID "RxNorm" :CONCEPT_CLASS_ID "Ingredient" :STANDARD_CONCEPT "S"})
      (fn [_ restore]
        (-> ((:run sc/tool) {:query "lisinopril"} {:source-key "SYNPUF5" :auth "Bearer xyz"})
            (.then (fn [out]
                     (is (= 1308216 (:conceptId (first (:results out)))))
                     (is (= "Ingredient" (:conceptClass (first (:results out)))))
                     (is (= :high (:confidence (first (:results out)))))
                     (is (<= (count (:results out)) 15))
                     (restore)
                     (done))))))))

(deftest name-matches-rank-above-other-standard-hits
  (async done
    (with-mock-request
      [{:CONCEPT_ID 1 :CONCEPT_NAME "Hypertensive heart disease" :DOMAIN_ID "Condition"
        :VOCABULARY_ID "SNOMED" :CONCEPT_CLASS_ID "Clinical Finding" :STANDARD_CONCEPT "S"}
       {:CONCEPT_ID 2 :CONCEPT_NAME "Complication of hypertension" :DOMAIN_ID "Condition"
        :VOCABULARY_ID "SNOMED" :CONCEPT_CLASS_ID "Clinical Finding" :STANDARD_CONCEPT "S"}
       {:CONCEPT_ID 316866 :CONCEPT_NAME "Hypertensive disorder" :DOMAIN_ID "Condition"
        :VOCABULARY_ID "SNOMED" :CONCEPT_CLASS_ID "Clinical Finding" :STANDARD_CONCEPT "S"}]
      (fn [_ restore]
        (-> ((:run sc/tool) {:query "hypertensive disorder"} {:source-key "SYNPUF5" :auth "Bearer xyz"})
            (.then (fn [out]
                     (is (= [316866 1 2] (mapv :conceptId (:results out))))
                     (restore)
                     (done))))))))
