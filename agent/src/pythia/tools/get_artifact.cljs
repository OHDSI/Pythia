(ns pythia.tools.get-artifact
  "get_artifact tool — fetch the full editable content of a saved artifact by
   kind + id. Port of the JVM trexsql.agent.tools.get-artifact."
  (:require [clojure.string :as str]
            [pythia.webapi :as webapi]))

(def schema
  {:type "object"
   :properties {:kind {:type "string"
                       :enum ["cohort" "concept_set" "feature_analysis"
                              "characterization" "pathway" "incidence_rate"]
                       :description "Artifact type. Use the same kind reported in the current-context system message."}
                :id   {:type "number"
                       :description "Artifact id. For 'open artifact' use the id from the current-context block; for cross-referenced artifacts use the id from a search_existing_* result."}}
   :required ["kind" "id"]})

(def ^:private kind->path
  {"cohort"           (fn [id] (str "/cohortdefinition/" id))
   "concept_set"      (fn [id] (str "/conceptset/" id "/expression"))
   "feature_analysis" (fn [id] (str "/feature-analysis/" id))
   "characterization" (fn [id] (str "/cohort-characterization/" id "/design"))
   "pathway"          (fn [id] (str "/pathway-analysis/" id))
   "incidence_rate"   (fn [id] (str "/ir/" id))})

(defn run [args ctx]
  (let [kind (str (or (:kind args) (get args "kind")))
        id (or (:id args) (get args "id"))
        path-fn (kind->path kind)
        auth (:auth ctx)]
    (cond
      (nil? path-fn)
      (js/Promise.resolve
       {:error (str "unknown kind: " kind
                    " (allowed: cohort, concept_set, feature_analysis, "
                    "characterization, pathway, incidence_rate)")})

      (nil? id)
      (js/Promise.resolve {:error "id is required"})

      :else
      (-> (webapi/request-status "GET" (path-fn id) {:auth auth})
          (.then (fn [{:keys [status body]}]
                   (cond
                     (= 200 status) {:kind kind :id id :artifact body}
                     (= 404 status) {:error (str kind " " id " not found")}
                     :else {:error (str "WebAPI returned HTTP " status
                                        " for " kind " " id)})))))))

(def tool
  {:name "get_artifact"
   :description "Fetch the FULL editable content of a saved artifact by kind + id. Use this BEFORE proposing edits to an existing artifact so you reason about its current state instead of overwriting blindly. Mandatory when the user is asking to modify the artifact currently open on screen — the conversation system message tells you what's open. Returns the full WebAPI definition under :artifact (or :error)."
   :schema schema
   :run run})
