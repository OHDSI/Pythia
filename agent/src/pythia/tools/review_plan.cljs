(ns pythia.tools.review-plan
  "review_plan tool — forces a structured self-critique of the active plan
   immediately after it is created, before any execution begins. Pure (no
   HTTP); combines the model's self-reported critique with mechanical
   structural checks against the plan.

   The plan can arrive two ways:
   - `ctx :plan` — the plan already active from a PRIOR turn (rebuilt by
     pythia.agent-tools' ->tool-ctx from the eve ToolContext's
     metadata.plan; works for the create_plan path, where the client
     always round-trips before review_plan runs).
   - `args :plan` — the model passes it directly when reviewing a plan
     select_plan_template just returned IN THE SAME TURN. select_plan_template
     runs as an in-process server tool within the same agentic step loop
     (owned by trex's shared agent runtime, not this plugin), so its
     output never reaches ctx before review_plan executes in the same
     request — the model must forward {document, steps} itself.
   `args :plan` takes priority when both are present, since it's always the
   fresher of the two."
  (:require [clojure.string :as str]))

(def schema
  {:type "object"
   :properties
   {:covers_request {:type "boolean"
                      :description "Does the plan, taken as a whole (document + steps), actually cover everything the user asked for?"}
    :gaps {:type "array" :items {:type "string"}
           :description "Anything in the user's original ask that this plan does NOT address. Empty array if none."}
    :risks {:type "array" :items {:type "string"}
            :description "Clinical or methodological risks worth flagging before executing (e.g. ambiguous time windows, missing exclusion logic, an entry event that may not match intent)."}
    :verdict {:type "string" :enum ["approved" "needs_revision"]
              :description "Your overall verdict after the self-critique above."}
    :plan {:type "object"
           :description "Pass this ONLY when reviewing a plan select_plan_template just returned in THIS SAME turn — the tool's context won't see it yet. Include the exact `document` and `steps` (each with at least `id`) from that tool's output. Omit this when reviewing a plan from create_plan (a prior turn) or ongoing progress — the tool reads that from context automatically."
           :properties {:document {:type "string"}
                        :steps {:type "array"
                                :items {:type "object"
                                        :properties {:id {:type "string"}}
                                        :required ["id"]}}}}}
   :required ["covers_request" "verdict"]})

(defn structural-issues
  "Mechanical checks against the plan (document + steps). Pure — exposed for
   direct testing."
  [plan]
  (let [steps (:steps plan)
        doc (:document plan)
        has-doc? (not (str/blank? (str doc)))
        ids (map :id steps)]
    (cond-> []
      (and (> (count steps) 1) (not has-doc?))
      (conj "plan has multiple steps but no :document — multi-phase plans should carry a Goal/Approach/Success-criteria narrative")

      (and has-doc? (not (str/includes? doc "## Goal")))
      (conj "plan :document is missing a \"## Goal\" section")

      (and has-doc? (not (str/includes? doc "## Success criteria")))
      (conj "plan :document is missing a \"## Success criteria\" section")

      (not= (count ids) (count (set ids)))
      (conj "plan has duplicate step ids"))))

(defn run [args ctx]
  (let [plan (or (:plan args) (:plan ctx))]
    (if (or (nil? plan) (empty? (:steps plan)))
      {:error "no active plan to review — call select_plan_template or create_plan first"}
      (let [issues (structural-issues plan)
            verdict (or (:verdict args) "needs_revision")
            needs-fix? (or (seq issues) (= verdict "needs_revision"))]
        {:ok true
         :structural-issues issues
         :model-reported {:covers-request (boolean (:covers_request args))
                           :gaps (or (:gaps args) [])
                           :risks (or (:risks args) [])
                           :verdict verdict}
         :instruction
         (if needs-fix?
           "Fix the plan (update_plan_step / create_plan) before continuing to the first required step."
           "Plan looks complete — continue to the first not-done required step.")}))))

(def tool
  {:name "review_plan"
   :description "Review a plan against the original request, right after select_plan_template or create_plan and before executing any step. Requires an honest self-critique (covers_request, gaps, risks, verdict); combines it with mechanical checks on the plan's structure (missing document, missing Goal/Success-criteria sections, duplicate step ids). If you just called select_plan_template THIS TURN, pass its {document, steps} back via the `plan` argument — the tool's context does not see it yet (select_plan_template runs server-side within the same turn). If reviewing a plan from an earlier turn (create_plan, or updates since), omit `plan` — it's read from context automatically. Returns combined findings plus an instruction: fix the plan first if issues are found, otherwise proceed to the first required step. Does NOT end your turn."
   :schema schema
   :run run})
