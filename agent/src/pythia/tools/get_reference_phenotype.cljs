(ns pythia.tools.get-reference-phenotype
  "get_reference_phenotype tool — returns the full Circe JSON body of a single
   OHDSI/PhenotypeLibrary cohort by id, fetched from raw.githubusercontent.com
   (pinned to v3.37.0). Port of the JVM trexsql.agent.tools.get-reference-phenotype.

   ;; TODO(get_reference_phenotype): the JVM tool first tries an on-disk read
   ;; (BAO_PHENOTYPE_LIBRARY_PATH / project-relative cohorts dir) and enriches
   ;; the response with :name/:description/:status/:tags/:summary pulled from the
   ;; bundled EDN index (`phenotype-library/cohorts-index.edn`). Both are
   ;; in-process resources with no HTTP equivalent, so ONLY the GitHub-raw fetch
   ;; path is ported here; index-derived metadata fields are omitted (nil).
   ;; Routing the in-process index/disk path is a later step."
  (:require [clojure.string :as str]
            [pythia.http :as http]))

(def ^:private library-tag "v3.37.0")

(def schema
  {:type "object"
   :properties {:cohortId {:type "number"}}
   :required ["cohortId"]})

(defn- parse-id [raw]
  (cond
    (number? raw) (long raw)
    (string? raw) (let [n (js/parseInt (str/trim raw) 10)]
                    (when-not (js/isNaN n) n))
    :else nil))

(defn- github-url [id]
  (str "https://raw.githubusercontent.com/OHDSI/PhenotypeLibrary/"
       library-tag "/inst/cohorts/" id ".json"))

(defn run
  "Tool entrypoint. Args: {:cohortId 1234}."
  [args _ctx]
  (let [id (parse-id (:cohortId args))]
    (if (nil? id)
      (js/Promise.resolve {:error "cohortId is required (numeric)"})
      (-> (http/get-json (github-url id) {})
          (.then (fn [{:keys [status body]}]
                   (if (= 200 status)
                     {:cohortId id
                      :tag library-tag
                      :body body
                      :url (str "https://github.com/OHDSI/PhenotypeLibrary/blob/"
                                library-tag "/inst/cohorts/" id ".json")}
                     {:error (str "cohort " id " not found via GitHub")
                      :tag library-tag})))
          (.catch (fn [e]
                    {:error (str "get_reference_phenotype failed: " (or (.-message e) e))
                     :tag library-tag}))))))

(def tool
  {:name "get_reference_phenotype"
   :description "Fetch the full Circe JSON definition of an OHDSI Phenotype Library cohort by numeric cohortId (pinned to library v3.37.0). Returns the cohort body to mimic canonical patterns. Use after search_phenotypes surfaces a Phenotype Library hit."
   :schema schema
   :run run})
