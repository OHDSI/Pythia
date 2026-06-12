(ns pythia.tools.summarise-attrition
  "summarise_attrition tool — pulls per-inclusion-rule person counts for a
   generated cohort and flags suspicious drops. Port of the JVM
   trexsql.agent.tools.summarise-attrition.
   WebAPI endpoint: GET /cohortdefinition/{id}/report/{sourceKey}"
  (:require [clojure.string :as str]
            [pythia.webapi :as webapi]))

(def schema
  {:type "object"
   :properties {:cohortId  {:type "number"}
                :sourceKey {:type "string"}}
   :required ["cohortId"]})

(defn- parse-id [raw]
  (cond (number? raw) (long raw)
        (string? raw) (let [n (js/parseInt (str/trim raw) 10)] (when-not (js/isNaN n) n))
        :else nil))

(defn- normalize-rule [rule]
  {:rule-id (or (:ruleId rule) (:id rule))
   :name (or (:name rule) (:ruleName rule))
   :persons (or (:personCount rule) (:persons rule) (:countPersons rule))
   :percent-satisfying (or (:percentSatisfying rule) (:percentExcluded rule))})

(defn- round1 [x] (js/parseFloat (.toFixed x 1)))

(defn- diagnose [rules]
  (let [counts (mapv :persons rules)]
    (->> rules
         (map-indexed
          (fn [i r]
            (let [prev (get counts (dec i))
                  cur (:persons r)
                  drop-pct (when (and (number? prev) (number? cur) (pos? prev))
                             (* 100.0 (/ (- prev cur) (double prev))))]
              (cond-> r
                drop-pct
                (assoc :drop-from-prev-pct (round1 drop-pct))

                (and drop-pct (>= drop-pct 90))
                (assoc :flag "very large drop (>=90%) — verify the rule is intended to be this restrictive")

                (and (number? cur) (zero? cur))
                (assoc :flag "zero subjects after this rule — rule is impossible to satisfy with current vocabulary or data")))))
         vec)))

(defn run [args ctx]
  (let [cohort-id (parse-id (:cohortId args))
        source-key (or (some-> (:sourceKey args) str)
                       (:source-key ctx)
                       "EUNOMIA")
        auth (:auth ctx)]
    (if (nil? cohort-id)
      (js/Promise.resolve {:error "cohortId is required (numeric)"})
      (-> (webapi/request "GET" (str "/cohortdefinition/" cohort-id "/report/" source-key) {:auth auth})
          (.then
           (fn [report]
             (let [raw-rules (or (:inclusionRuleStats report)
                                 (:inclusionRules report)
                                 [])
                   rules (->> raw-rules (map normalize-rule) (remove (comp nil? :persons)) vec)
                   annotated (diagnose rules)]
               (cond
                 (nil? report)
                 {:error (str "WebAPI returned no report for cohort " cohort-id
                              " on source " source-key " — has it been generated?")}

                 (empty? rules)
                 {:cohortId cohort-id :sourceKey source-key
                  :note "report present but no per-rule counts found"}

                 :else
                 {:cohortId cohort-id
                  :sourceKey source-key
                  :rules annotated
                  :flagged (filterv :flag annotated)
                  :interpretation
                  (cond
                    (some #(zero? (or (:persons %) -1)) annotated)
                    "One or more inclusion rules eliminate ALL subjects. Review the offending rule and its concept set; very likely a vocabulary/code-set issue."
                    (some :flag annotated)
                    "Some rules drop >=90% of subjects in one step. Verify each is intentional; consider relaxing or splitting into smaller rules to expose the source of attrition."
                    :else
                    "Attrition looks reasonable — gradual drop across rules.")}))))
          (.catch (fn [e]
                    {:error (str "WebAPI request failed: " (or (.-message e) e))}))))))

(def tool
  {:name "summarise_attrition"
   :description "Pull per-inclusion-rule person counts and identify rules that drop ≥90% of subjects in one step (likely too restrictive) or eliminate everyone (broken concept set). Use this AFTER get_cohort_generation_summary when the population is lower than expected — surfaces WHICH inclusion rule is the bottleneck."
   :schema schema
   :run run})
