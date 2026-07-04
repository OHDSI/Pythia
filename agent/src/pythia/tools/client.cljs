(ns pythia.tools.client
  "Client-side proposal tools. The 19 artifact-editing capabilities come from
   the ATLAS-authored manifest (pythia.manifest) — ATLAS owns those schemas.
   The 3 conversation tools (ask_user, create_plan, update_plan_step) have no
   ATLAS executor, so they stay authored here. All are SCHEMA-ONLY (no :run):
   the registry builds a Vercel AI SDK tool WITHOUT :execute, streamText emits
   `tool-input-available` and ends the step with finishReason:\"tool-calls\",
   and the frontend renders the accept/reject card. Names and schemas are a
   frontend contract."
  (:require [pythia.manifest :as manifest]))

(def ^:private pythia-only-tools
  [{:name "ask_user"
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
    :description "Declare a multi-step plan for the user's request: a prose `document` describing goal/approach/constraints/success, plus an ordered checklist of milestone-level `steps`. Prefer `select_plan_template`; use `create_plan` only for a genuinely novel multi-artifact flow with no matching scenario. Steps are milestone-level — ONE step per artifact or per proposal you will issue, named by outcome ('Create the statins concept set'); never split one proposal into several todos, and fold prep/search into the step it serves. Give each step a one-line `description` with the clinical/OHDSI specifics. Pass a `document` for any multi-phase plan: markdown with `## Goal`, `## Approach`, `## Prerequisites & constraints`, `## Success criteria` — a short paragraph each, not one packed sentence. Trivial single-artifact requests skip create_plan entirely and go straight to the proposal. Replaces any prior active plan. Does NOT end your turn — continue with the first step's tool calls in the same turn."
    :schema {:type "object"
             :properties {:title    {:type "string" :description "Short, user-facing title, e.g. 'Run incidence rate for diabetes patients'."}
                          :document {:type "string"
                                     :description "Markdown narrative with `## Goal`, `## Approach`, `## Prerequisites & constraints`, `## Success criteria` — a short paragraph each (not one packed sentence). Required for any multi-phase plan; skip only for a trivial single-artifact flow that has no plan at all."}
                          :steps    {:type "array"
                                     :description "Ordered, MILESTONE-LEVEL steps — one per artifact or per proposal you will issue, named by outcome; never split one proposal into multiple todos. Set linkedProposalKind on any step that maps to a proposal you will issue — the UI auto-marks the step 'done' when the user accepts that proposal, so you don't need to call update_plan_step after."
                                     :items {:type "object"
                                             :properties {:id    {:type "string" :description "Stable id; pass to update_plan_step."}
                                                          :label {:type "string" :description "Short user-visible label, e.g. 'Create concept set for statins'."}
                                                          :description {:type "string" :description "One-line clarification carrying the clinical/OHDSI specifics for this step (concepts, windows, constraints) — the label says what, this says how/why."}
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

(def client-tools
  "The 19 manifest-driven capability tools ++ the 3 inline conversation tools,
   all schema-only (no :run)."
  (into manifest/capability-tools pythia-only-tools))
