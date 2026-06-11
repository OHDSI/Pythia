(ns pythia.tools.search-concepts
  "search_concepts tool — POSTs WebAPI's vocabulary search endpoint
   (POST /WebAPI/vocabulary/{sourceKey}/search) and shapes the hit set the
   same way the JVM tool does: STANDARD_CONCEPT='S' filter, per-result
   confidence/flags, and a top-level ambiguity summary. The end user's bearer
   (ctx :auth) is forwarded so per-source permissions are honoured."
  (:require [clojure.string :as str]
            [pythia.webapi :as webapi]))

(def ^:private max-results 15)

(def ^:private domain-enum
  ["Condition" "Drug" "Procedure" "Measurement"
   "Observation" "Visit" "Device" "Specimen"])

(def schema
  {:type "object"
   :properties {:query  {:type "string"}
                :domain {:type "string" :enum domain-enum}}
   :required ["query"]})

(defn- standard-concept? [row]
  (= "S" (str (or (:STANDARD_CONCEPT row) (:standardConcept row)))))

(defn- to-result [row]
  {:conceptId   (or (:CONCEPT_ID row) (:conceptId row))
   :conceptName (or (:CONCEPT_NAME row) (:conceptName row))
   :domain      (or (:DOMAIN_ID row) (:domainId row))
   :vocabulary  (or (:VOCABULARY_ID row) (:vocabularyId row))
   :standard    (or (:STANDARD_CONCEPT row) (:standardConcept row))
   :source      "webapi"})

(defn- annotate-confidence
  "Per-result :confidence + :flags based on the full hit set."
  [results query]
  (let [n (count results)
        vocabs (set (map :vocabulary results))
        domains (set (map :domain results))
        q (str/lower-case (str query))]
    (mapv
     (fn [r idx]
       (let [name-lower (str/lower-case (str (:conceptName r)))
             exact? (= name-lower q)
             prefix? (or exact? (str/starts-with? name-lower q))
             standard? (= "S" (str (:standard r)))
             flags (cond-> []
                     (not standard?)
                     (conj "non-standard-concept")

                     (and (> n 5) (> (count vocabs) 1))
                     (conj (str "vocabulary-spread: " (str/join "," vocabs)))

                     (and (> n 5) (> (count domains) 1))
                     (conj (str "domain-spread: " (str/join "," domains)))

                     (and (= "Condition" (:domain r))
                          (contains? vocabs "ICD10CM"))
                     (conj "icd10cm-also-matched: prefer SNOMED for OMOP standard")

                     (and (= "Drug" (:domain r))
                          (contains? vocabs "NDC"))
                     (conj "ndc-also-matched: prefer RxNorm Ingredient")

                     (and (= idx 0) (not exact?) (not prefix?) (> n 3))
                     (conj "weak-name-match: top hit doesn't start with query"))
             confidence (cond
                          (and exact? standard?) :high
                          (and prefix? standard? (empty? flags)) :high
                          (or (not standard?) (>= (count flags) 2)) :low
                          :else :medium)]
         (assoc r :confidence confidence :flags flags)))
     results
     (range))))

(defn- corpus-ambiguity
  "Top-level hit-set summary + cross-vocabulary divergence warnings."
  [results]
  (let [vocabs (set (map :vocabulary results))
        domains (set (map :domain results))
        n-low (count (filter #(= :low (:confidence %)) results))]
    (cond-> {:n-results (count results)
             :vocabularies (vec vocabs)
             :domains (vec domains)
             :low-confidence-count n-low}

      (and (contains? vocabs "ICD10CM") (contains? vocabs "SNOMED"))
      (assoc :icd-snomed-divergence?
             "Both ICD and SNOMED matched — these only round-trip identically ~25% of the time. Prefer SNOMED for OMOP-standard cohorts.")

      (and (contains? vocabs "NDC") (contains? vocabs "RxNorm"))
      (assoc :ndc-rxnorm-divergence?
             "Both NDC and RxNorm matched — prefer RxNorm Ingredient for portable cohorts."))))

(defn- call-webapi
  "POST <base>/vocabulary/<source-key>/search, forwarding the bearer.
   Resolves a Promise of the parsed rows (or nil on non-200)."
  [source-key query domain auth]
  (webapi/request "POST" (str "/vocabulary/" source-key "/search")
                  {:body {:QUERY query
                          :DOMAIN_ID (when (and domain (not (str/blank? domain))) [domain])}
                   :auth auth}))

(defn run
  "Tool entrypoint. `args` is {:query :domain}; `ctx` carries
   {:source-key :auth}. Returns a Promise of the result map."
  [args ctx]
  (let [{:keys [query domain]} args
        source-key (or (:source-key ctx) "EUNOMIA")
        auth (:auth ctx)]
    (if (str/blank? (str query))
      (js/Promise.resolve {:results [] :note "empty query"})
      (-> (call-webapi source-key (str query) domain auth)
          (.then (fn [raw]
                   (if (nil? raw)
                     {:results [] :note (str "WebAPI vocabulary search returned no body for source " source-key)}
                     (let [filtered (->> raw
                                         (filter standard-concept?)
                                         (take max-results)
                                         (map to-result)
                                         vec)
                           annotated (annotate-confidence filtered (str query))]
                       {:results annotated
                        :ambiguity (corpus-ambiguity annotated)
                        :note (when (empty? annotated)
                                (str "WebAPI returned " (count raw) " row(s) but none matched STANDARD_CONCEPT='S'"
                                     (when domain (str " AND DOMAIN_ID=" domain))))}))))
          (.catch (fn [e]
                    {:results [] :note (str "WebAPI vocabulary search failed: "
                                            (or (.-message e) e))}))))))

(def tool
  {:name "search_concepts"
   :description "Search OMOP standard concepts in the local vocabulary by query and optional domain filter. Returns up to 15 Standard Concepts. Each result carries a `:confidence` (`:high|:medium|:low`) and optional `:flags` listing concrete concerns (vocabulary-spread, ICD↔SNOMED divergence, weak-name-match). The top-level `:ambiguity` summarizes the hit set and warns about cross-vocabulary divergence. When picking a `:low`-confidence concept or any with flags, call `verify_concept_mapping` to re-check before adding it to a proposal."
   :schema schema
   :run run})
