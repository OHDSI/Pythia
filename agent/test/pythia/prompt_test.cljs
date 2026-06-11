(ns pythia.prompt-test
  (:require [cljs.test :refer [deftest is]]
            [clojure.string :as str]
            [pythia.prompt :as prompt]))

(deftest system-prompt-mentions-cohort
  (let [s (prompt/system-prompt {:route "/atlas/#/cohortdefinitions" :artifact nil})]
    (is (string? s))
    (is (str/includes? (str/lower-case s) "cohort"))))

(deftest system-prompt-introduces-pythia
  (let [s (prompt/system-prompt {:route nil :artifact nil})]
    (is (str/includes? s "PYTHIA"))))

(deftest system-prompt-injects-route-context
  (let [s (prompt/system-prompt {:route "/atlas/#/cohortdefinitions" :artifact nil})]
    (is (str/includes? s "Current context"))
    (is (str/includes? s "/atlas/#/cohortdefinitions"))))

(deftest system-prompt-injects-artifact-context
  (let [s (prompt/system-prompt {:route "/atlas/#/cohortdefinition/42"
                                 :artifact {:kind "cohort" :id 42 :name "T2DM"}})]
    (is (str/includes? s "cohort"))
    (is (str/includes? s "T2DM"))
    (is (str/includes? s "42"))))

(deftest system-prompt-no-context-block-when-empty
  (let [s (prompt/system-prompt {:route nil :artifact nil})]
    (is (not (str/includes? s "Current context")))))
