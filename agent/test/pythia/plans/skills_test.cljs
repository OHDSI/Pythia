(ns pythia.plans.skills-test
  (:require [cljs.test :refer [deftest is testing]]
            [pythia.plans.skills :as skills]))

(deftest every-skill-well-formed
  (doseq [[id s] skills/skills]
    (testing id
      (is (string? id))
      (is (string? (:label s)) (str id " needs a label"))
      (is (seq (:tools s)) (str id " needs at least one tool"))
      (is (string? (:success s)) (str id " needs a success check"))
      (is (or (nil? (:proposal-kind s)) (string? (:proposal-kind s)))))))

(deftest lookup-helpers
  (is (= (set (keys skills/skills)) (skills/skill-ids)))
  (is (some? (skills/skill "create-concept-set")))
  (is (nil? (skills/skill "nope"))))

(deftest review-skills-are-registered
  (doseq [id ["review-plan" "review-cohort" "review-concept-set"
              "review-characterization" "review-pathway" "review-incidence-rate"]]
    (testing id
      (is (some? (skills/skill id)) (str id " must be defined"))
      (is (nil? (:route (skills/skill id))))
      (is (nil? (:proposal-kind (skills/skill id))))))
  (is (= ["review_plan"] (:tools (skills/skill "review-plan"))))
  (is (= ["review_artifact"] (:tools (skills/skill "review-cohort"))))
  (is (= ["review_artifact"] (:tools (skills/skill "review-concept-set"))))
  (is (= ["review_artifact"] (:tools (skills/skill "review-characterization"))))
  (is (= ["review_artifact"] (:tools (skills/skill "review-pathway"))))
  (is (= ["review_artifact"] (:tools (skills/skill "review-incidence-rate")))))
