(ns pythia.plans.templates-test
  (:require [cljs.test :refer [deftest is testing]]
            [pythia.plans.skills :as skills]
            [pythia.plans.templates :as t]))

(deftest all-templates-have-unique-step-ids
  ;; A template that reuses a skill (e.g. build-cohort for target+outcome) must
  ;; give each step a distinct :id override, else plan-payload collapses them.
  (doseq [scenario (keys t/templates)]
    (let [ids (map :id (:steps (t/plan-payload scenario)))]
      (is (= (count ids) (count (set ids)))
          (str scenario " has duplicate step ids: " (vec ids))))))

(deftest every-template-references-real-skills
  (doseq [[scenario tpl] t/templates]
    (testing scenario
      (is (string? (:title tpl)))
      (is (seq (:steps tpl)) "template needs steps")
      (doseq [{:keys [skill]} (:steps tpl)]
        (is (contains? (skills/skill-ids) skill)
            (str scenario " references unknown skill " skill))))))

(deftest scenarios-cover-the-catalog
  (is (= #{"cohort-design" "standalone-concept-set" "characterization"
           "incidence-rate" "pathway" "cohort-diagnostics" "cohort-comparison"
           "reuse-phenotype"}
         (set (keys t/templates)))))

(deftest template-for-resolves-each-scenario
  (doseq [scenario (keys t/templates)]
    (is (some? (t/template-for scenario))))
  (is (nil? (t/template-for "does-not-exist"))))

(deftest plan-payload-shape
  (let [p (t/plan-payload "standalone-concept-set")]
    (is (= "standalone-concept-set" (:scenario p)))
    (is (string? (:title p)))
    (is (every? (fn [s] (and (string? (:id s)) (string? (:label s))
                             (contains? s :linkedProposalKind)
                             (contains? s :linkedRoute)
                             (boolean? (:required s))))
                (:steps p))))
  (is (nil? (t/plan-payload "nope"))))

(deftest incidence-rate-has-distinct-cohort-build-steps
  ;; duplicate build-cohort skill must yield unique step ids via overrides
  (let [ids (map :id (:steps (t/plan-payload "incidence-rate")))]
    (is (= (count ids) (count (set ids))) "step ids unique")
    (is (some #{"build-target"} ids))
    (is (some #{"build-outcome"} ids))))

(deftest plan-payload-resolves-proposal-kind-from-skill
  (let [steps (:steps (t/plan-payload "standalone-concept-set"))
        cs (first (filter #(= "create-concept-set" (:id %)) steps))]
    (is (= "createStandaloneConceptSet" (:linkedProposalKind cs)))))

(deftest every-template-has-a-rich-document
  ;; Plans must carry a sectioned "Plan details" document, not a one-liner.
  (doseq [scenario (keys t/templates)]
    (let [doc (:document (t/plan-payload scenario))]
      (is (string? doc) (str scenario " is missing a :document"))
      (is (re-find #"## Goal" doc)
          (str scenario " document needs a ## Goal section"))
      (is (re-find #"## Success criteria" doc)
          (str scenario " document needs a ## Success criteria section")))))

(deftest simple-scenarios-are-single-step
  ;; Simple flows render as one milestone todo, not a chain of micro-steps.
  (doseq [scenario ["standalone-concept-set" "cohort-comparison"
                    "reuse-phenotype" "cohort-diagnostics"]]
    (is (= 1 (count (:steps (t/plan-payload scenario))))
        (str scenario " should be a single milestone step"))))

(deftest every-step-carries-a-description
  (doseq [scenario (keys t/templates)]
    (doseq [s (:steps (t/plan-payload scenario))]
      (is (and (string? (:description s)) (seq (:description s)))
          (str scenario "/" (:id s) " step is missing a :description")))))
