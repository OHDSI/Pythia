(ns pythia.plans.templates
  "Canonical plan templates: ordered compositions of skills, one per scenario.
   Authored from the ATLAS flow discovery. `plan-payload` expands a template
   into the host plan shape consumed by src/plan-state.ts applyGatedPlan.

   A step is {:skill <id> :required? bool [:id <override>] [:label <override>]}.
   The :id/:label overrides let one skill (e.g. build-cohort) appear more than
   once in a template (target vs outcome) with distinct, stable step ids."
  (:require [pythia.plans.skills :as skills]))

(def templates
  {"cohort-design"
   {:title "Design a cohort"
    :document (str "## Goal\n"
                   "Build a clinically sound, reproducible OMOP cohort that captures the target population precisely.\n\n"
                   "## Approach\n"
                   "First check whether an existing saved cohort or a validated library phenotype already matches — reuse beats rebuilding. "
                   "Otherwise define the concept sets (resolve clinical terms to Standard concepts, decide where descendants apply), "
                   "set the entry event, then add inclusion/exclusion rules. Finally set the observation window and exit/censor logic if the question needs them.\n\n"
                   "## Prerequisites & constraints\n"
                   "Use Standard Concepts only; turn on descendants for condition/drug concept sets. "
                   "Entry and criteria should reflect the clinical intent, not just keyword matches.\n\n"
                   "## Success criteria\n"
                   "A complete cohort definition: entry event, inclusion/exclusion, and (where relevant) observation window and exit logic — ready to save and generate.")
    :steps [{:skill "review-plan" :required? true}
            {:skill "find-existing" :required? true :label "Check for a reusable cohort or library phenotype"}
            {:skill "draft-concept-set-spec" :required? true}
            {:skill "set-entry-event" :required? true}
            {:skill "add-inclusion-exclusion" :required? true}
            {:skill "set-observation-window" :required? false}
            {:skill "set-exit-censor" :required? false}
            {:skill "review-cohort" :required? true}]}

   "standalone-concept-set"
   {:title "Create a concept set"
    :document (str "## Goal\n"
                   "Create a reusable, server-persisted concept set for the requested clinical terms.\n\n"
                   "## Approach\n"
                   "Resolve each clinical term to a Standard concept, decide where include-descendants applies, then create and save the concept set.\n\n"
                   "## Success criteria\n"
                   "A named concept set saved on the server, built from Standard Concepts.")
    :steps [{:skill "create-concept-set" :required? true}
            {:skill "review-concept-set" :required? true}]}

   "characterization"
   {:title "Run a characterization"
    :document (str "## Goal\n"
                   "Characterize a cohort against one or more feature analyses (covariate sets).\n\n"
                   "## Approach\n"
                   "Ensure a saved cohort exists (reuse or build one), ensure at least one feature analysis exists, then create the characterization referencing both.\n\n"
                   "## Prerequisites & constraints\n"
                   "Characterizations reference only saved cohorts and saved feature analyses.\n\n"
                   "## Success criteria\n"
                   "A characterization over a saved cohort + feature analysis, ready to generate.")
    :steps [{:skill "review-plan" :required? true}
            {:skill "find-existing" :required? true}
            {:skill "build-cohort" :required? false}
            {:skill "create-feature-analysis" :required? true}
            {:skill "run-characterization" :required? true}
            {:skill "review-characterization" :required? true}]}

   ;; Editing a cohort that already exists is its own shape of work: nothing is
   ;; being created, the definition on screen is the user's, and every change
   ;; has to be saved again to persist.
   "refine-cohort"
   {:title "Refine an existing cohort"
    :document (str "## Goal\n"
                   "Change a cohort that already exists — add, drop or adjust criteria — without rebuilding it.\n\n"
                   "## Approach\n"
                   "Open the saved cohort, read its current definition before touching it, then make the smallest "
                   "change that answers the request: add_criterion / add_inclusion_rule to add, "
                   "remove_inclusion_rule / remove_entry_event to drop, and set_observation_window / "
                   "add_exit_criterion / set_censor_event to overwrite a setting. Save again afterwards.\n\n"
                   "## Prerequisites & constraints\n"
                   "The cohort must already be saved. Changes live only in the editor until save_cohort is accepted, "
                   "so a refinement that is not saved has not happened. Do not rebuild the definition from scratch "
                   "to make one change — that loses whatever the user edited by hand.\n\n"
                   "## Success criteria\n"
                   "The saved cohort reflects the requested change and nothing else, confirmed with review_artifact.")
    :steps [{:skill "find-existing" :id "find-cohort" :label "Find the cohort to change" :required? true}
            {:skill "add-inclusion-exclusion" :id "apply-change" :label "Apply the requested change" :required? false}
            {:skill "build-cohort" :id "save-cohort" :label "Save the changed cohort" :required? true}
            {:skill "review-cohort" :required? true}]}

   "incidence-rate"
   {:title "Run an incidence-rate analysis"
    :document (str "## Goal\n"
                   "Compute outcome incidence in a target population over a defined time-at-risk window.\n\n"
                   "## Approach\n"
                   "Ensure a TARGET cohort and an OUTCOME cohort both exist and are saved (reuse or build each), "
                   "then create the incidence-rate analysis referencing both and set the time-at-risk window.\n\n"
                   "## Prerequisites & constraints\n"
                   "Analyses reference only saved cohorts. Target and outcome must be distinct saved cohorts.\n\n"
                   "## Success criteria\n"
                   "A saved incidence-rate analysis with target + outcome and a time-at-risk window.")
    :steps [{:skill "review-plan" :required? true}
            {:skill "find-existing" :id "find-target" :label "Find or pick the target cohort" :required? true}
            {:skill "build-cohort" :id "build-target" :label "Build & save the target cohort" :required? false}
            {:skill "find-existing" :id "find-outcome" :label "Find or pick the outcome cohort" :required? true}
            {:skill "build-cohort" :id "build-outcome" :label "Build & save the outcome cohort" :required? false}
            {:skill "run-incidence-rate" :required? true}
            {:skill "review-incidence-rate" :required? true}]}

   "pathway"
   {:title "Run a pathway analysis"
    :document (str "## Goal\n"
                   "Reveal treatment / event sequences within a target population.\n\n"
                   "## Approach\n"
                   "Ensure a saved TARGET cohort and the saved EVENT cohorts exist (reuse or build each), "
                   "then create the pathway analysis referencing the target and event cohorts.\n\n"
                   "## Prerequisites & constraints\n"
                   "Pathway analyses reference only saved cohorts; event cohorts define the steps to sequence.\n\n"
                   "## Success criteria\n"
                   "A saved pathway analysis over a target cohort and one or more event cohorts.")
    :steps [{:skill "review-plan" :required? true}
            {:skill "find-existing" :id "find-target" :label "Find or pick the target cohort" :required? true}
            {:skill "build-cohort" :id "build-target" :label "Build & save the target cohort" :required? false}
            {:skill "find-existing" :id "find-events" :label "Find or pick the event cohorts" :required? true}
            {:skill "build-cohort" :id "build-events" :label "Build & save event cohort(s)" :required? false}
            {:skill "run-pathway" :required? true}
            {:skill "review-pathway" :required? true}]}

   "cohort-diagnostics"
   {:title "Interpret cohort diagnostics"
    :document (str "## Goal\n"
                   "Explain how a cohort generated and where people were lost.\n\n"
                   "## Approach\n"
                   "Read the generation summary for the headline person-count and any failure, then walk the attrition table to name the highest-drop inclusion rules.\n\n"
                   "## Success criteria\n"
                   "The user understands the cohort size, any generation failure, and which rules drove the largest attrition.")
    :steps [{:skill "interpret-generation" :required? true}]}

   "cohort-comparison"
   {:title "Compare cohorts"
    :document (str "## Goal\n"
                   "Quantify how two cohorts relate — overlap, redundancy, or disjointness.\n\n"
                   "## Approach\n"
                   "Identify the cohorts to compare, then compute their overlap and interpret it for the user.\n\n"
                   "## Success criteria\n"
                   "Overlap computed and explained, with redundancy or disjointness flagged.")
    :steps [{:skill "compare-overlap" :required? true}]}

   "reuse-phenotype"
   {:title "Reuse a published phenotype"
    :document (str "## Goal\n"
                   "Adopt a validated, published phenotype rather than authoring one from scratch.\n\n"
                   "## Approach\n"
                   "Find the published phenotype in PheKB / the OHDSI library, load its reference Circe definition, and validate it before transplanting.\n\n"
                   "## Success criteria\n"
                   "The reference phenotype's Circe definition is loaded (and validated if transplanted).")
    :steps [{:skill "reuse-reference-phenotype" :required? true}]}})

(defn template-for [scenario] (get templates scenario))

(defn- ->payload-step [{:keys [skill required? id label description]}]
  (let [{slabel :label sdesc :description proposal-kind :proposal-kind route :route} (skills/skill skill)]
    {:id (or id skill)
     :label (or label slabel)
     :description (or description sdesc)
     :linkedProposalKind proposal-kind
     :linkedRoute route
     :required (boolean required?)}))

(defn plan-payload
  "Expand a template into the host plan payload, or nil for an unknown scenario."
  [scenario]
  (when-let [tpl (template-for scenario)]
    (cond-> {:scenario scenario
             :title (:title tpl)
             :steps (mapv ->payload-step (:steps tpl))}
      (:document tpl) (assoc :document (:document tpl)))))

(defn scenario-ids [] (set (keys templates)))
