(ns pythia.webapi
  "fetch-based WebAPI client, mirroring the JVM call-webapi/list-entities
   pattern: builds <base><path>, forwards the end user's bearer, parses JSON.
   Returns a Promise of keywordized JSON on 200, nil otherwise."
  (:require [clojure.string :as str]
            [pythia.config :as config]))

(defn- query-string [query]
  (when (seq query)
    (->> query
         (map (fn [[k v]]
                (str (js/encodeURIComponent (name k)) "="
                     (js/encodeURIComponent (str v)))))
         (str/join "&"))))

(defn request
  "Call WebAPI. `method` is e.g. \"GET\"/\"POST\"; `path` is appended to the
   configured base. Opts: :body (clj map, JSON-encoded), :auth (bearer string,
   forwarded as Authorization), :query (clj map -> query string).
   Resolves keywordized JSON on 200, nil on non-200 or error."
  [method path {:keys [body auth query]}]
  (let [qs (query-string query)
        url (str (config/webapi-url) path (when qs (str "?" qs)))
        headers (cond-> {"Accept" "application/json"}
                  body (assoc "Content-Type" "application/json")
                  auth (assoc "Authorization" auth))
        opts (cond-> {:method method
                      :headers (clj->js headers)}
               body (assoc :body (js/JSON.stringify (clj->js body))))]
    (-> (js/fetch url (clj->js opts))
        (.then (fn [resp]
                 (if (= 200 (unchecked-get resp "status"))
                   (-> (.json resp)
                       (.then (fn [j] (js->clj j :keywordize-keys true))))
                   nil)))
        (.catch (fn [_] nil)))))
