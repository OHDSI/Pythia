(ns pythia.plans.skills
  "Canonical action capabilities — the verified micro-procedure for one user
   action. Templates (pythia.plans.templates) compose these by id.

   :tools         required tool/proposal sequence for the action.
   :proposal-kind the host AgentProposal `kind` whose acceptance completes the
                  step (nil for search/interpretation skills with no card).
                  MUST match the `kind` strings in src/shell-bridge.ts.
   :route         matching navigate_to view name (or nil).
   :success       human-readable done condition.")

(def skills
  {"find-existing"
   {:label "Search for an existing artifact to reuse"
    :tools ["search_existing_cohorts"] :proposal-kind nil :route nil
    :success "the WebAPI was searched; strong matches surfaced to the user"}

   "find-phenotype"
   {:label "Search validated phenotype libraries"
    :tools ["search_phenotypes"] :proposal-kind nil :route nil
    :success "PheKB / OHDSI library searched for a reusable template"}

   "resolve-concept-ids"
   {:label "Resolve clinical terms to standard concept IDs"
    :tools ["search_concepts" "verify_concept_mapping"] :proposal-kind nil :route nil
    :success "each clinical term mapped to a standard concept id"}

   "draft-concept-set-spec"
   {:label "Draft the concept-set spec (logic before IDs)"
    :tools ["draft_concept_set_spec"] :proposal-kind nil :route nil
    :success "a named concept-set spec with clinical terms exists"}

   "create-concept-set"
   {:label "Create the concept set"
    :tools ["create_standalone_concept_set"]
    :proposal-kind "createStandaloneConceptSet" :route "concepts"
    :success "a server-persisted concept set exists"}

   "set-entry-event"
   {:label "Set the cohort entry event"
    :tools ["set_entry_event"] :proposal-kind "addEntryEvent" :route nil
    :success "primary qualifying event is set"}

   "add-inclusion-exclusion"
   {:label "Add inclusion and exclusion criteria"
    :tools ["add_criteria" "add_inclusion_rule"] :proposal-kind "addInclusionRule" :route nil
    :success "inclusion + exclusion criteria proposed"}

   "set-observation-window"
   {:label "Set the observation window"
    :tools ["set_observation_window"] :proposal-kind "setObservationPeriod" :route nil
    :success "lookback / follow-up window set"}

   "set-exit-censor"
   {:label "Set exit and censor logic"
    :tools ["add_exit_criterion" "set_censor_event"] :proposal-kind "setExitCriteria" :route nil
    :success "cohort exit (and censor if applicable) set"}

   "build-cohort"
   {:label "Build and save a cohort"
    :tools ["set_entry_event" "add_criteria" "save_cohort"]
    :proposal-kind "saveCohort" :route "cohort-edit"
    :success "a cohort is defined and saved (has an id)"}

   "create-feature-analysis"
   {:label "Create a feature analysis"
    :tools ["create_feature_analysis"]
    :proposal-kind "createFeatureAnalysis" :route "feature-analysis-edit"
    :success "a feature analysis exists"}

   "run-characterization"
   {:label "Create the characterization"
    :tools ["create_characterization"]
    :proposal-kind "createCharacterization" :route "characterization-edit"
    :success "characterization created over cohort + feature analysis"}

   "run-incidence-rate"
   {:label "Create the incidence-rate analysis"
    :tools ["create_incidence_rate"]
    :proposal-kind "createIncidenceRate" :route "incidence-rate-edit"
    :success "incidence-rate analysis created"}

   "run-pathway"
   {:label "Create the pathway analysis"
    :tools ["create_pathway"]
    :proposal-kind "createPathway" :route "pathway-edit"
    :success "pathway analysis created"}

   "interpret-generation"
   {:label "Read the cohort generation summary"
    :tools ["get_cohort_generation_summary"] :proposal-kind nil :route nil
    :success "headline person-count + any failure cited"}

   "interpret-attrition"
   {:label "Summarise attrition"
    :tools ["summarise_attrition"] :proposal-kind nil :route nil
    :success "high-drop rules named to the user"}

   "compare-overlap"
   {:label "Compute cohort overlap"
    :tools ["get_cohort_overlap"] :proposal-kind nil :route nil
    :success "overlap computed; redundancy / disjointness flagged"}

   "reuse-reference-phenotype"
   {:label "Load a reference phenotype definition"
    :tools ["get_reference_phenotype" "validate_circe"] :proposal-kind nil :route nil
    :success "reference Circe loaded (and validated if transplanted)"}})

(defn skill [id] (get skills id))
(defn skill-ids [] (set (keys skills)))
