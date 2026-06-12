(ns pythia.tools.select-plan-template-test
  (:require [cljs.test :refer [deftest is]]
            [pythia.tools.select-plan-template :as spt]
            [pythia.plans.templates :as templates]))

(deftest schema-enumerates-all-scenarios
  (let [enum (set (get-in spt/tool [:schema :properties :scenario :enum]))]
    (is (= (templates/scenario-ids) enum))))

(deftest run-returns-plan-payload
  (let [out ((:run spt/tool) {:scenario "standalone-concept-set"} {})]
    (is (= "standalone-concept-set" (:scenario out)))
    (is (seq (:steps out)))))

(deftest run-rejects-unknown-scenario
  (let [out ((:run spt/tool) {:scenario "nope"} {})]
    (is (contains? out :error))))

(deftest is-a-server-tool
  (is (fn? (:run spt/tool)))
  (is (= "select_plan_template" (:name spt/tool))))
