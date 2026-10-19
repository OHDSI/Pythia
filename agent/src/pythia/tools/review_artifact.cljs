(ns pythia.tools.review-artifact
  "review_artifact tool — fetch a saved artifact's full current definition
   (like get_artifact) and run kind-specific structural checks against the
   stated `intent`, before the agent declares the artifact done. The checks
   catch structural absence (no entry event, no exit strategy, missing
   references); they cannot catch wrong clinical logic — the model is
   instructed to weigh them alongside its own judgment.

   Two inputs come from outside the model's own account of its work: every
   concept-set item is looked up again in the session source's vocabulary,
   and the active plan's Goal / Success criteria (written before the work
   began) are returned next to `intent`, which is written after it."
  (:require [clojure.string :as str]
            [pythia.artifacts :as artifacts]
            [pythia.webapi :as webapi]))

(def schema
  {:type "object"
   :properties
   {:kind {:type "string"
           :enum ["cohort" "concept_set" "feature_analysis"
                  "characterization" "pathway" "incidence_rate"]
           :description "Artifact type — same kinds as get_artifact."}
    :id   {:type "number" :description "Artifact id to review."}
    :intent {:type "string"
             :description "Your own one-sentence restatement of what this artifact was supposed to achieve. State it before checking against it."}}
   :required ["kind" "id" "intent"]})

(defn- check [id pass detail] {:id id :pass (boolean pass) :detail detail})

;; Circe carries cardinality per criterion: Occurrence {:Type 0 :Count 0} is
;; "exactly zero" (an exclusion), {:Type 2 :Count 1} is "at least one" (a
;; requirement). A rule that says "exclude prior GI bleed" while every criterion
;; requires the event builds a cohort of exactly the people it claims to drop,
;; and every screen still shows the reassuring rule name — so the only way to
;; catch it is to read the encoding, not the label.
(def ^:private negation-re
  #"(?i)\b(exclud\w*|exclusion\w*|without|no prior|no history|no previous|no known|never|absence of|rule out)\b")

(defn- requires-event? [criterion]
  (let [occ (:Occurrence criterion)
        t (:Type occ)
        c (:Count occ)]
    ;; Absent Occurrence is how the editor displays "at least one", so count it
    ;; as required here; criteria-renderable flags it, since circe-be cannot
    ;; render a criterion without one.
    (or (nil? occ) (and (= 2 t) (pos? (or c 0))) (and (= 1 t) (pos? (or c 0))))))

(defn- excludes-event? [criterion]
  (let [occ (:Occurrence criterion)]
    (and occ (= 0 (:Type occ)) (= 0 (or (:Count occ) 0)))))

(defn- rule-directions
  "Per-rule summary of what each rule actually requires vs excludes."
  [inclusion-rules]
  (for [r inclusion-rules
        :let [crits (get-in r [:expression :CriteriaList])
              required (count (filter requires-event? crits))
              excluded (count (filter excludes-event? crits))]]
    {:name (:name r)
     :required required
     :excluded excluded
     :named-as-exclusion (boolean (re-find negation-re (str (:name r))))}))

(defn- inverted-exclusions
  "Rules whose name states an absence but whose criteria all require the event."
  [dirs]
  (filter #(and (:named-as-exclusion %) (zero? (:excluded %)) (pos? (:required %))) dirs))

(defn- codeset-problems
  "CodesetIds referenced by criteria that resolve to nothing: either the concept
   set is not defined at all, or it is defined but empty. Both make the criterion
   match no one while the cohort still builds and generates."
  [body]
  (let [sets (into {} (for [cs (:ConceptSets body)]
                        [(:id cs) (count (get-in cs [:expression :items]))]))
        referenced (atom #{})]
    (letfn [(walk [x]
              (cond
                (map? x) (do (when-let [cid (:CodesetId x)] (swap! referenced conj cid))
                             (when-let [cid (:DrugCodesetId x)] (swap! referenced conj cid))
                             (doseq [v (vals x)] (walk v)))
                (sequential? x) (doseq [v x] (walk v))
                :else nil))]
      (walk body))
    (for [cid @referenced
          :let [n (get sets cid)]
          :when (or (nil? n) (zero? n))]
      (if (nil? n)
        (str "CodesetId " cid " is referenced but not defined")
        (str "CodesetId " cid " (\"" (some #(when (= cid (:id %)) (:name %)) (:ConceptSets body))
             "\") is referenced but has no concepts")))))

(defn- unrenderable-criteria
  "Correlated criteria (maps carrying :Criteria) without :Occurrence or
   :StartWindow. circe-be dereferences both unconditionally, so generation and
   the live preview fail with a NullPointerException."
  [body]
  (let [found (atom [])]
    (letfn [(walk [x path]
              (cond
                (map? x) (do (when (contains? x :Criteria)
                               (doseq [k [:Occurrence :StartWindow]
                                       :when (nil? (get x k))]
                                 (swap! found conj (str path " has no " (name k)))))
                             (doseq [[k v] x] (walk v (str path "." (name k)))))
                (sequential? x) (doseq [[i v] (map-indexed vector x)]
                                  (walk v (str path "[" i "]")))
                :else nil))]
      (walk body "expression"))
    @found))

(defn- cohort-checks [body]
  (let [criteria (get-in body [:PrimaryCriteria :CriteriaList])
        concept-sets (:ConceptSets body)
        obs-window (get-in body [:PrimaryCriteria :ObservationWindow])
        inclusion-rules (:InclusionRules body)
        end-strategy (:EndStrategy body)
        non-standard (for [cs concept-sets
                           item (get-in cs [:expression :items])
                           :let [c (:concept item)]
                           :when (and c (contains? c :STANDARD_CONCEPT)
                                      (not= "S" (:STANDARD_CONCEPT c)))]
                       (str (:CONCEPT_NAME c) " (concept set \"" (:name cs) "\")"))]
    (let [dirs (rule-directions inclusion-rules)
          inverted (inverted-exclusions dirs)
          codeset-issues (codeset-problems body)
          unrenderable (unrenderable-criteria body)
          mixed (filter #(and (pos? (:required %)) (pos? (:excluded %))) dirs)]
      [(check "entry-event-present" (seq criteria) "PrimaryCriteria.CriteriaList (entry event)")
       (check "observation-window-set" (some? obs-window) "PrimaryCriteria.ObservationWindow")
       (check "inclusion-rules-present" (seq inclusion-rules)
              (str (count inclusion-rules) " inclusion rule(s) — informational, not all cohorts need them"))
       (check "exit-strategy-set" (some? end-strategy) "EndStrategy (exit criteria)")
       (check "all-concept-items-standard" (empty? non-standard)
              (if (seq non-standard)
                (str "non-Standard concepts used: " (str/join ", " non-standard))
                "all concept-set items use Standard concepts"))
       ;; The encoding, not the label. Read this list against what the user asked
       ;; for: a criterion counted under "requires" keeps only patients who HAVE
       ;; that event.
       (check "rule-directions-readable" true
              (if (seq dirs)
                (str/join "; "
                          (for [d dirs]
                            (str "\"" (:name d) "\" requires " (:required d)
                                 " and excludes " (:excluded d) " criterion/criteria")))
                "no inclusion rules"))
       (check "exclusions-encoded-not-just-named" (empty? inverted)
              (if (seq inverted)
                (str "these rules are NAMED as exclusions but REQUIRE the event, which inverts them: "
                     (str/join ", " (map #(str "\"" (:name %) "\"") inverted))
                     " — re-propose with zero cardinality (add_criterion group=exclusion, "
                     "or add_inclusion_rule logicType=AT_MOST count=0)")
                "no rule is named as an exclusion while requiring its event"))
       ;; A rule cannot be half required and half excluded and still read
       ;; correctly to anyone scanning the definition; split it.
       (check "one-direction-per-rule" (empty? mixed)
              (if (seq mixed)
                (str "these rules mix required and excluded criteria: "
                     (str/join ", " (map #(str "\"" (:name %) "\"") mixed))
                     " — split them into one rule per direction")
                "each rule is entirely required or entirely excluded"))
       (check "all-codesets-resolvable" (empty? codeset-issues)
              (if (seq codeset-issues)
                (str/join "; " codeset-issues)
                "every referenced concept set exists and has concepts"))
       (check "criteria-renderable" (empty? unrenderable)
              (if (seq unrenderable)
                (str (str/join "; " unrenderable)
                     " — WebAPI cannot generate or preview this cohort. Re-proposing the rule "
                     "does not fix it; ask the user to change that criterion's occurrence count "
                     "in the cohort editor (e.g. to 2 and back to 1) and save.")
                "every criterion has an Occurrence and a StartWindow"))])))

(defn- concept-set-checks [body]
  (let [items (:items body)]
    [(check "items-present" (seq items) (str (count items) " item(s)"))]))

(defn- feature-analysis-checks [body]
  [(check "type-and-design-present" (and (some? (:type body)) (some? (:design body)))
          "type + design")])

;; Field names (:cohorts, :featureAnalyses, :targetCohorts, :eventCohorts,
;; :targetIds, :outcomeIds, :timeAtRisk) are best-effort guesses at the
;; WebAPI response shape, unverified against a live fetch (the dev stack
;; this targets is currently blocked). Confirm against a real response when
;; the stack is available and adjust if any name is wrong.
(defn- characterization-checks [body]
  [(check "has-cohort" (seq (:cohorts body)) (str (count (:cohorts body)) " cohort(s)"))
   (check "has-feature-analysis" (seq (:featureAnalyses body))
          (str (count (:featureAnalyses body)) " feature analysis/es"))])

(defn- pathway-checks [body]
  [(check "has-target-cohort" (seq (:targetCohorts body)) (str (count (:targetCohorts body)) " target cohort(s)"))
   (check "has-event-cohort" (seq (:eventCohorts body)) (str (count (:eventCohorts body)) " event cohort(s)"))])

(defn- incidence-rate-checks [body]
  [(check "has-target-ids" (seq (:targetIds body)) (str (count (:targetIds body)) " target id(s)"))
   (check "has-outcome-ids" (seq (:outcomeIds body)) (str (count (:outcomeIds body)) " outcome id(s)"))
   (check "time-at-risk-set" (some? (:timeAtRisk body)) "timeAtRisk window")])

;; ---------- live vocabulary ----------
;;
;; A saved concept set carries a snapshot of each concept (STANDARD_CONCEPT,
;; INVALID_REASON) from whichever vocabulary it was built against. Vocabularies
;; are re-released and differ per source, so the snapshot can say Standard while
;; the source being analysed has the concept deprecated, non-standard or absent
;; — and a criterion on such a concept quietly matches no one.

(defn- concept-items
  "Non-excluded concept-set items as {:id :name :set} — from a cohort's
   ConceptSets, or from a bare concept-set expression."
  [kind body]
  (let [sets (case kind
               "cohort" (for [cs (:ConceptSets body)]
                          [(:name cs) (get-in cs [:expression :items])])
               "concept_set" [[nil (:items body)]]
               [])]
    (for [[set-name items] sets
          item items
          :let [c (:concept item)
                id (:CONCEPT_ID c)]
          :when (and (number? id) (not (:isExcluded item)))]
      {:id id :name (:CONCEPT_NAME c) :set set-name})))

(defn vocabulary-issues
  "Concept-set items that the source vocabulary does not return as a valid
   Standard concept, given the concepts /lookup/identifiers returned for them.
   Pure — exposed for direct testing."
  [items found]
  (let [by-id (into {} (map (juxt :CONCEPT_ID identity)) found)]
    (for [{:keys [id name set]} items
          :let [c (get by-id id)
                label (str (or name "concept") " (" id
                           (when set (str ", concept set \"" set "\"")) ")")
                ;; WebAPI selects ISNULL(INVALID_REASON,'V'): "V" is valid.
                reason (some-> (:INVALID_REASON c) str str/trim)
                issue (cond
                        (nil? c)
                        (str label " is not in this source's vocabulary")

                        (not (contains? #{nil "" "V"} reason))
                        (str label " is invalid in this source's vocabulary (INVALID_REASON="
                             reason ")")

                        (not= "S" (:STANDARD_CONCEPT c))
                        (str label " is not Standard in this source's vocabulary (STANDARD_CONCEPT="
                             (:STANDARD_CONCEPT c) ")"))]
          :when issue]
      issue)))

(defn- vocabulary-check [items found]
  (let [id "concepts-current-in-source-vocabulary"]
    (if (nil? found)
      (assoc (check id true "NOT VERIFIED — the vocabulary lookup failed; only the saved snapshot was checked")
             :skipped true)
      (let [issues (vocabulary-issues items found)]
        (check id (empty? issues)
               (if (seq issues)
                 (str (str/join "; " issues)
                      " — the criterion matches no one on this source for these concepts; "
                      "replace them via search_concepts and the matching update tool")
                 (str "all " (count items) " concept(s) are valid Standard concepts in this source's vocabulary")))))))

(defn- lookup-concepts
  "POST /vocabulary/{sourceKey}/lookup/identifiers. Resolves the concepts the
   source returns (absent ids are simply missing), or nil when the call fails."
  [source-key ids auth]
  (webapi/request "POST" (str "/vocabulary/" source-key "/lookup/identifiers")
                  {:auth auth :body (vec ids)}))

;; ---------- plan goal ----------

(defn plan-goal
  "The active plan's \"## Goal\" and \"## Success criteria\" sections, or nil.
   Pure — exposed for direct testing."
  [plan]
  (let [doc (str (:document plan))
        section (fn [heading]
                  (some-> (re-find (re-pattern (str "## " heading "[^\\n]*\\n([\\s\\S]*?)(?=\\n## |$)")) doc)
                          second str/trim not-empty))
        goal (section "Goal")
        success (section "Success criteria")]
    (when (or goal success)
      (cond-> {}
        goal (assoc :goal goal)
        success (assoc :success-criteria success)))))

(defn checks-for
  "Kind-specific structural checks. Pure — exposed for direct testing."
  [kind body]
  (case kind
    "cohort" (cohort-checks body)
    "concept_set" (concept-set-checks body)
    "feature_analysis" (feature-analysis-checks body)
    "characterization" (characterization-checks body)
    "pathway" (pathway-checks body)
    "incidence_rate" (incidence-rate-checks body)
    []))

(defn run [args ctx]
  (let [kind (str (or (:kind args) ""))
        id (:id args)
        intent (some-> (:intent args) str str/trim)
        path-fn (artifacts/kind->path kind)
        auth (:auth ctx)
        source-key (or (:source-key ctx) "EUNOMIA")
        goal (plan-goal (:plan ctx))]
    (cond
      (nil? path-fn)
      (js/Promise.resolve
       {:error (str "unknown kind: " kind
                    " (allowed: cohort, concept_set, feature_analysis, "
                    "characterization, pathway, incidence_rate)")})

      (nil? id)
      (js/Promise.resolve {:error "id is required"})

      (str/blank? intent)
      (js/Promise.resolve
       {:error "intent is required — state in one sentence what this artifact was supposed to achieve before checking it"})

      :else
      (-> (webapi/request-status "GET" (path-fn id) {:auth auth})
          (.then (fn [{:keys [status body]}]
                   (cond
                     (= 200 status)
                     (let [items (concept-items kind body)]
                       (-> (if (seq items)
                             (lookup-concepts source-key (distinct (map :id items)) auth)
                             (js/Promise.resolve nil))
                           (.then
                            (fn [found]
                              (cond-> {:kind kind :id id :intent intent
                                       :artifact body
                                       :checks (cond-> (checks-for kind body)
                                                 (seq items) (conj (vocabulary-check items found)))
                                       :instruction
                                       (str "Cross-reference :checks against `intent` plus your own clinical judgment — the checks catch structural absence, not wrong clinical logic."
                                            (when goal
                                              " Judge the artifact against :plan-goal as well: it was written before the work began, while `intent` was written after it — where they disagree, :plan-goal is what the user agreed to.")
                                            " If you find a real problem, propose a fix via the matching update_* tool; do not just mention it in prose.")}
                                goal (assoc :plan-goal goal))))))

                     (= 404 status) {:error (str kind " " id " not found")}

                     :else {:error (str "WebAPI returned HTTP " status
                                        " for " kind " " id)})))))))

(def tool
  {:name "review_artifact"
   :description "Review a saved artifact (cohort, concept set, feature analysis, characterization, pathway, or incidence rate) against the stated `intent` before declaring it done. Fetches the full current definition (like get_artifact) and runs kind-specific structural checks (entry event present, Standard concepts used, observation window / exit logic set, required references present), re-checks every concept-set item against the source's live vocabulary, and returns the active plan's Goal / Success criteria as :plan-goal. Weigh the checks plus your own clinical judgment against `intent`; propose a fix via the matching update_* tool if something's wrong. Does NOT end your turn."
   :schema schema
   :run run})
