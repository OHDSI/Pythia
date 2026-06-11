(ns pythia.tools.get-reference-phenotype-test
  (:require [cljs.test :refer [deftest is async testing]]
            [pythia.http :as http]
            [pythia.tools.get-reference-phenotype :as grp]))

(defn- mock-get-json! [route-fn]
  (let [orig http/get-json]
    (set! http/get-json (fn [url opts] (js/Promise.resolve (route-fn url opts))))
    (fn [] (set! http/get-json orig))))

(deftest requires-numeric-id
  (async done
    (-> (js/Promise.resolve ((:run grp/tool) {} {}))
        (.then (fn [out]
                 (is (re-find #"cohortId is required" (:error out)))
                 (done))))))

(deftest fetches-github-raw-and-shapes
  (async done
    (let [recorded (atom nil)
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
                   (is (re-find #"github.com/OHDSI/PhenotypeLibrary/blob/v3.37.0" (:url out)))
                   (restore)
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
