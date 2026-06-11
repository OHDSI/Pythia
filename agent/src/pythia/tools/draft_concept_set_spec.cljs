(ns pythia.tools.draft-concept-set-spec
  "draft_concept_set_spec tool — server-side scratchpad that records a
   concept-set SPEC (logic) before any concept-id resolution, echoes it back,
   and emits a mandated next-step hint. Pure (no HTTP). Port of the JVM
   trexsql.agent.tools.draft-concept-set-spec."
  (:require [clojure.string :as str]))

(def ^:private domain-enum
  ["Condition" "Drug" "Procedure" "Measurement"
   "Observation" "Visit" "Device" "Specimen"])

(def schema
  {:type "object"
   :properties {:name           {:type "string" :description "Clinical name for the concept set, e.g. 'Confirmatory T2DM treatment'."}
                :clinical_terms {:type "array" :items {:type "string"}
                                 :description "One or more clinical terms (e.g. ['metformin', 'sulfonylurea', 'insulin'])."}
                :domain         {:type "string" :enum domain-enum}
                :vocabulary     {:type "string" :description "Optional vocabulary hint (SNOMED, RxNorm, LOINC). Inferred from domain if omitted."}
                :include_descendants {:type "boolean"}
                :rationale      {:type "string" :description "Optional one-line clinical rationale."}}
   :required ["name" "clinical_terms"]})

(defn- normalize-terms [terms]
  (cond
    (sequential? terms) (->> terms (map str) (remove str/blank?) vec)
    (string? terms) [(str/trim terms)]
    :else []))

(defn- vocabulary-hint [domain]
  (case (str domain)
    "Condition"    "SNOMED"
    "Drug"         "RxNorm (Ingredient)"
    "Procedure"    "SNOMED / CPT4"
    "Measurement"  "LOINC"
    "Observation"  "SNOMED / LOINC"
    "Visit"        "Visit"
    "Device"       "SNOMED"
    "Specimen"     "SNOMED"
    "(no domain hint)"))

(defn run [args _ctx]
  (let [nm (str (or (:name args) ""))
        terms (normalize-terms (:clinical_terms args))
        domain (some-> (:domain args) str)
        vocabulary (or (some-> (:vocabulary args) str)
                       (vocabulary-hint domain))
        include-desc (boolean (:include_descendants args))
        rationale (some-> (:rationale args) str)]
    (cond
      (str/blank? nm)
      {:ok false :errors ["concept-set name is required (e.g. \"Confirmatory T2DM treatment\")"]}

      (empty? terms)
      {:ok false :errors ["clinical_terms is required (one or more clinical terms before concept-id resolution)"]}

      :else
      {:ok true
       :spec {:name nm
              :clinical_terms terms
              :vocabulary vocabulary
              :domain domain
              :include_descendants include-desc
              :rationale rationale}
       :next_steps
       [(str "Call search_concepts for each of: " (str/join ", " (map pr-str terms))
             (when domain (str " (DOMAIN_ID=" domain ")"))
             ".")
        "For each search_concepts result, prefer hits with :confidence :high. If the chosen pick has :confidence :low or any :flags, call verify_concept_mapping(conceptId, expectedDomain) before adding it to the concept set."
        (str "When all terms are resolved, propose the concept set via "
             (if include-desc
               "embed_concept_set_in_cohort or create_standalone_concept_set with includeDescendants=true."
               "the appropriate concept-set tool."))]})))

(def tool
  {:name "draft_concept_set_spec"
   :description "Record a concept-set SPEC (clinical name, list of clinical terms, target vocabulary, descendant policy) BEFORE any concept-id resolution. Use this for any non-trivial concept set as the first step of the two-stage pattern: commit to logic, then resolve IDs. Returns the spec back plus an explicit next_steps list of search_concepts calls to make. Skip for single-concept embeds where there is nothing to disambiguate."
   :schema schema
   :run run})
