(ns pythia.plans.skills
  "Canonical action capabilities — the verified micro-procedure for one user
   action. Templates (pythia.plans.templates) compose these by id.

   :tools         required tool/proposal sequence for the action.
   :description   one-line clarification carried onto the plan-step todo.
   :proposal-kind the host AgentProposal `kind` whose acceptance completes the
                  step (nil for search/interpretation skills with no card).
                  MUST match the `kind` strings in src/shell-bridge.ts.
   :route         matching navigate_to view name (or nil).
   :success       human-readable done condition.")

(def skills
  {"find-existing"
   {:label "Search for an existing artifact to reuse"
    :description "Search saved WebAPI cohorts (and library phenotypes) for a strong match before building anew."
    :tools ["search_existing_cohorts"] :proposal-kind nil :route nil
    :success "the WebAPI was searched; strong matches surfaced to the user"}

   "find-phenotype"
   {:label "Search validated phenotype libraries"
    :description "Search PheKB / OHDSI phenotype libraries for a validated definition to reuse."
    :tools ["search_phenotypes"] :proposal-kind nil :route nil
    :success "PheKB / OHDSI library searched for a reusable template"}

   "resolve-concept-ids"
   {:label "Resolve clinical terms to standard concept IDs"
    :description "Map each clinical term to a Standard concept id and verify the mapping."
    :tools ["search_concepts" "verify_concept_mapping"] :proposal-kind nil :route nil
    :success "each clinical term mapped to a standard concept id"}

   "draft-concept-set-spec"
   {:label "Define the concept sets"
    :description "Resolve clinical terms to Standard concepts and draft each named concept-set spec (include-descendants where appropriate)."
    :tools ["search_concepts" "verify_concept_mapping" "draft_concept_set_spec"] :proposal-kind nil :route nil
    :success "named concept-set specs with resolved Standard concepts exist"}

   "create-concept-set"
   {:label "Reuse or create the concept set"
    :description "Check search_existing_concept_sets FIRST — a set the user already curated is better than a near-duplicate, and stays in step with their edits. Reuse it with use_concept_set; only resolve terms and persist a new set when nothing fits."
    :tools ["search_existing_concept_sets" "use_concept_set" "search_concepts" "create_standalone_concept_set"]
    :proposal-kind "createStandaloneConceptSet" :route "concepts"
    :success "the cohort uses an existing concept set, or a new one exists because none fitted"}

   "set-entry-event"
   {:label "Set the cohort entry event"
    :description "Define the primary qualifying event; Standard Concepts + descendants for conditions/drugs."
    :tools ["set_entry_event"] :proposal-kind "addEntryEvent" :route nil
    :success "primary qualifying event is set"}

   "add-inclusion-exclusion"
   {:label "Add inclusion and exclusion criteria"
    :description "Add the inclusion and exclusion rules that refine the entry cohort."
    :tools ["add_criteria" "add_inclusion_rule"] :proposal-kind "addInclusionRule" :route nil
    :success "inclusion + exclusion criteria proposed"}

   "set-observation-window"
   {:label "Set the observation window"
    :description "Set prior-observation lookback and post-index follow-up windows."
    :tools ["set_observation_window"] :proposal-kind "setObservationPeriod" :route nil
    :success "lookback / follow-up window set"}

   "set-exit-censor"
   {:label "Set exit and censor logic"
    :description "Define cohort exit and any censoring events."
    :tools ["add_exit_criterion" "set_censor_event"] :proposal-kind "setExitCriteria" :route nil
    :success "cohort exit (and censor if applicable) set"}

   "build-cohort"
   {:label "Build and save a cohort"
    :description "Define entry event + criteria and save the cohort so it has an id."
    :tools ["set_entry_event" "add_criteria" "save_cohort"]
    :proposal-kind "saveCohort" :route "cohort-edit"
    :success "a cohort is defined and saved (has an id)"}

   "create-feature-analysis"
   {:label "Create a feature analysis"
    :description "Create a feature analysis (covariate set) for the characterization."
    :tools ["create_feature_analysis"]
    :proposal-kind "createFeatureAnalysis" :route "feature-analysis-edit"
    :success "a feature analysis exists"}

   "run-characterization"
   {:label "Create the characterization"
    :description "Create the characterization over the saved cohort + feature analysis."
    :tools ["create_characterization"]
    :proposal-kind "createCharacterization" :route "characterization-edit"
    :success "characterization created over cohort + feature analysis"}

   "run-incidence-rate"
   {:label "Create the incidence-rate analysis"
    :description "Create the incidence-rate analysis over target + outcome with a time-at-risk window."
    :tools ["create_incidence_rate"]
    :proposal-kind "createIncidenceRate" :route "incidence-rate-edit"
    :success "incidence-rate analysis created"}

   "run-pathway"
   {:label "Create the pathway analysis"
    :description "Create the pathway analysis over the target + event cohorts."
    :tools ["create_pathway"]
    :proposal-kind "createPathway" :route "pathway-edit"
    :success "pathway analysis created"}

   "interpret-generation"
   {:label "Interpret the cohort diagnostics"
    :description "Read the generation summary (headline person-count + any failure) and the attrition table, naming the highest-drop rules."
    :tools ["get_cohort_generation_summary" "summarise_attrition"] :proposal-kind nil :route nil
    :success "headline person-count, any failure, and high-drop rules cited"}

   "interpret-attrition"
   {:label "Summarise attrition"
    :description "Summarise attrition; name the highest-drop inclusion rules."
    :tools ["summarise_attrition"] :proposal-kind nil :route nil
    :success "high-drop rules named to the user"}

   "compare-overlap"
   {:label "Compare the cohorts"
    :description "Find the cohorts to compare, then compute their overlap and flag redundancy or disjointness."
    :tools ["search_existing_cohorts" "get_cohort_overlap"] :proposal-kind nil :route nil
    :success "overlap computed; redundancy / disjointness flagged"}

   "reuse-reference-phenotype"
   {:label "Reuse a published phenotype"
    :description "Find the published phenotype, load its reference Circe definition, and validate if transplanted."
    :tools ["search_phenotypes" "get_reference_phenotype" "validate_circe"] :proposal-kind nil :route nil
    :success "reference Circe loaded (and validated if transplanted)"}

   "review-plan"
   {:label "Review the plan before executing it"
    :description "Re-read the plan against the original request: check for gaps, risks, and structural completeness before starting work."
    :tools ["review_plan"] :proposal-kind nil :route nil
    :success "the plan was reviewed and any gaps/risks were addressed before executing"}

   "review-cohort"
   {:label "Review the saved cohort"
    :description "Fetch the saved cohort's full definition and check it against the original clinical intent before declaring it done."
    :tools ["review_artifact"] :proposal-kind nil :route nil
    :success "the cohort definition was reviewed against the stated intent"}

   "review-concept-set"
   {:label "Review the concept set"
    :description "Fetch the saved concept set's full definition and check it against the original clinical intent."
    :tools ["review_artifact"] :proposal-kind nil :route nil
    :success "the concept set was reviewed against the stated intent"}

   "review-characterization"
   {:label "Review the characterization"
    :description "Fetch the characterization's full definition and check it references the right cohort(s) and feature analysis/es."
    :tools ["review_artifact"] :proposal-kind nil :route nil
    :success "the characterization was reviewed against the stated intent"}

   "review-pathway"
   {:label "Review the pathway analysis"
    :description "Fetch the pathway analysis's full definition and check it references the right target and event cohorts."
    :tools ["review_artifact"] :proposal-kind nil :route nil
    :success "the pathway analysis was reviewed against the stated intent"}

   "review-incidence-rate"
   {:label "Review the incidence-rate analysis"
    :description "Fetch the incidence-rate analysis's full definition and check target, outcome, and time-at-risk are set as intended."
    :tools ["review_artifact"] :proposal-kind nil :route nil
    :success "the incidence-rate analysis was reviewed against the stated intent"}})

(defn skill [id] (get skills id))
(defn skill-ids [] (set (keys skills)))
