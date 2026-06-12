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
    :document "Goal: build a clinically sound OMOP cohort. Approach: reuse an existing cohort or library phenotype if one matches; else resolve concepts, draft concept-set specs, then set entry, inclusion/exclusion, observation window and exit/censor logic. Constraints: Standard Concepts only; descendants on for conditions/drugs. Success: a complete cohort definition."
    :steps [{:skill "find-existing" :required? true}
            {:skill "find-phenotype" :required? true}
            {:skill "resolve-concept-ids" :required? true}
            {:skill "draft-concept-set-spec" :required? true}
            {:skill "set-entry-event" :required? true}
            {:skill "add-inclusion-exclusion" :required? true}
            {:skill "set-observation-window" :required? false}
            {:skill "set-exit-censor" :required? false}]}

   "standalone-concept-set"
   {:title "Create a concept set"
    :steps [{:skill "resolve-concept-ids" :required? true}
            {:skill "create-concept-set" :required? true}]}

   "characterization"
   {:title "Run a characterization"
    :document "Goal: characterize a cohort against feature analyses. Approach: ensure a saved cohort and at least one feature analysis exist, then create the characterization referencing both. Success: a characterization over a saved cohort + feature analysis."
    :steps [{:skill "find-existing" :required? true}
            {:skill "build-cohort" :required? false}
            {:skill "create-feature-analysis" :required? true}
            {:skill "run-characterization" :required? true}]}

   "incidence-rate"
   {:title "Run an incidence-rate analysis"
    :document "Goal: compute outcome incidence over a time-at-risk window. Approach: ensure a TARGET cohort and an OUTCOME cohort both exist and are SAVED, then create the incidence-rate analysis referencing both. Constraint: analyses reference only saved cohorts. Success: a saved incidence-rate analysis with target+outcome and a time-at-risk window."
    :steps [{:skill "find-existing" :id "find-target" :label "Find or pick the target cohort" :required? true}
            {:skill "build-cohort" :id "build-target" :label "Build & save the target cohort" :required? false}
            {:skill "find-existing" :id "find-outcome" :label "Find or pick the outcome cohort" :required? true}
            {:skill "build-cohort" :id "build-outcome" :label "Build & save the outcome cohort" :required? false}
            {:skill "run-incidence-rate" :required? true}]}

   "pathway"
   {:title "Run a pathway analysis"
    :steps [{:skill "find-existing" :id "find-target" :label "Find or pick the target cohort" :required? true}
            {:skill "build-cohort" :id "build-target" :label "Build & save the target cohort" :required? false}
            {:skill "find-existing" :id "find-events" :label "Find or pick the event cohorts" :required? true}
            {:skill "build-cohort" :id "build-events" :label "Build & save event cohort(s)" :required? false}
            {:skill "run-pathway" :required? true}]}

   "cohort-diagnostics"
   {:title "Interpret cohort diagnostics"
    :steps [{:skill "interpret-generation" :required? true}
            {:skill "interpret-attrition" :required? false}]}

   "cohort-comparison"
   {:title "Compare cohorts"
    :steps [{:skill "find-existing" :required? true}
            {:skill "compare-overlap" :required? true}]}

   "reuse-phenotype"
   {:title "Reuse a published phenotype"
    :steps [{:skill "find-phenotype" :required? true}
            {:skill "reuse-reference-phenotype" :required? true}]}})

(defn template-for [scenario] (get templates scenario))

(defn- ->payload-step [{:keys [skill required? id label]}]
  (let [{slabel :label proposal-kind :proposal-kind route :route} (skills/skill skill)]
    {:id (or id skill)
     :label (or label slabel)
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
