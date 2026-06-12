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
