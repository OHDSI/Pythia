(ns pythia.tools.verify-concept-mapping
  "verify_concept_mapping tool — fetches a single OMOP concept by id and checks
   it against expected domain / vocabulary / standard-flag. Port of the JVM
   trexsql.agent.tools.verify-concept-mapping."
  (:require [clojure.string :as str]
            [pythia.webapi :as webapi]))

(def ^:private domain-enum
  ["Condition" "Drug" "Procedure" "Measurement"
   "Observation" "Visit" "Device" "Specimen"])

(def schema
  {:type "object"
   :properties {:conceptId          {:type "number" :description "OMOP concept id to verify."}
                :expectedDomain     {:type "string" :enum domain-enum}
                :expectedVocabulary {:type "string" :description "Expected vocabulary (e.g. SNOMED, RxNorm, LOINC). Substring match."}}
   :required ["conceptId"]})

(defn- parse-id [raw]
  (cond (number? raw) (long raw)
        (string? raw) (let [n (js/parseInt (str/trim raw) 10)] (when-not (js/isNaN n) n))
        :else nil))

(defn- fetch-concept [source-key concept-id auth]
  (webapi/request "GET" (str "/vocabulary/" source-key "/concept/" concept-id) {:auth auth}))

(defn- normalize-concept [body]
  (let [g (fn [& ks] (some #(get body %) ks))]
    {:conceptId   (g :CONCEPT_ID :conceptId "CONCEPT_ID")
     :conceptName (g :CONCEPT_NAME :conceptName "CONCEPT_NAME")
     :domain      (g :DOMAIN_ID :domainId "DOMAIN_ID")
     :vocabulary  (g :VOCABULARY_ID :vocabularyId "VOCABULARY_ID")
     :standard    (g :STANDARD_CONCEPT :standardConcept "STANDARD_CONCEPT")
     :code        (g :CONCEPT_CODE :conceptCode "CONCEPT_CODE")
     :validReason (g :INVALID_REASON :invalidReason "INVALID_REASON")}))

(defn- check-issues [concept expected]
  (let [{:keys [domain vocabulary standard validReason]} concept
        exp-domain (some-> (:expectedDomain expected) str)
        exp-vocab (some-> (:expectedVocabulary expected) str)]
    (cond-> []
      (and exp-domain
           (not (str/blank? exp-domain))
           (not= exp-domain (str domain)))
      (conj (str "domain mismatch: concept is " domain ", expected " exp-domain))

      (and exp-vocab
           (not (str/blank? exp-vocab))
           (not (str/includes? (str/lower-case (str vocabulary))
                               (str/lower-case exp-vocab))))
      (conj (str "vocabulary mismatch: concept is " vocabulary ", expected " exp-vocab))

      (not= "S" (str standard))
      (conj (str "non-standard concept (STANDARD_CONCEPT=" standard
                 ") — search_concepts only returns standards but a hand-picked id may be source/classification"))

      (and validReason (not (str/blank? (str validReason))))
      (conj (str "concept is invalid: reason=" validReason)))))

(defn run [args ctx]
  (let [concept-id (parse-id (:conceptId args))
        source-key (or (:source-key ctx) "EUNOMIA")
        auth (:auth ctx)]
    (if (nil? concept-id)
      (js/Promise.resolve {:ok false :errors ["conceptId is required (numeric)"]})
      (-> (fetch-concept source-key concept-id auth)
          (.then (fn [body]
                   (if (nil? body)
                     {:ok false
                      :errors [(str "concept " concept-id " not found in vocabulary "
                                    source-key " (or WebAPI returned non-200)")]}
                     (let [concept (normalize-concept body)
                           issues (check-issues concept args)]
                       {:ok (empty? issues)
                        :concept concept
                        :issues issues
                        :verdict (cond
                                   (empty? issues) "OK — safe to use"
                                   (some #(str/starts-with? % "concept is invalid") issues)
                                   "REJECT — invalid concept"
                                   :else
                                   "REVIEW — see issues; pick a different concept if domain/vocabulary doesn't match")}))))
          (.catch (fn [e]
                    {:ok false :errors [(str "WebAPI request failed: " (or (.-message e) e))]}))))))

(def tool
  {:name "verify_concept_mapping"
   :description "Look up a single OMOP concept by id and check it against an expected domain and/or vocabulary. Use AFTER picking a concept from search_concepts when its `:confidence` is `:low` or when it carries `:flags`, AND for any concept id you used from your own knowledge (the limited-vocabulary fallback list). Returns {:ok :concept :issues [...]} so you can swap out a wrong pick before proposing."
   :schema schema
   :run run})
