(ns pythia.tools.review-plan
  "review_plan tool — forces a structured self-critique of the active plan
   immediately after it is created, before any execution begins. Pure (no
   HTTP); combines the model's self-reported critique with mechanical
   structural checks against the live plan object (threaded into the tool
   ctx by pythia.entry — see Task 5 of the review-steps plan)."
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
              :description "Your overall verdict after the self-critique above."}}
   :required ["covers_request" "verdict"]})

(defn structural-issues
  "Mechanical checks against the live plan object. Pure — exposed for
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
  (let [plan (:plan ctx)]
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
   :description "Review the ACTIVE plan against the original request, right after select_plan_template or create_plan and before executing any step. Requires an honest self-critique (covers_request, gaps, risks, verdict); combines it with mechanical checks on the plan's structure (missing document, missing Goal/Success-criteria sections, duplicate step ids). Returns combined findings plus an instruction: fix the plan first if issues are found, otherwise proceed to the first required step. Does NOT end your turn."
   :schema schema
   :run run})
