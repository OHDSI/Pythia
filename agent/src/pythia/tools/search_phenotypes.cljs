(ns pythia.tools.search-phenotypes
  "search_phenotypes tool — queries four phenotype sources in parallel and merges
   the results: the bundled OHDSI Phenotype Library index, the live HDR UK
   Phenotype Library API, PheKB, and the OHDSI Forums (Discourse). Each source
   returns the unified item shape from pythia.phenotype-sources and is fail-soft.

   Port of the JVM trexsql.agent.tools.search-phenotypes, with the OHDSI Library
   ranking + :circe-summary restored and HDR UK added."
  (:require [clojure.string :as str]
            [pythia.phenotype-sources :as ps]))

(def ^:private per-source-limit 5)
(def ^:private overall-limit 20)
(def ^:private sources ["ohdsi-library" "hdruk" "phekb" "forums"])

(def schema
  {:type "object"
   :properties {:query {:type "string" :description "Clinical condition or phenotype to search for"}}
   :required ["query"]})

(defn run
  "Tool entrypoint. Args: {:query \"clinical condition\"}."
  [args _ctx]
  (let [query (str (or (:query args) ""))]
    (if (str/blank? query)
      (js/Promise.resolve {:results [] :note "empty query"})
      (-> (js/Promise.all
           #js [(ps/search-ohdsi-library query per-source-limit)
                (ps/search-hdruk query per-source-limit)
                (ps/search-phekb query per-source-limit)
                (ps/search-forums query per-source-limit)])
          (.then (fn [results]
                   {:results (ps/merge-rank (apply concat (array-seq results)) overall-limit)
                    :sources sources}))
          (.catch (fn [_] {:results [] :sources sources}))))))

(def tool
  {:name "search_phenotypes"
   :description "Search the OHDSI Phenotype Library, the HDR UK Phenotype Library, PheKB, and the OHDSI Forums for validated phenotype definitions. Call after search_existing_cohorts when no existing cohort matches. OHDSI Phenotype Library hits (`:source \"ohdsi-library\"`, `:kind :omop-cohort`) are directly importable OMOP cohorts and include a `:circe-summary` (entry domains, # primary criteria, # inclusion rules, concept-set list, exit strategy) — use it to mimic canonical OHDSI patterns directly. HDR UK hits (`:kind :clinical-codelist`) are UK EHR codelists for reference. When a hit is the right template and you want the full Circe JSON (or HDR UK clinical codes), follow up with `get_reference_phenotype`."
   :schema schema
   :run run})
