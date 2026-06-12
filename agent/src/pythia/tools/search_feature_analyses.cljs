(ns pythia.tools.search-feature-analyses
  "search_existing_feature_analyses tool — lists /feature-analysis
   and ranks by query-term overlap on name + description."
  (:require [clojure.string :as str]
            [pythia.tools.search-util :as su]))

(def schema
  {:type "object"
   :properties {:query {:type "string" :description "Topic to search for in feature-analysis names/descriptions (e.g. 'demographics', 'condition era', 'drug exposure')."}
                :limit {:type "number" :description "Max results to return (default 10)"}}
   :required ["query"]})

(defn run [args ctx]
  (let [query (str (or (:query args) ""))
        limit (or (:limit args) 10)
        auth (su/forward-auth ctx)]
    (if (str/blank? query)
      (js/Promise.resolve {:results [] :note "empty query"})
      (-> (su/list-entities "/feature-analysis?size=100000" auth)
          (.then (fn [body]
                   (let [results (su/ranked-results query (su/extract-content body) limit su/default-result)]
                     {:results results
                      :note (when (empty? results)
                              (str "No existing feature analysis name or description matched any of the "
                                   "query terms (" query ")."))})))
          (.catch (fn [e]
                    {:results [] :note (str "WebAPI list failed: " (or (.-message e) e))}))))))

(def tool
  {:name "search_existing_feature_analyses"
   :description "Look up feature analyses (covariate definitions) already saved on the server. ALWAYS call this BEFORE create_characterization (a characterization needs at least one feature analysis attached) and before create_feature_analysis (so you can suggest reusing one)."
   :schema schema
   :run run})
