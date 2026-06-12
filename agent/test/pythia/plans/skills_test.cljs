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
