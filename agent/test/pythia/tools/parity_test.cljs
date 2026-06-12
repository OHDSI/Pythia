(ns pythia.tools.parity-test
  "Tool-parity audit: the exposed CLJS tool-name set MUST equal the JVM
   `tool-specs` name set (names are a frontend contract). Also asserts that
   client-side proposal tools build SDK tools with NO `:execute`."
  (:require [cljs.test :refer [deftest is testing]]
            [clojure.set :as set]
            [pythia.tools :as tools]
            [pythia.tools.client :as client]
            [pythia.tools.registry :as registry]))

(def cljs-extension-names
  "Tools exposed by the CLJS Pythia agent beyond the bao JVM parity baseline."
  #{"save_cohort"})

(def jvm-tool-names
  "Exact `:name` of every entry in JVM trexsql.agent.tools/tool-specs."
  #{"search_existing_cohorts"
    "search_existing_concept_sets"
    "search_existing_feature_analyses"
    "search_existing_characterizations"
    "search_existing_pathways"
    "search_existing_incidence_rates"
    "search_phenotypes"
    "get_reference_phenotype"
    "validate_circe"
    "search_ohdsi_book"
    "web_search"
    "get_artifact"
    "search_concepts"
    "draft_concept_set_spec"
    "verify_concept_mapping"
    "get_cohort_generation_summary"
    "summarise_attrition"
    "get_cohort_overlap"
    "search_ohdsi_studies"
    "add_criterion"
    "add_criteria"
    "set_entry_event"
    "set_observation_window"
    "add_exit_criterion"
    "set_censor_event"
    "create_standalone_concept_set"
    "navigate_to"
    "add_inclusion_rule"
    "create_feature_analysis"
    "create_characterization"
    "create_pathway"
    "update_concept_set"
    "update_feature_analysis"
    "update_characterization"
    "update_pathway"
    "update_incidence_rate"
    "create_incidence_rate"
    "ask_user"
    "create_plan"
    "update_plan_step"})

(deftest jvm-has-40-tools
  (is (= 40 (count jvm-tool-names))))

(deftest exposed-name-set-equals-jvm
  (let [exposed   (set (map :name tools/all))
        expected  (set/union jvm-tool-names cljs-extension-names)]
    (testing "no names exposed that are neither in JVM baseline nor CLJS extensions"
      (is (empty? (set/difference exposed expected))
          (str "extra CLJS names: " (set/difference exposed expected))))
    (testing "no JVM names missing from the CLJS exposure"
      (is (empty? (set/difference jvm-tool-names exposed))
          (str "missing CLJS names: " (set/difference jvm-tool-names exposed))))
    (is (= expected exposed))
    (is (= 41 (count exposed)))))

(deftest no-duplicate-names
  (let [names (map :name tools/all)]
    (is (= (count names) (count (set names))) "tool names are unique")))

(deftest client-tools-count
  (is (= 22 (count client/client-tools))))

(deftest save-cohort-exposed
  (is (some #(= "save_cohort" (:name %)) tools/all))
  (is (nil? (:run (first (filter #(= "save_cohort" (:name %)) tools/all)))) "save_cohort is client-side (no :run)"))

(deftest client-tools-have-no-run
  (doseq [t client/client-tools]
    (is (nil? (:run t)) (str (:name t) " must be schema-only (no :run)"))))

(deftest client-sdk-tools-have-no-execute
  (testing "each client tool maps to an SDK tool with NO execute"
    (let [obj (registry/tools-object client/client-tools {})]
      (doseq [{:keys [name]} client/client-tools]
        (let [sdk-tool (unchecked-get obj name)]
          (is (some? sdk-tool) (str name " present in tools-object"))
          (is (undefined? (unchecked-get sdk-tool "execute"))
              (str name " SDK tool must have NO execute")))))))

(deftest server-sdk-tools-have-execute
  (testing "each server tool maps to an SDK tool WITH execute"
    (let [obj (registry/tools-object tools/server {})]
      (doseq [{:keys [name]} tools/server]
        (let [sdk-tool (unchecked-get obj name)]
          (is (fn? (unchecked-get sdk-tool "execute"))
              (str name " server SDK tool must have execute")))))))
