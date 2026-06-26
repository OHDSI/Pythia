(ns pythia.tools.select-plan-template
  "select_plan_template — the agent's FIRST move on a multi-artifact request.
   Returns the canonical plan payload for a scenario; the host intercepts the
   output and instantiates a gated plan. Server-side (has :run) so the template
   catalog stays the single source of truth on the agent side."
  (:require [pythia.plans.templates :as templates]))

(def schema
  {:type "object"
   :properties {:scenario {:type "string"
                           :enum (vec (sort (templates/scenario-ids)))
                           :description "The canonical flow for the user's request."}}
   :required ["scenario"]})

(defn run [{:keys [scenario]} _ctx]
  (or (templates/plan-payload scenario)
      {:error (str "unknown scenario: " scenario)}))

(def tool
  {:name "select_plan_template"
   :description (str "Select the canonical multi-step plan for the user's request and "
                     "instantiate it as the pinned plan card. Call this FIRST whenever a "
                     "request genuinely needs 2+ artifacts or multiple phases (cohort design; an "
                     "analysis that needs prerequisites; diagnostics; comparison; phenotype "
                     "reuse). Do NOT use it for trivial single-artifact requests (one concept "
                     "set, one cohort, a single edit) — those skip planning and go straight to "
                     "the proposal. Pass the matching `scenario`. The template ships a rich "
                     "`document` and milestone-level steps; the host renders the full plan and "
                     "you then execute the FIRST not-done required step. Does NOT end your "
                     "turn. Fall back to create_plan only for a genuinely novel request with "
                     "no matching scenario.")
   :schema schema
   :run run})
