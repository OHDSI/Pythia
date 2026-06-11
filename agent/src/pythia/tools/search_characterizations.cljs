(ns pythia.tools.search-characterizations
  "search_existing_characterizations tool — lists /cohort-characterization
   and ranks by query-term overlap on name + description."
  (:require [clojure.string :as str]
            [pythia.tools.search-util :as su]))

(def schema
  {:type "object"
   :properties {:query {:type "string" :description "Topic to search for in characterization names/descriptions."}
                :limit {:type "number" :description "Max results to return (default 10)"}}
   :required ["query"]})

(defn run [args ctx]
  (let [query (str (or (:query args) ""))
        limit (or (:limit args) 10)
        auth (su/forward-auth ctx)]
    (if (str/blank? query)
      (js/Promise.resolve {:results [] :note "empty query"})
      (-> (su/list-entities "/cohort-characterization?size=10000" auth)
          (.then (fn [body]
                   (let [results (su/ranked-results query (su/extract-content body) limit su/default-result)]
                     {:results results
                      :note (when (empty? results)
                              (str "No existing characterization name or description matched any of the "
                                   "query terms (" query ")."))})))
          (.catch (fn [e]
                    {:results [] :note (str "WebAPI list failed: " (or (.-message e) e))}))))))

(def tool
  {:name "search_existing_characterizations"
   :description "Look up characterizations already saved on the server. Call before proposing create_characterization to suggest reusing an existing one."
   :schema schema
   :run run})
