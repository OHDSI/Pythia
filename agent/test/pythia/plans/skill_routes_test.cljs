(ns pythia.plans.skill-routes-test
  "Guard: every skill's :route (used as a plan step's linkedRoute / a navigate_to
   target) must be a real agent-visible ATLAS view. Catches drift like the
   `concept-set-edit` route that never existed."
  (:require [cljs.test :refer [deftest is testing]]
            [pythia.plans.skills :as skills]
            [pythia.routes :as routes]))

(deftest skill-routes-are-valid-agent-views
  (let [views (set (map :name routes/agent-visible-entries))]
    (doseq [[id s] skills/skills]
      (testing id
        (when-let [route (:route s)]
          (is (contains? views route)
              (str id " :route \"" route "\" is not an agent-visible ATLAS view")))))))
