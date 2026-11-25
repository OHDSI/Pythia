(ns pythia.tools.search-phenotypes
  "search_phenotypes tool — queries four phenotype sources in parallel and merges
   the results: the bundled OHDSI Phenotype Library index, the live HDR UK
   Phenotype Library API, PheKB, and the OHDSI Forums (Discourse). Each source
   returns the unified item shape from pythia.phenotype-sources and is fail-soft.

   Port of the JVM trexsql.agent.tools.search-phenotypes, with the OHDSI Library
   ranking + :circe-summary restored and HDR UK added."
  (:require [clojure.string :as str]
            [pythia.phenotype-sources :as ps]))

(def ^:private per-source-limit 5)
(def ^:private overall-limit 20)
(def ^:private sources ["ohdsi-library" "hdruk" "phekb" "forums"])

(def schema
  {:type "object"
   :properties {:query {:type "string" :description "The condition's full clinical name as the library would name it, e.g. \"type 2 diabetes mellitus\", not \"T2DM\""}}
   :required ["query"]})

(defn library-next-step
  "The instruction returned with OHDSI Phenotype Library hits. Without it, some
   models used a library phenotype only as silent inspiration: they told the
   user \"no saved match\" (meaning saved ATLAS cohorts) and proposed a
   simplified custom entry event, so the user never learned that validated
   definitions existed. Nil when the library returned nothing."
  [library-hits]
  (when (seq library-hits)
    (str "The OHDSI Phenotype Library has matching phenotypes: "
         (str/join "; " (map #(str "#" (:id %) " " (:name %)) (take 3 library-hits)))
         ". Before proposing an entry event, tell the user these by name and id, say in one line how the"
         " closest ones differ (e.g. diagnosis only vs. also treatment or lab evidence, exclusions), and call"
         " ask_user with options to rebuild a specific library phenotype or to design a custom cohort."
         " If they pick one, call get_reference_phenotype for it and rebuild it faithfully: same concepts,"
         " entry, inclusion rules and exit; say explicitly which parts you could not mirror."
         " Skip the question only if the user already asked for a custom definition or named the phenotype to use.")))

(def library-retry-note
  "Returned when the OHDSI Phenotype Library has no match. The library names
   conditions in full, so an abbreviation or a narrow wording finds nothing."
  (str "No OHDSI Phenotype Library match for this query. The library names conditions in full"
       " (\"type 2 diabetes mellitus\", not \"T2DM\"): search again with the full condition name or a"
       " common synonym before concluding the library has no phenotype for it."))

(defn run
  "Tool entrypoint. Args: {:query \"clinical condition\"}."
  [args _ctx]
  (let [query (str (or (:query args) ""))]
    (if (str/blank? query)
      (js/Promise.resolve {:results [] :note "empty query"})
      (-> (js/Promise.all
           #js [(ps/search-ohdsi-library query per-source-limit)
                (ps/search-hdruk query per-source-limit)
                (ps/search-phekb query per-source-limit)
                (ps/search-forums query per-source-limit)])
          (.then (fn [results]
                   (let [merged (ps/merge-rank (apply concat (array-seq results)) overall-limit)
                         next-step (library-next-step (filter #(= "ohdsi-library" (:source %)) merged))]
                     (assoc {:results merged :sources sources}
                            :next-step (or next-step library-retry-note)))))
          (.catch (fn [_] {:results [] :sources sources}))))))

(def tool
  {:name "search_phenotypes"
   :description "Search the OHDSI Phenotype Library, the HDR UK Phenotype Library, PheKB, and the OHDSI Forums for validated phenotype definitions. Search with the condition's full clinical name, not an abbreviation. Call after search_existing_cohorts when no existing cohort matches. OHDSI Phenotype Library hits (`:source \"ohdsi-library\"`, `:kind :omop-cohort`) are directly importable OMOP cohorts and include a `:circe-summary` (entry domains, # primary criteria, # inclusion rules, concept-set list, exit strategy). When the library has a matching phenotype, tell the user about it and offer to rebuild it before designing your own (the result's `:next-step` says how). HDR UK hits (`:kind :clinical-codelist`) are UK EHR codelists for reference. When a hit is the right template and you want the full Circe JSON (or HDR UK clinical codes), follow up with `get_reference_phenotype`."
   :schema schema
   :run run})
