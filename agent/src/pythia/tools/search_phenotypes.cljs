(ns pythia.tools.search-phenotypes
  "search_phenotypes tool — queries PheKB and the OHDSI Forums (Discourse) in
   parallel, merges results, returns up to 5 hits per source. Port of the JVM
   trexsql.agent.tools.search-phenotypes.

   ;; TODO(search_phenotypes): the JVM tool also searches a bundled OHDSI
   ;; Phenotype Library v3.37.0 EDN index (`phenotype-library/cohorts-index.edn`
   ;; loaded from the classpath, with per-hit :circe-summary). That index is an
   ;; in-process resource with no HTTP equivalent, so the Phenotype-Library
   ;; source is NOT ported here. Only the two external-HTTP sources (PheKB,
   ;; Discourse) are replicated. Routing the in-process index path is a later
   ;; step (would need a globalThis/bundled-EDN equivalent)."
  (:require [clojure.string :as str]
            [pythia.http :as http]))

(def ^:private per-source-limit 5)

(def ^:private phekb-url
  "https://phekb.org/services/phenotypes/views/phenotype_table.json")
(def ^:private discourse-url "https://forums.ohdsi.org/search.json")

(def schema
  {:type "object"
   :properties {:query {:type "string"}}
   :required ["query"]})

(defn- match-all-terms?
  "Case-insensitive: every word in `query` appears somewhere in `text`."
  [query text]
  (let [t (str/lower-case (or text ""))
        terms (->> (str/split (str/lower-case (or query "")) #"\s+")
                   (remove str/blank?))]
    (and (seq terms)
         (every? #(str/includes? t %) terms))))

(defn- search-phekb [query]
  (-> (http/get-json phekb-url
                     {:query {:display_id "services_1"}
                      :headers {"Accept" "application/json"}})
      (.then (fn [{:keys [status body]}]
               (if (= 200 status)
                 (->> (or body [])
                      (filter (fn [row]
                                (let [text (str (:title row) " " (:description row))]
                                  (match-all-terms? query text))))
                      (take per-source-limit)
                      (mapv (fn [row]
                              {:source "PheKB"
                               :title (or (:title row) "Untitled")
                               :description (or (:description row) "")
                               :url (or (:url row) "https://phekb.org")})))
                 [])))
      (.catch (fn [_] []))))

(defn- search-discourse [query]
  (-> (http/get-json discourse-url
                     {:query {:q query}
                      :headers {"Accept" "application/json"}})
      (.then (fn [{:keys [status body]}]
               (if (= 200 status)
                 (let [topics (or (:topics body) [])
                       posts (or (:posts body) [])
                       post-by-topic (into {} (map (juxt :topic_id identity) posts))]
                   (->> topics
                        (take per-source-limit)
                        (mapv (fn [topic]
                                (let [post (post-by-topic (:id topic))]
                                  {:source "OHDSI Forums"
                                   :title (:title topic)
                                   :description (or (:blurb post) "")
                                   :url (str "https://forums.ohdsi.org/t/"
                                             (:slug topic) "/" (:id topic))})))))
                 [])))
      (.catch (fn [_] []))))

(defn run
  "Tool entrypoint. Args: {:query \"clinical condition\"}."
  [args _ctx]
  (let [query (str (or (:query args) ""))]
    (if (str/blank? query)
      (js/Promise.resolve {:results [] :note "empty query"})
      (-> (js/Promise.all #js [(search-phekb query) (search-discourse query)])
          (.then (fn [pair]
                   (let [[phekb discourse] (array-seq pair)]
                     {:results (vec (concat phekb discourse))})))))))

(def tool
  {:name "search_phenotypes"
   :description "Search published phenotype definitions for a clinical condition across PheKB and the OHDSI Forums. Returns up to 5 hits per source (title, description, URL). Use when looking for existing algorithm definitions or community discussion to base a cohort on."
   :schema schema
   :run run})
