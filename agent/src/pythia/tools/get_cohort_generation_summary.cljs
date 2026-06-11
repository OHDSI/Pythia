(ns pythia.tools.get-cohort-generation-summary
  "get_cohort_generation_summary tool — pulls a saved cohort's generation
   status and headline counts from WebAPI. Port of the JVM
   trexsql.agent.tools.get-cohort-generation-summary.
   WebAPI endpoint: GET /cohortdefinition/{id}/info"
  (:require [clojure.string :as str]
            [pythia.webapi :as webapi]))

(def schema
  {:type "object"
   :properties {:cohortId  {:type "number" :description "Saved cohort id from search_existing_cohorts."}
                :sourceKey {:type "string" :description "Optional source key (e.g. 'EUNOMIA'). Defaults to the chat session's source."}}
   :required ["cohortId"]})

(defn- parse-id [raw]
  (cond (number? raw) (long raw)
        (string? raw) (let [n (js/parseInt (str/trim raw) 10)] (when-not (js/isNaN n) n))
        :else nil))

(defn- summarize-info [info source-key]
  (let [arr (when (sequential? info) info)
        match (or (some #(when (= source-key (or (:sourceKey %) (get-in % [:source :sourceKey]))) %)
                        arr)
                  (first arr))]
    (when match
      {:source-key (or (:sourceKey match) (get-in match [:source :sourceKey]))
       :status (or (:status match) (:executionStatus match))
       :start-time (or (:startTime match) (:startDate match))
       :end-time (or (:endTime match) (:endDate match))
       :person-count (or (:personCount match) (:persons match))
       :record-count (or (:recordCount match) (:records match))
       :failure-message (or (:failMessage match) (:failureMessage match))})))

(defn run [args ctx]
  (let [cohort-id (parse-id (:cohortId args))
        source-key (or (some-> (:sourceKey args) str)
                       (:source-key ctx)
                       "EUNOMIA")
        auth (:auth ctx)]
    (if (nil? cohort-id)
      (js/Promise.resolve {:error "cohortId is required (numeric)"})
      (-> (webapi/request "GET" (str "/cohortdefinition/" cohort-id "/info") {:auth auth})
          (.then
           (fn [info]
             (let [summary (summarize-info info source-key)]
               (cond
                 (nil? info)
                 {:error (str "WebAPI returned no /cohortdefinition/" cohort-id "/info")}

                 (nil? summary)
                 {:cohortId cohort-id :note (str "no generation found for source " source-key)
                  :sources (mapv #(or (:sourceKey %) (get-in % [:source :sourceKey])) info)}

                 :else
                 {:cohortId cohort-id
                  :sourceKey source-key
                  :summary summary
                  :interpretation
                  (cond
                    (= "FAILED" (str (:status summary)))
                    "Generation FAILED — surface failure-message and propose fixing the cohort."
                    (and (number? (:person-count summary)) (zero? (:person-count summary)))
                    "Generation succeeded but person-count is 0 — likely a vocabulary/concept-id mismatch. Inspect concept sets via get_artifact and verify_concept_mapping."
                    (and (number? (:person-count summary)) (< (:person-count summary) 10))
                    "Generation succeeded but person-count is very low (< 10). Check inclusion rule attrition with summarise_attrition and review entry-event narrowness."
                    :else
                    "Generation completed. Compare person-count to expected prevalence; call summarise_attrition to inspect inclusion-rule drop-offs.")}))))
          (.catch (fn [e]
                    {:error (str "WebAPI request failed: " (or (.-message e) e))}))))))

(def tool
  {:name "get_cohort_generation_summary"
   :description "Pull a saved cohort's generation status and headline person/record counts on a given source. Use this AFTER the user has generated a cohort, when they ask whether the cohort is sensible, healthy, or as expected. Returns {:summary :interpretation} — the interpretation flags zero-population or very-low-population cases and points at the next diagnostic step. Source key defaults to the agent's session source."
   :schema schema
   :run run})
