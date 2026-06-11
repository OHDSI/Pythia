(ns pythia.tools.search-collections-test
  "Tests for the six list-entities + score-match search tools."
  (:require [cljs.test :refer [deftest is async testing]]
            [pythia.webapi :as webapi]
            [pythia.tools.search-concept-sets :as csets]
            [pythia.tools.search-existing-cohorts :as cohorts]
            [pythia.tools.search-characterizations :as chars]
            [pythia.tools.search-feature-analyses :as feats]
            [pythia.tools.search-incidence-rates :as irs]
            [pythia.tools.search-pathways :as paths]))

(defn- with-mock [body f]
  (let [recorded (atom nil)
        orig webapi/request]
    (set! webapi/request
          (fn [method path opts]
            (reset! recorded {:method method :path path :opts opts})
            (js/Promise.resolve body)))
    (f recorded (fn [] (set! webapi/request orig)))))

(deftest concept-sets-lists-scores-shapes
  (async done
    (with-mock
      [{:id 1 :name "Statins" :description "lipid lowering"}
       {:id 2 :name "Metformin" :description "antidiabetic"}]
      (fn [recorded restore]
        (-> ((:run csets/tool) {:query "statins"} {:auth "Bearer t"})
            (.then (fn [out]
                     (is (= "GET" (:method @recorded)))
                     (is (= "/conceptset" (:path @recorded)))
                     (is (= "Bearer t" (get-in @recorded [:opts :auth])))
                     (is (= 1 (count (:results out))))
                     (is (= 1 (:id (first (:results out)))))
                     (is (= 3 (:matchScore (first (:results out)))))
                     (restore) (done))))))))

(deftest concept-sets-empty-query-short-circuits
  (async done
    (with-mock []
      (fn [recorded restore]
        (-> ((:run csets/tool) {:query ""} {})
            (.then (fn [out]
                     (is (nil? @recorded))
                     (is (= [] (:results out)))
                     (is (= "empty query" (:note out)))
                     (restore) (done))))))))

(deftest cohorts-lists-from-cohortdefinition
  (async done
    (with-mock
      [{:id 7 :name "T2DM" :description "type 2 diabetes"}]
      (fn [recorded restore]
        (-> ((:run cohorts/tool) {:query "diabetes"} {:auth "Bearer t"})
            (.then (fn [out]
                     (is (= "/cohortdefinition" (:path @recorded)))
                     (is (= 7 (:id (first (:results out)))))
                     (is (= 1 (:matchScore (first (:results out)))))
                     (restore) (done))))))))

(deftest characterizations-uses-content-wrapper-and-path
  (async done
    (with-mock
      {:content [{:id 3 :name "Demographics char" :description ""}]}
      (fn [recorded restore]
        (-> ((:run chars/tool) {:query "demographics"} {:auth "Bearer t"})
            (.then (fn [out]
                     (is (= "/cohort-characterization?size=10000" (:path @recorded)))
                     (is (= 3 (:id (first (:results out)))))
                     (restore) (done))))))))

(deftest feature-analyses-path
  (async done
    (with-mock
      [{:id 9 :name "Condition era covariates" :description ""}]
      (fn [recorded restore]
        (-> ((:run feats/tool) {:query "condition"} {:auth "Bearer t"})
            (.then (fn [out]
                     (is (= "/feature-analysis?size=100000" (:path @recorded)))
                     (is (= 9 (:id (first (:results out)))))
                     (restore) (done))))))))

(deftest incidence-rates-path
  (async done
    (with-mock
      [{:id 11 :name "Diabetes incidence" :description ""}]
      (fn [recorded restore]
        (-> ((:run irs/tool) {:query "diabetes"} {:auth "Bearer t"})
            (.then (fn [out]
                     (is (= "/ir/" (:path @recorded)))
                     (is (= 11 (:id (first (:results out)))))
                     (restore) (done))))))))

(deftest pathways-path-and-empty-note
  (async done
    (with-mock
      [{:id 13 :name "Antidiabetic sequencing" :description ""}]
      (fn [recorded restore]
        (-> ((:run paths/tool) {:query "nonsense"} {:auth "Bearer t"})
            (.then (fn [out]
                     (is (= "/pathway-analysis?size=10000" (:path @recorded)))
                     (is (= [] (:results out)))
                     (is (string? (:note out)))
                     (restore) (done))))))))
