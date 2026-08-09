(ns pythia.tools.client-manifest-test
  "Guards the manifest-driven client tool set: the 28 artifact-editing tools
   come from the ATLAS-authored capability manifest, the 3 conversation tools
   stay authored inline, and all are schema-only (no :run)."
  (:require [cljs.test :refer [deftest is testing]]
            [pythia.tools.client :as client]))

(def capability-names
  #{"add_criterion" "add_criteria" "set_entry_event" "set_observation_window"
    "add_exit_criterion" "set_censor_event" "create_standalone_concept_set"
    "navigate_to" "add_inclusion_rule" "create_feature_analysis"
    "create_characterization" "create_pathway" "update_concept_set"
    "update_feature_analysis" "update_characterization" "update_pathway"
    "update_incidence_rate" "create_incidence_rate" "save_cohort"
    "generate_analysis" "remove_inclusion_rule" "remove_entry_event"
    "use_concept_set" "add_demographic_criterion" "set_event_limits"
    "add_qualifying_criterion" "set_censor_window" "set_era_collapse"})
(def pythia-only #{"ask_user" "create_plan" "update_plan_step"})

(deftest client-tools-name-set
  (is (= (set (map :name client/client-tools)) (into capability-names pythia-only))))

(deftest client-tools-schema-only
  (is (every? #(nil? (:run %)) client/client-tools)))

(deftest capability-tools-object-schema
  (let [by-name (into {} (map (juxt :name identity) client/client-tools))]
    (doseq [n capability-names]
      (is (= "object" (get-in by-name [n :schema :type]))))))
