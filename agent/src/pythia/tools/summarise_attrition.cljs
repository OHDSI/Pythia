(ns pythia.tools.summarise-attrition
  "summarise_attrition tool — reads a generated cohort's inclusion report (the
   ATLAS Inclusion Report / attrition view) and flags suspicious drops.

   WebAPI endpoint: GET /cohortdefinition/{id}/report/{sourceKey}/inclusion?mode=
   (WebAPI 3; older versions served the same body at /report/{sourceKey}).
   mode 1 counts persons (best event), mode 0 counts qualifying events.

   `inclusionRuleStats` counts each rule on its own; the attrition ATLAS draws
   applies the rules in order. That sequential count comes from `treemapData`,
   whose leaves are bit strings (character i = rule i passed) with a size."
  (:require [clojure.string :as str]
            [pythia.webapi :as webapi]
            [pythia.tools.sources :as sources]))

(def schema
  {:type "object"
   :properties {:cohortId  {:type "number"}
                :sourceKey {:type "string" :description "Optional source key. Defaults to the chat session's source."}
                :mode {:type "string" :enum ["person" "event"]
                       :description "Count persons (default) or qualifying events."}}
   :required ["cohortId"]})

(defn- parse-id [raw]
  (cond (number? raw) (long raw)
        (string? raw) (let [n (js/parseInt (str/trim raw) 10)] (when-not (js/isNaN n) n))
        :else nil))

(defn- round1 [x] (js/parseFloat (.toFixed x 1)))

(defn- treemap-leaves
  "[[bits size] ...] from the report's treemapData (a JSON string or a map)."
  [treemap]
  (let [root (cond (string? treemap) (try (js->clj (js/JSON.parse treemap) :keywordize-keys true)
                                          (catch :default _ nil))
                   (map? treemap) treemap)]
    (when root
      (->> (tree-seq :children :children root)
           (remove :children)
           (keep (fn [n] (when (string? (:name n)) [(:name n) (or (:size n) 0)])))
           vec))))

(defn- remaining-after
  "Count still in after rules 1..k, for each k; nil without treemap leaves."
  [leaves n-rules]
  (when (seq leaves)
    (mapv (fn [k]
            (let [prefix (apply str (repeat (inc k) "1"))]
              (reduce + 0 (for [[bits size] leaves :when (str/starts-with? bits prefix)] size))))
          (range n-rules))))

(defn- annotate [rules base remaining]
  (vec
   (map-indexed
    (fn [i r]
      (let [cur (get remaining i)
            prev (if (zero? i) base (get remaining (dec i)))
            drop-pct (when (and (number? prev) (number? cur) (pos? prev))
                       (* 100.0 (/ (- prev cur) (double prev))))]
        (cond-> {:rule (inc i)
                 :name (:name r)
                 :satisfying-on-its-own (:countSatisfying r)
                 :percent-excluded-on-its-own (:percentExcluded r)}
          (number? cur) (assoc :remaining-after-rule cur)
          drop-pct (assoc :drop-from-previous-pct (round1 drop-pct))

          (and drop-pct (>= drop-pct 90))
          (assoc :flag "very large drop (>=90%) — verify the rule is intended to be this restrictive")

          (and (number? cur) (zero? cur))
          (assoc :flag "nobody left after this rule — it cannot be satisfied with the current concept sets or data")

          (and (number? base) (pos? base) (= base (:countSatisfying r)))
          (assoc :note "everyone satisfies this rule — it removes no one"))))
    rules)))

(defn- interpretation [annotated]
  (cond
    (some #(= 0 (:remaining-after-rule %)) annotated)
    "An inclusion rule eliminates ALL subjects. Review the offending rule and its concept set; very likely a vocabulary/code-set issue."
    (some :flag annotated)
    "Some rules drop >=90% of the remaining subjects in one step. Verify each is intentional; consider relaxing or splitting it to expose the source of attrition."
    :else
    "Explain the attrition rule by rule from remaining-after-rule (rules apply in order); satisfying-on-its-own is how many pass that rule ignoring the others."))

(defn- fetch-report [cohort-id source-key mode auth]
  (let [base (str "/cohortdefinition/" cohort-id "/report/" source-key)]
    (-> (webapi/request "GET" (str base "/inclusion") {:auth auth :query {:mode mode}})
        (.then (fn [report]
                 (or report
                     (webapi/request "GET" base {:auth auth :query {:mode mode}})))))))

(defn generated-source-keys
  "Promise of the source keys a cohort has a completed generation on."
  [cohort-id auth]
  (-> (js/Promise.all
       #js [(webapi/request "GET" (str "/cohortdefinition/" cohort-id "/info") {:auth auth})
            (sources/key-by-id auth)])
      (.then (fn [[info key-by-id]]
               (->> (when (sequential? info) info)
                    (filter #(= "COMPLETE" (str (:status %))))
                    (keep #(get key-by-id (get-in % [:id :sourceId])))
                    distinct
                    vec)))))

(defn run [args ctx]
  (let [cohort-id (parse-id (:cohortId args))
        source-key (or (some-> (:sourceKey args) str)
                       (:source-key ctx)
                       "EUNOMIA")
        mode (if (= "event" (:mode args)) 0 1)
        auth (:auth ctx)]
    (if (nil? cohort-id)
      (js/Promise.resolve {:error "cohortId is required (numeric)"})
      (-> (fetch-report cohort-id source-key mode auth)
          (.then
           (fn [report]
             (let [rules (vec (:inclusionRuleStats report))
                   summary (:summary report)
                   base (:baseCount summary)]
               (if (or (nil? report) (and (empty? rules) (not (pos? (or base 0)))))
                 (-> (generated-source-keys cohort-id auth)
                     (.then (fn [ks]
                              {:error (str "No inclusion report for cohort " cohort-id " on source " source-key
                                           (if (seq ks)
                                             (str ". It is generated on: " (str/join ", " ks)
                                                  " — call again with one of those as sourceKey.")
                                             " — has it been generated?"))})))
                 (let [remaining (remaining-after (treemap-leaves (:treemapData report)) (count rules))
                       annotated (annotate rules base remaining)]
                   {:cohortId cohort-id
                    :sourceKey source-key
                    :counting (if (= 1 mode) "persons" "events")
                    :initial-count base
                    :final-count (:finalCount summary)
                    :percent-matched (:percentMatched summary)
                    :rules annotated
                    :flagged (filterv :flag annotated)
                    :interpretation (if (empty? rules)
                                      "The cohort has no inclusion rules; everyone who qualifies on the entry event is in it."
                                      (interpretation annotated))})))))
          (.catch (fn [e]
                    {:error (str "WebAPI request failed: " (or (.-message e) e))}))))))

(def tool
  {:name "summarise_attrition"
   :description "Read a generated cohort's inclusion report — what ATLAS shows as the Inclusion Report / attrition view: the starting count, how many remain after each inclusion rule in order, how many pass each rule on its own, and the final count. Flags rules that drop ≥90% in one step, remove everyone, or remove no one. Use it to explain the inclusion report or attrition, or AFTER get_cohort_generation_summary when the population is lower than expected — it shows WHICH rule is the bottleneck."
   :schema schema
   :run run})
