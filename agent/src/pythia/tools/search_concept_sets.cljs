(ns pythia.tools.search-concept-sets
  "search_existing_concept_sets tool — lists /conceptset, scores each against
   the query (name + description), returns the top hits. Port of the JVM
   trexsql.agent.tools.search-concept-sets."
  (:require [clojure.string :as str]
            [pythia.tools.search-util :as su]))

(def ^:private default-max-results 10)

(def schema
  {:type "object"
   :properties {:query {:type "string" :description "Clinical concept group to search for in concept-set names/descriptions (e.g. 'statins', 'metformin', 'NSAIDs')."}
                :limit {:type "number" :description "Max results to return (default 10)"}}
   :required ["query"]})

(defn run [args ctx]
  (let [query (str (or (:query args) ""))
        limit (or (:limit args) default-max-results)
        auth (su/forward-auth ctx)]
    (if (str/blank? query)
      (js/Promise.resolve {:results [] :note "empty query"})
      (-> (su/list-entities "/conceptset" auth)
          (.then (fn [body]
                   (let [results (su/ranked-results query (su/extract-content body) limit su/default-result)]
                     {:results results
                      :note (when (empty? results)
                              (str "No existing concept set name or description matched any of the "
                                   "query terms (" query ")."))})))
          (.catch (fn [e]
                    {:results [] :note (str "WebAPI list failed: " (or (.-message e) e))}))))))

(def tool
  {:name "search_existing_concept_sets"
   :description "Look up concept sets the user has already saved on the server. Call this BEFORE proposing create_standalone_concept_set, so you can suggest reusing an existing set when the user's request closely matches one (matchScore >= 6)."
   :schema schema
   :run run})
