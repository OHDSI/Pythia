(ns pythia.tools.sources
  "Source-id → source-key lookup shared by the result-reading tools. WebAPI's
   generation records (/cohortdefinition/{id}/info, /ir/{id}/info) name the
   source by numeric id only, so matching them to a request's source key, or
   telling the user where a result does exist, needs /source/sources."
  (:require [pythia.webapi :as webapi]))

(defn key-by-id
  "Promise of {sourceId sourceKey} for every source the user can see ({} when
   WebAPI returns nothing)."
  [auth]
  (-> (webapi/request "GET" "/source/sources" {:auth auth})
      (.then (fn [sources]
               (into {} (for [s (when (sequential? sources) sources)
                              :when (and (:sourceId s) (:sourceKey s))]
                          [(:sourceId s) (:sourceKey s)]))))))
