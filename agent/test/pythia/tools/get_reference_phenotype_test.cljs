(ns pythia.tools.get-reference-phenotype-test
  (:require [cljs.test :refer [deftest is async testing]]
            [pythia.http :as http]
            [pythia.phenotype-sources :as ps]
            [pythia.tools.get-reference-phenotype :as grp]))

(defn- mock-get-json! [route-fn]
  (let [orig http/get-json]
    (set! http/get-json (fn [url opts] (js/Promise.resolve (route-fn url opts))))
    (fn [] (set! http/get-json orig))))

(def ^:private fake-index
  [{:id 1234 :name "Type 2 diabetes mellitus" :status "Accepted"
    :description "T2DM cohort" :tags ["#endocrine"]
    :entry-domains ["ConditionOccurrence"] :n-inclusion-rules 2
    :n-primary-criteria 1 :concept-sets [{:id 1 :name "T2DM" :n-items 5}]
    :body-path "cohorts/1234.json"}])

(defn- with-fake-index! []
  (reset! @#'ps/index-cache fake-index)
  (fn [] (ps/reset-cache!)))

(deftest requires-numeric-id
  (async done
    (-> (js/Promise.resolve ((:run grp/tool) {} {}))
        (.then (fn [out]
                 (is (re-find #"cohortId is required" (:error out)))
                 (done))))))

(deftest fetches-github-raw-and-enriches-from-index
  (async done
    (let [restore-idx (with-fake-index!)
          recorded (atom nil)
          restore (mock-get-json!
                   (fn [url _]
                     (reset! recorded url)
                     {:status 200 :body {:ConceptSets [] :PrimaryCriteria {}}}))]
      (-> ((:run grp/tool) {:cohortId 1234} {})
          (.then (fn [out]
                   (is (re-find #"raw.githubusercontent.com/OHDSI/PhenotypeLibrary" @recorded))
                   (is (re-find #"/inst/cohorts/1234.json" @recorded))
                   (is (= 1234 (:cohortId out)))
                   (is (= "v3.37.0" (:tag out)))
                   (is (= {:ConceptSets [] :PrimaryCriteria {}} (:body out)))
                   ;; enriched from the index (restored JVM behavior)
                   (is (= "Type 2 diabetes mellitus" (:name out)))
                   (is (= "Accepted" (:status out)))
                   (is (= ["#endocrine"] (:tags out)))
                   (is (map? (:summary out)))
                   (is (= 2 (:n-inclusion-rules (:summary out))))
                   (is (re-find #"github.com/OHDSI/PhenotypeLibrary/blob/v3.37.0" (:url out)))
                   (restore)
                   (restore-idx)
                   (done)))))))

(deftest accepts-string-id
  (async done
    (let [recorded (atom nil)
          restore (mock-get-json!
                   (fn [url _] (reset! recorded url) {:status 200 :body {}}))]
      (-> ((:run grp/tool) {:cohortId "42"} {})
          (.then (fn [out]
                   (is (= 42 (:cohortId out)))
                   (is (re-find #"/42.json" @recorded))
                   (restore)
                   (done)))))))

(deftest not-found-returns-error
  (async done
    (let [restore (mock-get-json! (fn [_ _] {:status 404 :body nil}))]
      (-> ((:run grp/tool) {:cohortId 9999} {})
          (.then (fn [out]
                   (is (re-find #"not found" (:error out)))
                   (is (= "v3.37.0" (:tag out)))
                   (restore)
                   (done)))))))

;; --- HDR UK resolution ---

(deftest hdruk-detail-and-codes
  (async done
    (let [restore (mock-get-json!
                   (fn [url _]
                     (cond
                       (re-find #"/detail/" url)
                       {:status 200
                        :body {:phenotype_id "PH6" :name "Cardiovascular Disease"
                               :coding_system "Read" :status "FINAL"
                               :publications [{:details "Parisi et al 2015"}]}}

                       (re-find #"/export/codes/" url)
                       {:status 200
                        :body (vec (for [n (range 80)]
                                     {:code (str "C" n)
                                      :description (str "code " n)
                                      :attributes {:coding_system "Read"}}))}

                       :else {:status 404 :body nil})))]
      (-> ((:run grp/tool) {:source "hdruk" :id "PH6"} {})
          (.then (fn [out]
                   (is (= "hdruk" (:source out)))
                   (is (= "PH6" (:id out)))
                   (is (= "Cardiovascular Disease" (:name out)))
                   (is (re-find #"phenotypes.healthdatagateway.org/phenotypes/PH6" (:url out)))
                   ;; codes capped at 50
                   (is (= 50 (count (:codes out))))
                   (let [c (first (:codes out))]
                     (is (= "C0" (:code c)))
                     (is (= "code 0" (:description c)))
                     (is (= "Read" (:coding-system c))))
                   (is (= [{:details "Parisi et al 2015"}] (:publications out)))
                   (restore)
                   (done)))))))

(deftest hdruk-fail-soft
  (async done
    (let [restore (mock-get-json! (fn [_ _] {:status 500 :body nil}))]
      (-> ((:run grp/tool) {:source "hdruk" :id "PH6"} {})
          (.then (fn [out]
                   (is (re-find #"not found|failed" (:error out)))
                   (restore)
                   (done)))))))
