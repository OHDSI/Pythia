(ns pythia.tools.client
  "Client-side proposal tools — ported verbatim from the JVM `tool-specs`
   entries with `:side :client`. These are SCHEMA-ONLY: each has NO `:run`,
   so the registry builds a Vercel AI SDK `tool(...)` WITHOUT `:execute`.
   streamText emits `tool-input-available` for the call and ends the step
   with finishReason:\"tool-calls\"; the frontend (*ProposalCard.vue) renders
   the accept/reject card and posts the result back to continue. Names and
   schemas are a frontend contract — keep them byte-faithful to the JVM."
  (:require [pythia.routes :as routes]))

(def ^:private domain-enum
  ["Condition" "Drug" "Procedure" "Measurement"
   "Observation" "Visit" "Device" "Specimen"])

(def ^:private operator-enum ["gt" "gte" "lt" "lte" "eq" "between"])

(def ^:private criterion-schema
  {:type "object"
   :properties {:conceptId          {:type "number" :description "OMOP concept ID"}
                :conceptName        {:type "string" :description "Human-readable concept name"}
                :domain             {:type "string" :enum domain-enum :description "OMOP domain"}
                :group              {:type "string" :enum ["inclusion" "exclusion"]}
                :includeDescendants {:type "boolean"}
                :operator           {:type "string" :enum operator-enum
                                     :description "Measurement value operator (only for Measurement domain)"}
                :value              {:type "number" :description "Threshold value (only for Measurement domain)"}
                :value2             {:type "number" :description "Upper bound for between operator"}}
   :required ["conceptId" "conceptName" "domain" "group" "includeDescendants"]})

(def ^:private concept-ref-schema
  {:type "object"
   :properties {:conceptId          {:type "number"}
                :conceptName        {:type "string"}
                :domain             {:type "string" :enum domain-enum}
                :includeDescendants {:type "boolean"}
                :isExcluded         {:type "boolean" :description "Optional exclusion flag for concept-set items"}}
   :required ["conceptId" "conceptName" "domain"]})

(def ^:private temporal-window-schema
  {:type "object"
   :description "Optional temporal window for the criteria, relative to the index (cohort entry) start date."
   :properties {:startDays {:type ["number" "null"] :description "Window start in days vs index start: negative = before index, 0 = at index, null = all time prior."}
                :endDays   {:type ["number" "null"] :description "Window end in days vs index start: positive = after index, null = all time after. Default 0 (index date)."}}})

(def ^:private agent-visible-views
  "Route names the agent may navigate to — the agentVisible=true entries of
   the Atlas3 routes manifest (mirrors JVM rm/agent-visible-views)."
  routes/agent-visible-views)

(def client-tools
  "Every `:side :client` entry from JVM `tool-specs`, as
   {:name :description :schema}. NO :run (schema-only)."
  [{:name "add_criterion"
    :description "Propose adding one criterion to the cohort. The user sees a confirmation card and accepts or rejects."
    :schema criterion-schema}

   {:name "add_criteria"
    :description "Propose adding multiple criteria to the cohort with AND/OR logic. ALWAYS provide a `name` describing the rule (e.g. \"On metformin or sulfonylurea\", \"Excludes pregnancy\"); never omit it. Prefer add_inclusion_rule when cardinality or temporal constraints are needed."
    :schema {:type "object"
             :properties {:name  {:type "string" :description "Short, human-readable label for this group (REQUIRED). E.g. 'Confirmatory T2DM treatment', 'Exclude Type 1 DM'."}
                          :group {:type "string" :enum ["inclusion" "exclusion"]}
                          :logic {:type "string" :enum ["AND" "OR"]}
                          :items {:type "array" :items criterion-schema}}
             :required ["name" "group" "logic" "items"]}}

   {:name "set_entry_event"
    :description "Set the cohort's primary qualifying entry event. Replaces any existing entry event."
    :schema concept-ref-schema}

   {:name "set_observation_window"
    :description "Set the prior + post observation window (in days) around the entry event. priorDays = days of continuous observation required BEFORE entry; postDays = days of continuous observation required AFTER entry."
    :schema {:type "object"
             :properties {:priorDays {:type "number"}
                          :postDays  {:type "number"}}
             :required ["priorDays" "postDays"]}}

   {:name "add_exit_criterion"
    :description "Define how a patient exits the cohort. Strategy: end_of_observation = end of continuous observation period; fixed_duration = N days after entry; continuous_drug = persistence-window-driven exit; custom_event = exit on a clinical event."
    :schema {:type "object"
             :properties {:strategy {:type "string" :enum ["end_of_observation" "fixed_duration" "continuous_drug" "custom_event"]}
                          :offset    {:type "number" :description "Days offset (for fixed_duration and continuous_drug)"}
                          :dateField {:type "string" :enum ["START_DATE" "END_DATE"] :description "Anchor for offset"}
                          :persistenceWindow {:type "number" :description "Gap days between exposures for continuous_drug"}
                          :surveillanceWindow {:type "number" :description "Trailing days after final exposure for continuous_drug"}
                          :concept   (assoc concept-ref-schema :description "Concept defining the exit event (for continuous_drug or custom_event)")}
             :required ["strategy"]}}

   {:name "set_censor_event"
    :description "Add a censoring criterion that ends a patient's time-at-risk when the event occurs."
    :schema concept-ref-schema}

   {:name "create_standalone_concept_set"
    :description "Create a NEW reusable concept set on the server via WebAPI. This is the ONLY way pythia creates concept sets. The host persists it, then: if the user has a cohort open, attaches the concept set to that cohort and stays in the cohort builder; otherwise navigates to the concept-set editor. We deliberately do NOT support cohort-local (in-line) concept sets — every set is reusable across cohorts. Use a clinical, descriptive name (e.g. 'Statins', 'Inhaled corticosteroids', 'Type 2 Diabetes diagnoses')."
    :schema {:type "object"
             :properties {:name        {:type "string" :description "Concept set name (REQUIRED). Clinical and descriptive."}
                          :description {:type "string" :description "Optional one-line description"}
                          :items       {:type "array" :items concept-ref-schema}}
             :required ["name" "items"]}}

   {:name "navigate_to"
    :description "Suggest moving the user to a different view in ATLAS. The user sees a 5s undo toast after the navigation is applied (no approval card). Always include a one-sentence `reason` so the toast is self-explanatory. Each `view` accepts a specific set of params — see the route manifest."
    :schema {:type "object"
             :properties {:view    {:type "string"
                                    :enum agent-visible-views
                                    :description "Route name from the Atlas3 route manifest (resources/routes.manifest.json — generated by Atlas3/scripts/emit-route-manifest.mjs). Edit the manifest to add a new view."}
                          :id          {:type "number"}
                          :sourceKey   {:type "string"}
                          :conceptId   {:type "number"}
                          :personId    {:type "number"}
                          :executionId {:type "number"}
                          :reason      {:type "string" :description "One short sentence; surfaced in the undo toast."}}
             :required ["view"]}}

   {:name "add_inclusion_rule"
    :description "Add an inclusion rule: a named group of criteria with AND/OR logic and optional cardinality (AT_LEAST/AT_MOST count) or temporal window. Prefer this over add_criteria when the model needs cardinality or temporal constraints."
    :schema {:type "object"
             :properties {:name        {:type "string"}
                          :description {:type "string"}
                          :logicType   {:type "string" :enum ["ALL" "ANY" "AT_LEAST" "AT_MOST"]}
                          :count       {:type "number" :description "Required for AT_LEAST and AT_MOST"}
                          :temporalWindow temporal-window-schema
                          :events      {:type "array" :items criterion-schema}}
             :required ["name" "logicType" "events"]}}

   {:name "create_feature_analysis"
    :description "Persist a NEW feature analysis (covariate definition) on the server. The user sees an approval card; on accept, ATLAS navigates to the editor. `type` controls the design shape: PRESET (built-in OHDSI preset id as a string), CRITERIA_SET (JSON object with conceptSets + criteria), or CUSTOM_FE (raw SQL string). Pass `design` accordingly."
    :schema {:type "object"
             :properties {:name        {:type "string" :description "Clinical, descriptive name. REQUIRED."}
                          :description {:type "string"}
                          :type        {:type "string" :enum ["PRESET" "CRITERIA_SET" "CUSTOM_FE"]
                                        :description "PRESET for built-in feature library entries; CRITERIA_SET for custom criteria sets; CUSTOM_FE for raw SQL."}
                          :domain      {:type "string"}
                          :statType    {:type "string" :enum ["PREVALENCE" "DISTRIBUTION"]}
                          :design      {:description "Type-dependent: string for PRESET/CUSTOM_FE; object for CRITERIA_SET."}}
             :required ["name" "type"]}}

   {:name "create_characterization"
    :description "Persist a NEW cohort characterization on the server. REQUIRES at least one cohort and at least one feature analysis attached. Before calling this you MUST have called search_existing_cohorts and search_existing_feature_analyses to find the IDs. If neither result has matches, do NOT call this — instead, propose creating the missing prerequisite (cohort first, then feature analysis) and emit a navigate_to(view='cohort-new' or 'feature-analysis-new')."
    :schema {:type "object"
             :properties {:name             {:type "string" :description "Clinical, descriptive name. REQUIRED."}
                          :description      {:type "string"}
                          :cohorts          {:type "array"
                                             :description "REQUIRED. One or more cohorts to characterize. Each item is {id, name} pulled from search_existing_cohorts results."
                                             :items {:type "object"
                                                     :properties {:id {:type "number"}
                                                                  :name {:type "string"}}
                                                     :required ["id" "name"]}}
                          :featureAnalyses  {:type "array"
                                             :description "REQUIRED. One or more feature analyses to apply. Each item is {id, name} pulled from search_existing_feature_analyses results."
                                             :items {:type "object"
                                                     :properties {:id {:type "number"}
                                                                  :name {:type "string"}}
                                                     :required ["id"]}}}
             :required ["name" "cohorts" "featureAnalyses"]}}

   {:name "create_pathway"
    :description "Persist a NEW pathway analysis on the server. Only `name` is required; sensible OHDSI defaults are applied for the rest (combinationWindow=30, minCellCount=5, maxDepth=5, allowRepeats=false). Pass target/event cohort references when the user has named them."
    :schema {:type "object"
             :properties {:name              {:type "string" :description "Clinical, descriptive name. REQUIRED."}
                          :description       {:type "string"}
                          :targetCohorts     {:type "array"
                                              :items {:type "object"
                                                      :properties {:id {:type "number"} :name {:type "string"}}
                                                      :required ["id" "name"]}}
                          :eventCohorts      {:type "array"
                                              :items {:type "object"
                                                      :properties {:id {:type "number"} :name {:type "string"}}
                                                      :required ["id" "name"]}}
                          :combinationWindow {:type "number" :description "Days for collapsing concurrent events (default 30)"}
                          :minCellCount      {:type "number" :description "Minimum cell count to display (default 5)"}
                          :maxDepth          {:type "number" :description "Max pathway depth, 1-10 (default 5)"}
                          :allowRepeats      {:type "boolean" :description "Whether the same event can repeat in a pathway (default false)"}}
             :required ["name"]}}

   {:name "update_concept_set"
    :description "Apply a partial edit to an existing standalone concept set: rename, change description, append items, or replace items. Mutates the open editor; user clicks Save to persist. Use itemsToAdd to append (skips duplicate conceptIds), items to fully replace."
    :schema {:type "object"
             :properties {:id          {:type "number" :description "Concept set id (REQUIRED)."}
                          :name        {:type "string"}
                          :description {:type "string"}
                          :items       {:type "array" :items concept-ref-schema
                                        :description "Full replace — overwrites the existing items array."}
                          :itemsToAdd  {:type "array" :items concept-ref-schema
                                        :description "Append-only — skips items whose conceptId already exists."}}
             :required ["id"]}}

   {:name "update_feature_analysis"
    :description "Apply a partial edit to an existing feature analysis: rename, change description, change type/domain/statType, or replace the design payload. Mutates the open editor; user clicks Save to persist."
    :schema {:type "object"
             :properties {:id          {:type "number" :description "Feature analysis id (REQUIRED)."}
                          :name        {:type "string"}
                          :description {:type "string"}
                          :type        {:type "string" :enum ["PRESET" "CRITERIA_SET" "CUSTOM_FE"]}
                          :domain      {:type "string"}
                          :statType    {:type "string" :enum ["PREVALENCE" "DISTRIBUTION"]}
                          :design      {:description "Type-dependent: string for PRESET/CUSTOM_FE; object for CRITERIA_SET. Replaces the existing design entirely."}}
             :required ["id"]}}

   {:name "update_characterization"
    :description "Apply a partial edit to an existing characterization: rename, change description, replace or extend cohorts/featureAnalyses. Each cohort and feature-analysis ref is {id, name} from search_existing_* results."
    :schema {:type "object"
             :properties {:id                   {:type "number" :description "Characterization id (REQUIRED)."}
                          :name                 {:type "string"}
                          :description          {:type "string"}
                          :cohorts              {:type "array"
                                                 :items {:type "object"
                                                         :properties {:id {:type "number"} :name {:type "string"}}
                                                         :required ["id" "name"]}
                                                 :description "Full replace."}
                          :cohortsToAdd         {:type "array"
                                                 :items {:type "object"
                                                         :properties {:id {:type "number"} :name {:type "string"}}
                                                         :required ["id" "name"]}
                                                 :description "Append-only."}
                          :featureAnalyses      {:type "array"
                                                 :items {:type "object"
                                                         :properties {:id {:type "number"} :name {:type "string"}}
                                                         :required ["id" "name"]}
                                                 :description "Full replace."}
                          :featureAnalysesToAdd {:type "array"
                                                 :items {:type "object"
                                                         :properties {:id {:type "number"} :name {:type "string"}}
                                                         :required ["id" "name"]}
                                                 :description "Append-only."}}
             :required ["id"]}}

   {:name "update_pathway"
    :description "Apply a partial edit to an existing pathway analysis: rename, change description, replace or extend target/event cohorts, tweak combinationWindow / minCellCount / maxDepth / allowRepeats."
    :schema {:type "object"
             :properties {:id                {:type "number" :description "Pathway id (REQUIRED)."}
                          :name              {:type "string"}
                          :description       {:type "string"}
                          :targetCohorts     {:type "array"
                                              :items {:type "object"
                                                      :properties {:id {:type "number"} :name {:type "string"}}
                                                      :required ["id" "name"]}
                                              :description "Full replace."}
                          :targetCohortsToAdd {:type "array"
                                               :items {:type "object"
                                                       :properties {:id {:type "number"} :name {:type "string"}}
                                                       :required ["id" "name"]}
                                               :description "Append-only."}
                          :eventCohorts      {:type "array"
                                              :items {:type "object"
                                                      :properties {:id {:type "number"} :name {:type "string"}}
                                                      :required ["id" "name"]}
                                              :description "Full replace."}
                          :eventCohortsToAdd {:type "array"
                                              :items {:type "object"
                                                      :properties {:id {:type "number"} :name {:type "string"}}
                                                      :required ["id" "name"]}
                                              :description "Append-only."}
                          :combinationWindow {:type "number"}
                          :minCellCount      {:type "number"}
                          :maxDepth          {:type "number"}
                          :allowRepeats      {:type "boolean"}}
             :required ["id"]}}

   {:name "update_incidence_rate"
    :description "Apply a partial edit to an existing incidence-rate analysis: rename, change description, replace or extend target/outcome cohort ids, tweak timeAtRisk or studyWindow."
    :schema {:type "object"
             :properties {:id              {:type "number" :description "Incidence-rate id (REQUIRED)."}
                          :name            {:type "string"}
                          :description     {:type "string"}
                          :targetIds       {:type "array" :items {:type "number"} :description "Full replace."}
                          :targetIdsToAdd  {:type "array"
                                            :items {:type "object"
                                                    :properties {:id {:type "number"} :name {:type "string"}}
                                                    :required ["id"]}
                                            :description "Append-only with optional display names."}
                          :outcomeIds      {:type "array" :items {:type "number"} :description "Full replace."}
                          :outcomeIdsToAdd {:type "array"
                                            :items {:type "object"
                                                    :properties {:id {:type "number"} :name {:type "string"}}
                                                    :required ["id"]}
                                            :description "Append-only with optional display names."}
                          :timeAtRisk      {:type "object"
                                            :properties {:start {:type "object"
                                                                 :properties {:DateField {:type "string" :enum ["StartDate" "EndDate"]}
                                                                              :Offset    {:type "number"}}
                                                                 :required ["DateField" "Offset"]}
                                                         :end   {:type "object"
                                                                 :properties {:DateField {:type "string" :enum ["StartDate" "EndDate"]}
                                                                              :Offset    {:type "number"}}
                                                                 :required ["DateField" "Offset"]}}
                                            :required ["start" "end"]}
                          :studyWindow     {:type "object"
                                            :properties {:startDate {:type "string"}
                                                         :endDate   {:type "string"}}}}
             :required ["id"]}}

   {:name "create_incidence_rate"
    :description "Persist a NEW incidence-rate analysis on the server. Only `name` is required; the time-at-risk window defaults to {start: StartDate +0, end: EndDate +0} which means 'from cohort start to cohort end'. Provide a custom timeAtRisk when the user describes a specific risk window (e.g., '365 days after exposure' → start: StartDate +0, end: StartDate +365)."
    :schema {:type "object"
             :properties {:name        {:type "string" :description "Clinical, descriptive name. REQUIRED."}
                          :description {:type "string"}
                          :targetIds   {:type "array" :items {:type "number"} :description "Target cohort IDs (denominator)"}
                          :outcomeIds  {:type "array" :items {:type "number"} :description "Outcome cohort IDs (numerator)"}
                          :timeAtRisk  {:type "object"
                                        :properties {:start {:type "object"
                                                             :properties {:DateField {:type "string" :enum ["StartDate" "EndDate"]}
                                                                          :Offset    {:type "number" :description "Days from the anchor date"}}
                                                             :required ["DateField" "Offset"]}
                                                     :end   {:type "object"
                                                             :properties {:DateField {:type "string" :enum ["StartDate" "EndDate"]}
                                                                          :Offset    {:type "number"}}
                                                             :required ["DateField" "Offset"]}}
                                        :required ["start" "end"]}
                          :studyWindow {:type "object"
                                        :properties {:startDate {:type "string" :description "ISO date YYYY-MM-DD"}
                                                     :endDate   {:type "string" :description "ISO date YYYY-MM-DD"}}}}
             :required ["name"]}}

   {:name "save_cohort"
    :description "Persist the CURRENTLY OPEN cohort to the WebAPI so it gets a stable id. Call this AFTER the user has accepted the cohort's entry event + criteria, and BEFORE creating any analysis (incidence rate / pathway / characterization) that must reference this cohort by id — analyses can only target SAVED cohorts. Returns the saved cohort id. Optional name/description override the open cohort's current values."
    :schema {:type "object"
             :properties {:name        {:type "string" :description "Optional clinical name to save under (defaults to the open cohort's name)."}
                          :description {:type "string" :description "Optional description."}}}}

   {:name "ask_user"
    :description "Ask the user a clarifying question with 2–4 discrete clickable options when the next action genuinely depends on their preference and the surrounding context can't disambiguate. Canonical case: the user is editing artifact X and asks to 'create a Y' — should you UPDATE X (repurpose the open editor) or CREATE Y as a new artifact (leaving X alone)? Other cases: pick which of multiple search-result matches the user means; confirm a potentially destructive change. Do NOT use for routine yes/no — only when the choice changes which tools you'd call. After calling this, write a brief one-line preamble and END YOUR TURN; do not call other tools. The user's selection arrives as the next user message so you can act on it."
    :schema {:type "object"
             :properties {:question    {:type "string"
                                        :description "The question, phrased as one short sentence."}
                          :options     {:type "array"
                                        :description "2–4 mutually-exclusive options. List the recommended option first when applicable."
                                        :items {:type "object"
                                                :properties {:id          {:type "string"
                                                                           :description "Stable id; for your own disambiguation."}
                                                             :label       {:type "string"
                                                                           :description "1–5 word button text."}
                                                             :description {:type "string"
                                                                           :description "Optional one-line explanation of what choosing this does."}}
                                                :required ["id" "label"]}}
                          :allowCustom {:type "boolean"
                                        :description "If true, also show an 'Other…' free-text option (default false)."}}
             :required ["question" "options"]}}

   {:name "create_plan"
    :description "Declare a multi-step plan for the user's request: an optional prose `document` describing goal/approach/success criteria, plus an ordered checklist of concrete `steps`. Use this for ANY analysis that needs 3+ artifacts or multiple phases (e.g. cohort + concept set + incidence-rate; phenotype validation across multiple sources; treatment-pattern study). For non-trivial plans pass a `document` field (3-5 sentences of markdown — goal, approach, key constraints, success criteria); the chat panel renders it as a collapsible 'Plan details' section above the steps. Trivial single-step requests skip create_plan and go straight to the proposal. Replaces any prior active plan. Does NOT end your turn — continue with the first step's tool calls in the same turn."
    :schema {:type "object"
             :properties {:title    {:type "string" :description "Short, user-facing title, e.g. 'Run incidence rate for diabetes patients'."}
                          :document {:type "string"
                                     :description "Optional markdown narrative (3-5 sentences) covering goal, approach, prerequisites, and success criteria. Skip for trivial 1-2 step flows."}
                          :steps    {:type "array"
                                     :description "Ordered steps the user must walk through. Set linkedProposalKind on any step that maps to a proposal you will issue — the UI auto-marks the step 'done' when the user accepts that proposal, so you don't need to call update_plan_step after."
                                     :items {:type "object"
                                             :properties {:id    {:type "string" :description "Stable id; pass to update_plan_step."}
                                                          :label {:type "string" :description "Short user-visible label, e.g. 'Create concept set for statins'."}
                                                          :description {:type "string" :description "Optional one-line clarification."}
                                                          :linkedProposalKind {:type "string"
                                                                               :enum ["addEntryEvent" "addInclusionRule" "addConceptSet"
                                                                                      "setObservationPeriod" "setExitCriteria" "addCensoringCriterion"
                                                                                      "createStandaloneConceptSet" "createFeatureAnalysis"
                                                                                      "createCharacterization" "createPathway" "createIncidenceRate"
                                                                                      "saveCohort" "navigate"]
                                                                               :description "Optional. AgentProposal kind the host applies. When set, the UI auto-ticks this step the moment a matching proposal is accepted, so you don't need a follow-up update_plan_step."}
                                                          :linkedRoute {:type "string" :description "Optional ATLAS route name (matches the navigate_to view enum). Renders an 'Open' button on the step row."}}
                                             :required ["id" "label"]}}}
             :required ["title" "steps"]}}

   {:name "update_plan_step"
    :description "Update the status of one step on the active plan. Use when reasoning advances a step but you are NOT issuing a linked proposal (e.g. you finished a search-only step, decided a step is blocked, or want to mark a step in_progress before walking the user through it). DO NOT call this after issuing a proposal whose kind is linked to the step — the UI auto-ticks on acceptance, and a redundant call would briefly show 'done' before the proposal is even applied. Does NOT end your turn."
    :schema {:type "object"
             :properties {:stepId {:type "string"}
                          :status {:type "string" :enum ["pending" "in_progress" "done" "blocked"]}}
             :required ["stepId" "status"]}}])
