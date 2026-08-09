(ns pythia.tools.phenotype-patterns
  "phenotype_patterns tool — what community definitions of a condition actually
   contain, aggregated across the bundled OHDSI Phenotype Library index.

   search_phenotypes returns individual definitions to mimic. This answers the
   question before that one: for THIS condition, what do accepted definitions
   usually restrict on, what do they usually exclude, do they enter on the first
   event, and how do they exit? Those answers used to live in the system prompt
   as prose the model had to take on trust ('most phenotypes have exclusions',
   'rule out Type 1 DM when defining Type 2 DM'). Here they come with counts and
   named sources, so the agent can cite what it followed and the user can
   disagree with the evidence rather than with an assertion."
  (:require [clojure.string :as str]
            [pythia.phenotype-sources :as ps]))

(def schema
  {:type "object"
   :properties
   {:condition {:type "string"
                :description "The condition or phenotype being designed, e.g. \"type 2 diabetes\", \"heart failure\"."}
    :limit {:type "number"
            :description "How many library definitions to aggregate over (default 15)."}}
   :required ["condition"]})

;; The concept set that names the condition itself is the entry event, not a
;; pattern worth reporting back ("T2DM definitions usually use a T2DM concept
;; set" tells the agent nothing).
(defn- names-the-condition? [condition set-name]
  (let [n (str/lower-case (or set-name ""))
        terms (->> (str/split (str/lower-case (or condition "")) #"\s+")
                   (remove str/blank?)
                   (remove #(contains? #{"of" "the" "and" "in" "with"} %)))]
    (and (seq terms) (every? #(str/includes? n %) terms))))

;; Community sets are named in prose, so the direction has to be read off the
;; name: "Exclude pregnancy", "No prior statin use", "Type 1 diabetes" (used as
;; a competing-diagnosis exclusion) all describe absence.
(def ^:private exclusion-name-re
  #"(?i)\b(exclud\w*|exclusion\w*|without|no prior|no history|prior|previous|competing|rule out|contraindicat\w*)\b")

;; Direction is only claimed when the NAME states it. A set called "Type 1
;; diabetes" inside a T2DM definition is almost certainly a competing-diagnosis
;; exclusion, but the index ships no Circe body to confirm that, and guessing
;; would hand the agent a confident wrong answer — the exact failure this tool
;; exists to avoid. Those land in :also-used with the caveat attached.
(defn- direction [set-name]
  (if (re-find exclusion-name-re (str set-name)) :stated-exclusion :direction-unstated))

(defn- tally
  "Frequency map -> descending [{:value v :count n}], top `n`."
  [coll n]
  (->> coll
       (remove nil?)
       frequencies
       (sort-by (juxt (comp - val) key))
       (take n)
       (mapv (fn [[v c]] {:value v :count c}))))

(defn summarise
  "Pure aggregation over raw index entries. Exposed for direct testing."
  [condition entries]
  (let [n (count entries)
        other-sets (for [e entries
                         cs (:concept-sets e)
                         :let [nm (:name cs)]
                         :when (and nm (not (names-the-condition? condition nm)))]
                     nm)
        by-direction (group-by direction other-sets)]
    {:definitions-considered n
     :sources (mapv (fn [e] {:id (:id e) :name (:name e) :status (:status e)}) entries)
     :explicitly-excluded (tally (:stated-exclusion by-direction) 8)
     :also-used (tally (:direction-unstated by-direction) 8)
     :also-used-note (str "Named without stating a direction. In a definition of "
                          condition
                          ", a competing diagnosis here is usually an exclusion and a "
                          "treatment is usually a requirement — confirm with "
                          "get_reference_phenotype before copying either way.")
     :entry-domains (tally (mapcat :entry-domains entries) 5)
     :entry-event-limit (tally (map :primary-event-limit entries) 3)
     :exit-strategy (tally (map :exit-strategy entries) 3)
     :median-inclusion-rules (when (pos? n)
                               (let [xs (sort (keep :n-inclusion-rules entries))]
                                 (nth xs (quot (count xs) 2) nil)))}))

(defn interpretation
  "Reading of the aggregate the agent should act on. Public for testing."
  [s]
  (let [limits (:entry-event-limit s)
        first-limit (some #(when (= "First" (:value %)) (:count %)) limits)
        total (:definitions-considered s)]
    (str/join
     " "
     (remove
      nil?
      [(when (zero? (or total 0))
         "No library definitions matched this condition — design from clinical reasoning and say that the library had nothing to anchor on.")
       (when (seq (:explicitly-excluded s))
         "The explicitly-excluded list is what accepted definitions rule out by name; propose the ones that apply and say which you skipped.")
       (when (and first-limit total (> first-limit (/ total 2)))
         "Most of these enter on the FIRST qualifying event — a new-user design. Set the entry-event limit accordingly rather than taking the default.")
       "These are patterns, not rules: cite what you followed, and say plainly where this cohort departs from them and why."]))))

(defn run [args _ctx]
  (let [condition (str (or (:condition args) ""))
        limit (or (some-> (:limit args) int) 15)]
    (if (str/blank? condition)
      (js/Promise.resolve {:error "condition is required"})
      (-> (ps/load-index)
          (.then (fn [index]
                   (let [entries (ps/filter-index index condition limit)
                         s (summarise condition entries)]
                     (assoc s :condition condition
                            :interpretation (interpretation s)))))
          (.catch (fn [e]
                    {:error (str "phenotype library index unavailable: "
                                 (.-message e))}))))))

(def tool
  {:name "phenotype_patterns"
   :description "What accepted OHDSI Phenotype Library definitions of a condition actually contain, aggregated: which concept sets they commonly EXCLUDE (competing diagnoses, prior exposure), which they commonly require, whether they enter on the first event, and how they exit — each with counts and the definitions they came from. Call this when designing a phenotype, before proposing criteria, so the exclusions you offer come from what the community does rather than recall. Use search_phenotypes instead when you want one definition to mimic, and get_reference_phenotype for its full Circe JSON."
   :schema schema
   :run run})
