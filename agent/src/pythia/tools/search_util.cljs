(ns pythia.tools.search-util
  "Shared helpers for search_existing_* tools that list a WebAPI collection
   and score by query-token overlap with name + description. Port of the JVM
   trexsql.agent.tools._search-util."
  (:require [clojure.string :as str]
            [pythia.config :as config]
            [pythia.webapi :as webapi]))

(defn webapi-base [] (config/webapi-url))

(defn forward-auth
  "On the JVM this pulls the bearer off the inbound ring request. In CLJS the
   bearer is already forwarded through ctx, so this just reads :auth."
  [ctx]
  (:auth ctx))

(defn- normalize [s] (str/lower-case (str (or s ""))))

(defn score-match
  "Tiny TF-style scorer: count how many query terms appear in the entity's
   name (heavier weight) or description. Returns an integer score."
  [query entity]
  (let [q (normalize query)
        terms (->> (str/split q #"\s+") (remove str/blank?) distinct)
        name-text (normalize (or (:name entity) (get entity "name")))
        desc-text (normalize (or (:description entity) (get entity "description")))]
    (reduce
     (fn [score t]
       (cond-> score
         (str/includes? name-text t) (+ 3)
         (str/includes? desc-text t) (+ 1)))
     0
     terms)))

(defn list-entities
  "GET <base><path> with the user's bearer forwarded. Resolves a Promise of
   the parsed JSON body on 200, [] otherwise."
  [path auth]
  (-> (webapi/request "GET" path {:auth auth})
      (.then (fn [body] (or body [])))))

(defn extract-content
  "Some WebAPI list endpoints wrap results in a Spring Page `content` array.
   Accept either the wrapped or the bare-array shape."
  [body]
  (cond
    (sequential? body) body
    (map? body) (or (:content body) (get body "content") [])
    :else []))

(defn default-result [entity]
  {:id           (or (:id entity) (get entity "id"))
   :name         (or (:name entity) (get entity "name"))
   :description  (or (:description entity) (get entity "description"))
   :createdDate  (or (:createdDate entity) (get entity "createdDate"))
   :modifiedDate (or (:modifiedDate entity) (get entity "modifiedDate"))})

(defn ranked-results
  "Score `entities` against `query`, keep positive matches, take the top
   `limit`, and shape each into a result map via `result-fn`."
  [query entities limit result-fn]
  (->> entities
       (map (fn [e] [(score-match query e) e]))
       (filter (fn [[s _]] (pos? s)))
       (sort-by (comp - first))
       (take limit)
       (mapv (fn [[s e]] (assoc (result-fn e) :matchScore s)))))
