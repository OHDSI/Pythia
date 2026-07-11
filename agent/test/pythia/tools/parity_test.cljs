(ns pythia.tools.parity-test
  "Tool-parity audit: the exposed CLJS tool-name set MUST equal the JVM
   `tool-specs` name set (names are a frontend contract).

   Task P3 deleted pythia.tools.registry (the Vercel AI SDK adapter) along
   with entry.cljs/sdk.cljs — the eve `defineTool` adapter (pythia.agent-tools,
   added in P2) is now the only tool-building path, and its
   execute/clientOnly-presence assertions live in agent_tools_test.cljs. This
   namespace keeps ONLY the NAME-parity part: the exposed CLJS tool-name set
   still must equal the JVM baseline."
  (:require [cljs.test :refer [deftest is testing]]
            [clojure.set :as set]
            [pythia.tools :as tools]
            [pythia.tools.client :as client]
            [pythia.agent-tools :as agent-tools]))

(def cljs-extension-names
  "Tools exposed by the CLJS Pythia agent beyond the bao JVM parity baseline."
  #{"save_cohort" "select_plan_template" "review_plan" "review_artifact"})

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
    (is (= 44 (count exposed)))))

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

(deftest agent-tools-name-set-matches-tools-all
  ;; Sanity check that the eve adapter (pythia.agent-tools, P2) exposes
  ;; exactly the same name set as pythia.tools/all — the parity baseline
  ;; above is meaningless if the two diverge. execute/clientOnly presence
  ;; per tool is covered by agent_tools_test.cljs, not duplicated here.
  (is (= (set (map :name tools/all))
         (set (js/Object.keys agent-tools/tools)))))
