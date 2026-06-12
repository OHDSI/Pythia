(ns pythia.tools.search-existing-cohorts
  "search_existing_cohorts tool — lists /cohortdefinition, scores each against
   the query, returns the top hits. Port of the JVM
   trexsql.agent.tools.search-existing-cohorts."
  (:require [clojure.string :as str]
            [pythia.tools.search-util :as su]))

(def ^:private max-results 5)

(def schema
  {:type "object"
   :properties {:query {:type "string" :description "Clinical condition or phenotype to look for in existing cohort names/descriptions"}}
   :required ["query"]})

(defn run [args ctx]
  (let [query (str (or (:query args) ""))
        auth (su/forward-auth ctx)]
    (if (str/blank? query)
      (js/Promise.resolve {:results [] :note "empty query"})
      (-> (su/list-entities "/cohortdefinition" auth)
          (.then (fn [body]
                   (let [results (su/ranked-results query (su/extract-content body) max-results su/default-result)]
                     {:results results
                      :note (when (empty? results)
                              (str "No existing cohort name or description matched any of the "
                                   "query terms (" query ")."))})))
          (.catch (fn [e]
                    {:results [] :note (str "WebAPI list failed: " (or (.-message e) e))}))))))

(def tool
  {:name "search_existing_cohorts"
   :description "Look up cohorts the user has already defined in this WebAPI instance. ALWAYS call this BEFORE building a new cohort from scratch — if a strong match exists (matchScore >= 6), suggest reusing it (by id+name) instead of redefining."
   :schema schema
   :run run})
