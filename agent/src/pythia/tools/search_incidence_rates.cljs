(ns pythia.tools.search-incidence-rates
  "search_existing_incidence_rates tool — lists /ir/ and ranks by
   query-term overlap on name + description."
  (:require [clojure.string :as str]
            [pythia.tools.search-util :as su]))

(def schema
  {:type "object"
   :properties {:query {:type "string" :description "Topic to search for in incidence-rate names/descriptions."}
                :limit {:type "number" :description "Max results to return (default 10)"}}
   :required ["query"]})

(defn run [args ctx]
  (let [query (str (or (:query args) ""))
        limit (or (:limit args) 10)
        auth (su/forward-auth ctx)]
    (if (str/blank? query)
      (js/Promise.resolve {:results [] :note "empty query"})
      (-> (su/list-entities "/ir/" auth)
          (.then (fn [body]
                   (let [results (su/ranked-results query (su/extract-content body) limit su/default-result)]
                     {:results results
                      :note (when (empty? results)
                              (str "No existing incidence rate name or description matched any of the "
                                   "query terms (" query ")."))})))
          (.catch (fn [e]
                    {:results [] :note (str "WebAPI list failed: " (or (.-message e) e))}))))))

(def tool
  {:name "search_existing_incidence_rates"
   :description "Look up incidence-rate definitions already saved on the server. Call before proposing create_incidence_rate."
   :schema schema
   :run run})
