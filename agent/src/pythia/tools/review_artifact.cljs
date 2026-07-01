(ns pythia.tools.review-artifact
  "review_artifact tool — fetch a saved artifact's full current definition
   (like get_artifact) and run kind-specific structural checks against the
   stated `intent`, before the agent declares the artifact done. The checks
   catch structural absence (no entry event, no exit strategy, missing
   references); they cannot catch wrong clinical logic — the model is
   instructed to weigh them alongside its own judgment."
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
    [(check "entry-event-present" (seq criteria) "PrimaryCriteria.CriteriaList (entry event)")
     (check "observation-window-set" (some? obs-window) "PrimaryCriteria.ObservationWindow")
     (check "inclusion-rules-present" (seq inclusion-rules)
            (str (count inclusion-rules) " inclusion rule(s) — informational, not all cohorts need them"))
     (check "exit-strategy-set" (some? end-strategy) "EndStrategy (exit criteria)")
     (check "all-concept-items-standard" (empty? non-standard)
            (if (seq non-standard)
              (str "non-Standard concepts used: " (str/join ", " non-standard))
              "all concept-set items use Standard concepts"))]))

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
        auth (:auth ctx)]
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
                     {:kind kind :id id :intent intent
                      :artifact body
                      :checks (checks-for kind body)
                      :instruction
                      "Cross-reference :checks against `intent` plus your own clinical judgment — the checks catch structural absence, not wrong clinical logic. If you find a real problem, propose a fix via the matching update_* tool; do not just mention it in prose."}

                     (= 404 status) {:error (str kind " " id " not found")}

                     :else {:error (str "WebAPI returned HTTP " status
                                        " for " kind " " id)})))))))

(def tool
  {:name "review_artifact"
   :description "Review a saved artifact (cohort, concept set, feature analysis, characterization, pathway, or incidence rate) against the stated `intent` before declaring it done. Fetches the full current definition (like get_artifact) and runs kind-specific structural checks (entry event present, Standard concepts used, observation window / exit logic set, required references present). Weigh the checks plus your own clinical judgment against `intent`; propose a fix via the matching update_* tool if something's wrong. Does NOT end your turn."
   :schema schema
   :run run})
