(ns pythia.phenotype-sources
  "Phenotype source adapters. One fn per source, each returning the unified item
   shape and (for live sources) fail-soft (errors resolve to []).

   Sources:
   - OHDSI Phenotype Library  (`ohdsi-library`, :omop-cohort)  — bundled EDN index
     + GitHub-raw cohort JSON. Ranking + :circe-summary ported verbatim from the
     JVM trexsql.agent.tools.search-phenotypes / get-reference-phenotype.
   - HDR UK Phenotype Library (`hdruk`, :clinical-codelist)    — live public API.
   - PheKB                    (`phekb`, :community)            — live HTTP.
   - OHDSI Forums (Discourse) (`forums`, :community)           — live HTTP.

   Unified item shape:
     {:source :id :name :description :url :kind
      :status :tags :circe-summary :score}   ; last four optional"
  (:require [clojure.string :as str]
            [cljs.reader :as reader]
            [pythia.http :as http]
            [pythia.resources :as resources]))

(def ^:private per-source-limit 5)

;; ---------------------------------------------------------------------------
;; OHDSI Phenotype Library (bundled index)
;; ---------------------------------------------------------------------------

(def ^:private index-cache (atom nil))

(defn reset-cache! "Test hook: clear the cached cohorts index." [] (reset! index-cache nil))

(defn load-index
  "Read + parse phenotype-library/cohorts-index.edn once, cache it. Returns a
   Promise of the index vector (empty on any failure — fail-soft)."
  []
  (if-let [c @index-cache]
    (js/Promise.resolve c)
    (-> (try (resources/read-text "phenotype-library/cohorts-index.edn")
             (catch :default _ (js/Promise.resolve nil)))
        (.then (fn [text]
                 (let [idx (if (str/blank? (str text))
                             []
                             (try (vec (reader/read-string text))
                                  (catch :default e
                                    (js/console.warn "[phenotype-sources] cohorts-index.edn parse failed:"
                                                     (or (.-message e) e))
                                    [])))]
                   (reset! index-cache idx)
                   idx)))
        (.catch (fn [_] [])))))

(defn index-by-id
  "Map of :id -> entry for the given index."
  [index]
  (into {} (map (juxt :id identity)) index))

(defn- match-all-terms?
  "Case-insensitive: every word in `query` appears somewhere in `text`. (JVM port.)"
  [query text]
  (let [t (str/lower-case (or text ""))
        terms (->> (str/split (str/lower-case (or query "")) #"\s+")
                   (remove str/blank?))]
    (and (seq terms)
         (every? #(str/includes? t %) terms))))

(defn- status-rank
  "Sort accepted/peer-reviewed cohorts ahead of pending/withdrawn. (JVM port.)"
  [status]
  (case (or status "")
    "Accepted" 0
    "Pending peer review" 1
    "Pending" 2
    "Prediction" 3
    "Withdrawn" 9
    "Deprecated" 9
    5))

(defn circe-summary
  "Per-hit logic summary. Map shape ported verbatim from the JVM
   search-phenotype-library :circe-summary."
  [c]
  {:entry-domains (:entry-domains c)
   :n-primary-criteria (:n-primary-criteria c)
   :n-inclusion-rules (:n-inclusion-rules c)
   :n-concept-sets (:n-concept-sets c)
   :concept-sets (:concept-sets c)
   :exit-strategy (:exit-strategy c)
   :censor-criteria? (:censor-criteria? c)
   :primary-event-limit (:primary-event-limit c)
   :expression-limit (:expression-limit c)})

(defn- library-url
  "Human GitHub URL for a cohort id (JVM uses main/inst/cohorts/<id>.json)."
  [id]
  (str "https://github.com/OHDSI/PhenotypeLibrary/blob/main/inst/cohorts/" id ".json"))

(defn- ->ohdsi-item
  "Map an index entry to the unified item shape. :score is the status rank
   inverted so higher = better (Accepted highest), preserving the JVM order."
  [c]
  {:source "ohdsi-library"
   :id (:id c)
   :name (:name c)
   :description (:description c)
   :url (library-url (:id c))
   :kind :omop-cohort
   :status (:status c)
   :tags (:tags c)
   :circe-summary (circe-summary c)
   :score (- 100 (status-rank (:status c)))})

(defn rank-ohdsi-library
  "Filter + rank the bundled index for `query`, top `n`. Mirrors the JVM
   search-phenotype-library: match-all-terms? over name/name-long/description/
   hashtag, then sort by (status-rank, :id), take n. Returns unified items."
  [index query n]
  (->> index
       (filter (fn [c]
                 (or (match-all-terms? query (:name c))
                     (match-all-terms? query (:name-long c))
                     (match-all-terms? query (:description c))
                     (match-all-terms? query (:hashtag c)))))
       (sort-by (juxt #(status-rank (:status %)) :id))
       (take n)
       (mapv ->ohdsi-item)))

(defn search-ohdsi-library
  "Load the bundled index (cached) and rank for `query`. Returns a Promise of
   unified items. Fail-soft (missing/unparseable index -> [])."
  ([query] (search-ohdsi-library query per-source-limit))
  ([query n]
   (-> (load-index)
       (.then (fn [index] (rank-ohdsi-library index query n)))
       (.catch (fn [_] [])))))

;; ---------------------------------------------------------------------------
;; HDR UK Phenotype Library (live public API)
;; ---------------------------------------------------------------------------

(def hdruk-base "https://phenotypes.healthdatagateway.org")

(defn- ->hdruk-item [r]
  {:source "hdruk"
   :id (:phenotype_id r)
   :name (:name r)
   :description (some-> (:publications r) first :details)
   :url (str hdruk-base "/phenotypes/" (:phenotype_id r))
   :kind :clinical-codelist})

(defn search-hdruk
  "Live HDR UK search: GET /api/v1/phenotypes/?search=<q> (page 1). Returns a
   Promise of up to `n` unified items. Fail-soft: any error/non-200 -> []."
  ([query] (search-hdruk query per-source-limit))
  ([query n]
   (-> (http/get-json (str hdruk-base "/api/v1/phenotypes/")
                      {:query {:search query}
                       :headers {"Accept" "application/json"}})
       (.then (fn [{:keys [status body]}]
                (if (= 200 status)
                  (->> (or (:data body) [])
                       (take n)
                       (mapv ->hdruk-item))
                  [])))
       (.catch (fn [_] [])))))

;; ---------------------------------------------------------------------------
;; PheKB (live HTTP, community)
;; ---------------------------------------------------------------------------

(def ^:private phekb-url
  "https://phekb.org/services/phenotypes/views/phenotype_table.json")

(defn search-phekb
  "Live PheKB search. Returns a Promise of up to `n` unified community items.
   Fail-soft. Behavior matches the prior search_phenotypes PheKB path."
  ([query] (search-phekb query per-source-limit))
  ([query n]
   (-> (http/get-json phekb-url
                      {:query {:display_id "services_1"}
                       :headers {"Accept" "application/json"}})
       (.then (fn [{:keys [status body]}]
                (if (= 200 status)
                  (->> (or body [])
                       (filter (fn [row]
                                 (match-all-terms?
                                  query (str (:title row) " " (:description row)))))
                       (take n)
                       (mapv (fn [row]
                               {:source "phekb"
                                :id (or (:url row) (:title row))
                                :name (or (:title row) "Untitled")
                                :description (or (:description row) "")
                                :url (or (:url row) "https://phekb.org")
                                :kind :community})))
                  [])))
       (.catch (fn [_] [])))))

;; ---------------------------------------------------------------------------
;; OHDSI Forums / Discourse (live HTTP, community)
;; ---------------------------------------------------------------------------

(def ^:private discourse-url "https://forums.ohdsi.org/search.json")

(defn search-forums
  "Live OHDSI Forums (Discourse) search. Returns a Promise of up to `n` unified
   community items. Fail-soft. Behavior matches the prior search_phenotypes path."
  ([query] (search-forums query per-source-limit))
  ([query n]
   (-> (http/get-json discourse-url
                      {:query {:q query}
                       :headers {"Accept" "application/json"}})
       (.then (fn [{:keys [status body]}]
                (if (= 200 status)
                  (let [topics (or (:topics body) [])
                        posts (or (:posts body) [])
                        post-by-topic (into {} (map (juxt :topic_id identity) posts))]
                    (->> topics
                         (take n)
                         (mapv (fn [topic]
                                 (let [post (post-by-topic (:id topic))]
                                   {:source "forums"
                                    :id (:id topic)
                                    :name (:title topic)
                                    :description (or (:blurb post) "")
                                    :url (str "https://forums.ohdsi.org/t/"
                                              (:slug topic) "/" (:id topic))
                                    :kind :community})))))
                  [])))
       (.catch (fn [_] [])))))

;; ---------------------------------------------------------------------------
;; Merge / rank
;; ---------------------------------------------------------------------------

(defn merge-rank
  "Concat already-per-source-ranked items, sort by :score desc (nil last),
   cap to top `n` overall."
  [items n]
  (->> items
       (sort-by #(or (:score %) 0) >)
       (take n)
       vec))
