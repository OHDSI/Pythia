(ns pythia.tools.search-pathways
  "search_existing_pathways tool — lists /pathway-analysis and ranks by
   query-term overlap on name + description."
  (:require [clojure.string :as str]
            [pythia.tools.search-util :as su]))

(def schema
  {:type "object"
   :properties {:query {:type "string" :description "Topic to search for in pathway-analysis names/descriptions (e.g. 'antidiabetic sequencing', 'opioid initiation')."}
                :limit {:type "number" :description "Max results to return (default 10)"}}
   :required ["query"]})

(defn run [args ctx]
  (let [query (str (or (:query args) ""))
        limit (or (:limit args) 10)
        auth (su/forward-auth ctx)]
    (if (str/blank? query)
      (js/Promise.resolve {:results [] :note "empty query"})
      (-> (su/list-entities "/pathway-analysis?size=10000" auth)
          (.then (fn [body]
                   (let [results (su/ranked-results query (su/extract-content body) limit su/default-result)]
                     {:results results
                      :note (when (empty? results)
                              (str "No existing pathway analysis name or description matched any of the "
                                   "query terms (" query ")."))})))
          (.catch (fn [e]
                    {:results [] :note (str "WebAPI list failed: " (or (.-message e) e))}))))))

(def tool
  {:name "search_existing_pathways"
   :description "Look up pathway analyses already saved on the server. Call before proposing create_pathway."
   :schema schema
   :run run})
