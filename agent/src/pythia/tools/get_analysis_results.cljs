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
                     GET /cohort-characterization/generation/{genId}/result
     incidenceRate   GET /ir/{id}, GET /ir/{id}/info
                     GET /ir/{id}/report/{sourceKey}?targetId=&outcomeId=
   plus GET /cohortdefinition (cohort names), /cohortdefinition/{id}/info
   (cohort sizes) and /source/sources (generation source ids → keys)."
  (:require [clojure.string :as str]
            [pythia.webapi :as webapi]
            [pythia.tools.sources :as sources]))

(def schema
  {:type "object"
   :properties
   {:analysisType {:type "string"
                   :enum ["pathway" "characterization" "incidenceRate"]
                   :description "Which kind of analysis to read. REQUIRED."}
    :analysisId {:type "number" :description "Saved analysis id. REQUIRED."}
    :sourceKey {:type "string" :description "Optional source key. Defaults to the session source."}
    :topN {:type "number" :description "How many top pathways, or covariates per feature analysis, to return (default 8 / 10)."}
    :cohortId {:type "number" :description "Characterization only: report just this cohort."}
    :search {:type "string" :description "Characterization only: keep covariates whose name contains this text (e.g. 'insulin')."}}
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

;; ------------------------------------------------------------- shared ---

(defn- cohort-names
  "Promise of {cohortId name} for every cohort definition ({} on failure)."
  [auth]
  (-> (webapi/request "GET" "/cohortdefinition" {:auth auth})
      (.then (fn [xs] (into {} (for [c (when (sequential? xs) xs)] [(:id c) (:name c)]))))))

(defn- cohort-size
  "Promise of the person count of a cohort's completed generation on `source-key`."
  [cohort-id source-key key-by-id auth]
  (-> (webapi/request "GET" (str "/cohortdefinition/" cohort-id "/info") {:auth auth})
      (.then (fn [info]
               (some #(when (= source-key (get key-by-id (get-in % [:id :sourceId])))
                        (:personCount %))
                     (when (sequential? info) info))))))

;; ------------------------------------------------------- characterization ---

(def ^:private demographics
  "Analyses whose every row is worth showing (a full distribution, not a top-N)."
  #{"DemographicsGender" "DemographicsAgeGroup" "DemographicsRace" "DemographicsEthnicity"
    "DemographicsIndexYear" "DemographicsIndexMonth"})

(defn- covariate-label [r]
  (let [n (:conceptName r)]
    (if (and (seq n) (not= n "No matching concept")) n (:covariateName r))))

(defn- prevalence
  "Share of the cohort with the covariate: count / cohort size when the size is
   known (WebAPI rounds `avg` to two decimals), else `avg`."
  [r n]
  (if (and (number? n) (pos? n) (number? (:count r)))
    (/ (:count r) n)
    (:avg r)))

(defn- pct1 [x] (when (number? x) (/ (js/Math.round (* 1000.0 x)) 10.0)))

(defn- prevalence-row [r n]
  {:covariate (covariate-label r) :count (:count r) :pct (pct1 (prevalence r n))})

(defn- distribution-row [r]
  (merge {:covariate (covariate-label r)}
         (select-keys r [:count :avg :stdDev :min :p25 :median :p75 :max])))

(defn- analysis-rows [analysis-name rows n top-n]
  (cond
    (some #(= "DISTRIBUTION" (:resultType %)) rows)
    (mapv distribution-row rows)

    (demographics analysis-name)
    (mapv #(prevalence-row % n) (sort-by :covariateId rows))

    :else
    (mapv #(prevalence-row % n) (take top-n (sort-by :count > rows)))))

(defn- std-diff [p1 p2]
  (let [pooled (js/Math.sqrt (/ (+ (* p1 (- 1 p1)) (* p2 (- 1 p2))) 2))]
    (when (pos? pooled) (/ (js/Math.round (* 100.0 (/ (- p1 p2) pooled))) 100.0))))

(defn- largest-differences
  "Binary covariates that differ most (standardised difference) between the
   first two cohorts; a covariate missing from one cohort counts as 0%."
  [rows [a b] sizes top-n]
  (let [prev (filter #(= "PREVALENCE" (:resultType %)) rows)
        by-cov (group-by :covariateId prev)]
    (->> by-cov
         (keep (fn [[_ rs]]
                 (let [ra (some #(when (= a (:cohortId %)) %) rs)
                       rb (some #(when (= b (:cohortId %)) %) rs)
                       pa (or (some-> ra (prevalence (sizes a))) 0)
                       pb (or (some-> rb (prevalence (sizes b))) 0)
                       sd (std-diff pa pb)]
                   (when sd
                     {:covariate (covariate-label (or ra rb))
                      :analysis (:analysisName (or ra rb))
                      :pct-first (pct1 pa) :pct-second (pct1 pb) :std-diff sd}))))
         (sort-by #(- (js/Math.abs (:std-diff %))))
         (take top-n)
         vec)))

(defn summarise-characterization
  "Per cohort, per feature analysis: full demographics and distributions, the
   top-N binary covariates elsewhere; plus the largest differences between the
   first two cohorts."
  [rows {:keys [names sizes top-n cohort-id search]}]
  (let [needle (some-> search str/trim str/lower-case not-empty)
        all (filter map? (when (sequential? rows) rows))
        strata (->> all (remove #(contains? #{nil 0} (:strataId %))) (keep :strataName) distinct vec)
        overall (->> all
                     (filter #(contains? #{nil 0} (:strataId %)))
                     (filter #(or (nil? cohort-id) (= cohort-id (:cohortId %))))
                     (filter #(or (nil? needle)
                                  (str/includes? (str/lower-case (str (:covariateName %))) needle))))
        cohort-ids (vec (distinct (map :cohortId overall)))]
    (cond-> {:result-rows (count all)
             :cohorts
             (vec (for [cid cohort-ids
                        :let [n (sizes cid)
                              rs (filter #(= cid (:cohortId %)) overall)]]
                    {:cohortId cid
                     :name (names cid)
                     :persons n
                     :analyses (into (sorted-map)
                                     (for [[an ars] (group-by :analysisName rs)]
                                       [an (analysis-rows an ars n top-n)]))}))}
      (>= (count cohort-ids) 2)
      (assoc :largest-differences
             {:first (first cohort-ids) :second (second cohort-ids)
              :covariates (largest-differences overall (take 2 cohort-ids) sizes top-n)})
      (seq strata) (assoc :strata-not-shown strata))))

;; --------------------------------------------------------- incidence rate ---

(defn- ir-expression [ir]
  (let [e (:expression ir)]
    (cond (string? e) (try (js->clj (js/JSON.parse e) :keywordize-keys true)
                           (catch :default _ nil))
          (map? e) e)))

(defn- per-1000 [num den]
  (when (and (number? num) (number? den) (pos? den))
    (/ (js/Math.round (* 100.0 (/ (* 1000.0 num) den))) 100.0)))

(defn summarise-ir
  "WebAPI reports `timeAtRisk` in person-years."
  [report]
  (let [row (fn [s]
              (let [persons (or (:totalPersons s) (:targetPersons s))
                    years (or (:timeAtRisk s) (:personTime s))]
                {:persons persons
                 :cases (:cases s)
                 :person-years years
                 :rate-per-1000-person-years (per-1000 (:cases s) years)
                 :proportion-per-1000-persons (per-1000 (:cases s) persons)}))
        strata (filter map? (:stratifyStats report))]
    (cond-> (row (:summary report))
      (seq strata) (assoc :strata (mapv #(assoc (row %) :stratum (or (:name %) (:id %))) strata)))))

(defn- ir-executed-source
  "The source the IR ran on: the requested one if it completed there, else the
   newest completed run."
  [info source-key key-by-id]
  (let [runs (->> (when (sequential? info) info)
                  (map :executionInfo)
                  (filter #(= "COMPLETE" (str (:status %))))
                  (map #(get key-by-id (get-in % [:id :sourceId])))
                  (remove nil?))]
    (or (some #{source-key} runs) (first runs))))

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

(defn- characterization-results [id source-key {:keys [top-n] :as opts} auth]
  (-> (webapi/request "GET" (str "/cohort-characterization/" id "/generation") {:auth auth})
      (.then
       (fn [execs]
         (let [exec (latest-completed execs source-key)]
           (if-not (and exec (= "COMPLETED" (str (:status exec))))
             {:error (str "No completed generation found for characterization " id
                          (when exec (str " (latest run: " (:status exec) ")"))
                          ". Run generate_analysis first.")}
             (-> (js/Promise.all
                  #js [(webapi/request "GET" (str "/cohort-characterization/generation/" (:id exec) "/result")
                                       {:auth auth})
                       (cohort-names auth)
                       (sources/key-by-id auth)])
                 (.then
                  (fn [[rows names key-by-id]]
                    (let [ids (vec (distinct (keep :cohortId (when (sequential? rows) rows))))]
                      (-> (js/Promise.all
                           (clj->js (for [cid ids] (cohort-size cid (:sourceKey exec) key-by-id auth))))
                          (.then
                           (fn [counts]
                             (let [summary (summarise-characterization
                                            rows (assoc opts
                                                        :names names
                                                        :sizes (zipmap ids (vec counts))
                                                        :top-n (or top-n 10)))]
                               {:analysisType "characterization"
                                :analysisId id
                                :sourceKey (:sourceKey exec)
                                :status (:status exec)
                                :summary summary
                                :interpretation
                                (if (zero? (:result-rows summary))
                                  "The run completed but produced no feature rows — check that the cohorts are generated and not empty on this source."
                                  "Describe each cohort from its demographics and distributions, then the most common conditions/drugs (pct = share of the cohort). When there are two cohorts, lead with largest-differences: |std-diff| above 0.1 is a meaningful imbalance. Only the top-N binary covariates per analysis are listed; pass search to look up a specific one.")}))))))))))))))

(defn- ir-results [id source-key auth]
  (-> (js/Promise.all
       #js [(webapi/request "GET" (str "/ir/" id) {:auth auth})
            (webapi/request "GET" (str "/ir/" id "/info") {:auth auth})
            (sources/key-by-id auth)
            (cohort-names auth)])
      (.then
       (fn [[ir info key-by-id names]]
         (let [expr (ir-expression ir)
               targets (vec (:targetIds expr))
               outcomes (vec (:outcomeIds expr))
               ran-on (ir-executed-source info source-key key-by-id)
               pairs (vec (take 12 (for [t targets o outcomes] [t o])))]
           (cond
             (nil? ir)
             {:error (str "Incidence rate " id " not found.")}

             (empty? pairs)
             {:error (str "Incidence rate " id " has no target/outcome pair to report on.")}

             (nil? ran-on)
             {:error (str "Incidence rate " id " has no completed run. Run generate_analysis first.")}

             :else
             (-> (js/Promise.all
                  (clj->js
                   (for [[t o] pairs]
                     (webapi/request "GET" (str "/ir/" id "/report/" ran-on)
                                     {:auth auth :query {:targetId t :outcomeId o}}))))
                 (.then
                  (fn [reports]
                    {:analysisType "incidenceRate"
                     :analysisId id
                     :sourceKey ran-on
                     :time-at-risk (:timeAtRisk expr)
                     :results
                     (mapv (fn [[t o] report]
                             (merge {:target {:id t :name (names t)}
                                     :outcome {:id o :name (names o)}}
                                    (if report (summarise-ir report) {:error "no report for this pair"})))
                           pairs (vec reports))
                     :interpretation
                     "Quote cases, person-years and the rate together; a rate without its denominator is not interpretable. Both rates are per 1,000 (person-years or persons), not percentages. Name the time-at-risk window. Flag very low case counts (< 10) as unstable."})))))))))

(defn run [args ctx]
  (let [kind (some-> (:analysisType args) str)
        id (parse-id (:analysisId args))
        source-key (or (some-> (:sourceKey args) str) (:source-key ctx) "EUNOMIA")
        top-n (parse-id (:topN args))
        cohort-id (parse-id (:cohortId args))
        auth (:auth ctx)]
    (cond
      (nil? id)
      (js/Promise.resolve {:error "analysisId is required (numeric)"})

      (not (contains? #{"pathway" "characterization" "incidenceRate"} kind))
      (js/Promise.resolve {:error "analysisType must be one of: pathway, characterization, incidenceRate"})

      (= "pathway" kind) (pathway-results id source-key top-n auth)
      (= "characterization" kind) (characterization-results id source-key {:top-n top-n
                                                                            :cohort-id cohort-id
                                                                            :search (:search args)}
                                                            auth)
      :else (ir-results id source-key auth))))

(def tool
  {:name "get_analysis_results"
   :description "Read the RESULTS of a generated analysis so you can explain them: pathway (top treatment sequences, how much of the target cohort they cover), characterization (per cohort: demographics, distributions such as Charlson, the most common conditions/drugs, and the largest differences between two cohorts; narrow with cohortId/search) or incidence rate (every target × outcome pair: persons, cases, person-years, rate per 1,000 person-years). Call this AFTER generate_analysis has completed, whenever the user asks what the analysis shows, whether it looks sensible, or what the visualisation means. Returns {:summary :interpretation} — the interpretation names the honest reading of the numbers. Never describe results you have not read with this tool."
   :schema schema
   :run run})
