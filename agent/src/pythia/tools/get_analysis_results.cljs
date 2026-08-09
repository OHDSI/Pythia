(ns pythia.tools.get-analysis-results
  "get_analysis_results tool — reads the OUTPUT of a generated analysis so the
   agent can explain it, rather than only building and running one.

   Pythia could already read cohort results (get_cohort_generation_summary,
   summarise_attrition, get_cohort_overlap) but had no way to see what a
   pathway / characterization / incidence-rate run actually produced, so it
   could run an analysis and then say nothing useful about the numbers.

   WebAPI endpoints, per analysis type:
     pathway         GET /pathway-analysis/{id}/generation
                     GET /pathway-analysis/generation/{genId}/result
     characterization GET /cohort-characterization/{id}/generation
                     GET /cohort-characterization/generation/{genId}/result/count
     incidenceRate   GET /ir/{id}
                     GET /ir/{id}/report/{sourceKey}?targetId=&outcomeId="
  (:require [clojure.string :as str]
            [pythia.webapi :as webapi]))

(def schema
  {:type "object"
   :properties
   {:analysisType {:type "string"
                   :enum ["pathway" "characterization" "incidenceRate"]
                   :description "Which kind of analysis to read. REQUIRED."}
    :analysisId {:type "number" :description "Saved analysis id. REQUIRED."}
    :sourceKey {:type "string" :description "Source key (e.g. 'EUNOMIA'). Defaults to the session source."}
    :topN {:type "number" :description "How many top pathways to return (default 8)."}}
   :required ["analysisType" "analysisId"]})

(defn- parse-id [raw]
  (cond (number? raw) (long raw)
        (string? raw) (let [n (js/parseInt (str/trim raw) 10)] (when-not (js/isNaN n) n))
        :else nil))

(defn- pct [n d]
  (when (and (number? n) (number? d) (pos? d))
    (/ (js/Math.round (* 1000.0 (/ n d))) 10.0)))

(defn- latest-completed
  "Newest execution, preferring COMPLETED ones on the requested source."
  [execs source-key]
  (let [xs (filter map? (when (sequential? execs) execs))
        on-source (filter #(or (nil? source-key) (= source-key (:sourceKey %))) xs)
        pool (if (seq on-source) on-source xs)
        done (filter #(= "COMPLETED" (str (:status %))) pool)]
    (last (sort-by :id (if (seq done) done pool)))))

;; ---------------------------------------------------------------- pathway ---

(defn- decode-path
  "Pathway `path` is a dash-joined list of event codes ('1-3'); turn it into the
   cohort names so the model talks about drugs, not integers."
  [path code->name]
  (->> (str/split (str path) #"-")
       (map #(get code->name (str/trim %) (str "code " %)))
       (str/join " then ")))

(defn- pathway-interpretation [s]
  (cond
    (nil? (:persons-with-pathway s))
    "Result had no pathway groups — the analysis may have produced nothing on this source."
    (zero? (or (:persons-with-pathway s) 0))
    "No one in the target cohort had any of the event cohorts. Check that the event cohorts are populated on this source before drawing conclusions."
    (and (:coverage-pct s) (< (:coverage-pct s) 20))
    "Only a small share of the target cohort has any pathway — say so plainly; the sequences describe a minority, not the typical patient."
    :else
    "Describe the dominant sequences by share of the target cohort, and name what the long tail means clinically. Do not over-read paths with tiny person counts (below the analysis min cell count they are suppressed)."))

(defn- summarise-pathway [result top-n]
  (let [code->name (into {} (map (fn [c] [(str (:code c)) (:name c)])
                                 (:eventCodes result)))
        group (first (:pathwayGroups result))
        target-count (:targetCohortCount group)
        with-pathway (:totalPathwaysCount group)
        paths (->> (:pathways group)
                   (sort-by :personCount >)
                   (take (or top-n 8))
                   (mapv (fn [p]
                           {:sequence (decode-path (:path p) code->name)
                            :persons (:personCount p)
                            :pct-of-target (pct (:personCount p) target-count)})))]
    {:target-persons target-count
     :persons-with-pathway with-pathway
     :coverage-pct (pct with-pathway target-count)
     :distinct-event-cohorts (count (:eventCodes result))
     :top-pathways paths}))

;; ------------------------------------------------------- characterization ---

(defn- summarise-characterization [counts]
  {:result-rows (cond (number? counts) counts
                      (map? counts) (or (:count counts) (:totalCount counts))
                      :else nil)})

;; --------------------------------------------------------- incidence rate ---

(defn- summarise-ir [report]
  (let [s (:summary report)]
    {:target-persons (or (:totalPersons s) (:targetPersons s))
     :cases (:cases s)
     :person-time-days (or (:timeAtRisk s) (:personTime s))
     :incidence-rate (or (:rate s) (:incidenceRate s))
     :proportion (:proportion s)}))

(defn- pathway-results [id source-key top-n auth]
  (-> (webapi/request "GET" (str "/pathway-analysis/" id "/generation") {:auth auth})
      (.then
       (fn [execs]
         (let [exec (latest-completed execs source-key)]
           (if-not exec
             (js/Promise.resolve
              {:error (str "No generation found for pathway analysis " id ". Run generate_analysis first.")})
             (-> (webapi/request "GET" (str "/pathway-analysis/generation/" (:id exec) "/result") {:auth auth})
                 (.then
                  (fn [result]
                    (if-not result
                      {:error "Generation exists but returned no result rows."}
                      (let [summary (summarise-pathway result top-n)]
                        {:analysisType "pathway"
                         :analysisId id
                         :sourceKey (:sourceKey exec)
                         :status (:status exec)
                         :summary summary
                         :interpretation (pathway-interpretation summary)}))))))))))) 

(defn- characterization-results [id source-key auth]
  (-> (webapi/request "GET" (str "/cohort-characterization/" id "/generation") {:auth auth})
      (.then
       (fn [execs]
         (let [exec (latest-completed execs source-key)]
           (if-not exec
             (js/Promise.resolve
              {:error (str "No generation found for characterization " id ". Run generate_analysis first.")})
             (-> (webapi/request "GET" (str "/cohort-characterization/generation/" (:id exec) "/result/count")
                                 {:auth auth})
                 (.then
                  (fn [counts]
                    {:analysisType "characterization"
                     :analysisId id
                     :sourceKey (:sourceKey exec)
                     :status (:status exec)
                     :summary (summarise-characterization counts)
                     :interpretation
                     "Report the generation status and how many feature rows were produced. For specific covariates, open the characterization in ATLAS — this tool reports the run, not every feature."})))))))))

(defn- ir-results [id source-key auth]
  (-> (webapi/request "GET" (str "/ir/" id) {:auth auth})
      (.then
       (fn [ir]
         (let [target (first (:targetIds ir))
               outcome (some-> (first (:outcomes ir)) :id)]
           (if-not (and target outcome)
             (js/Promise.resolve
              {:error (str "Incidence rate " id " has no target/outcome pair to report on.")})
             (-> (webapi/request "GET" (str "/ir/" id "/report/" source-key)
                                 {:auth auth :query {:targetId target :outcomeId outcome}})
                 (.then
                  (fn [report]
                    (if-not report
                      {:error "No report available — generate the incidence rate analysis first."}
                      {:analysisType "incidenceRate"
                       :analysisId id
                       :sourceKey source-key
                       :summary (summarise-ir report)
                       :interpretation
                       "Quote cases, person-time and the rate together; a rate without its denominator is not interpretable. Flag very low case counts as unstable."})))))))))) 

(defn run [args ctx]
  (let [kind (some-> (:analysisType args) str)
        id (parse-id (:analysisId args))
        source-key (or (some-> (:sourceKey args) str) (:source-key ctx) "EUNOMIA")
        top-n (parse-id (:topN args))
        auth (:auth ctx)]
    (cond
      (nil? id)
      (js/Promise.resolve {:error "analysisId is required (numeric)"})

      (not (contains? #{"pathway" "characterization" "incidenceRate"} kind))
      (js/Promise.resolve {:error "analysisType must be one of: pathway, characterization, incidenceRate"})

      (= "pathway" kind) (pathway-results id source-key top-n auth)
      (= "characterization" kind) (characterization-results id source-key auth)
      :else (ir-results id source-key auth))))

(def tool
  {:name "get_analysis_results"
   :description "Read the RESULTS of a generated analysis so you can explain them: pathway (top treatment sequences, how much of the target cohort they cover), characterization (generation status and result size) or incidence rate (cases, person-time, rate). Call this AFTER generate_analysis has completed, whenever the user asks what the analysis shows, whether it looks sensible, or what the visualisation means. Returns {:summary :interpretation} — the interpretation names the honest reading of the numbers. Never describe results you have not read with this tool."
   :schema schema
   :run run})
