(ns pythia.tools.get-cohort-samples
  "get_cohort_samples tool — reads the patient samples saved for a cohort (the
   ATLAS cohort Samples tab) so the agent can say who is in a sample and pick
   people to open with get_person_profile.

   WebAPI endpoints:
     GET /cohortsample/{cohortId}/{sourceKey}            samples, no persons
     GET /cohortsample/{cohortId}/{sourceKey}/{sampleId}?fields=elements"
  (:require [clojure.string :as str]
            [pythia.webapi :as webapi]))

(def schema
  {:type "object"
   :properties {:cohortId {:type "number" :description "Saved cohort id. REQUIRED."}
                :sampleId {:type "number" :description "Optional sample id. Without it, the persons of the only (or newest) sample are returned."}
                :sourceKey {:type "string" :description "Optional source key. Defaults to the chat session's source."}}
   :required ["cohortId"]})

(defn- parse-id [raw]
  (cond (number? raw) (long raw)
        (string? raw) (let [n (js/parseInt (str/trim raw) 10)] (when-not (js/isNaN n) n))
        :else nil))

(def ^:private gender-names {8507 "male" 8532 "female"})

(defn- criteria [s]
  (cond-> {}
    (:age s) (assoc :age (:age s))
    (seq (get-in s [:gender :conceptIds])) (assoc :gender (mapv #(get gender-names % %) (get-in s [:gender :conceptIds])))))

(defn- sample-row [s]
  (merge {:sampleId (:id s) :name (:name s) :size (:size s)}
         (criteria s)))

(defn- median [xs]
  (let [v (vec (sort xs)) n (count v)]
    (when (pos? n)
      (if (odd? n) (v (quot n 2)) (/ (+ (v (dec (quot n 2))) (v (quot n 2))) 2)))))

(defn summarise-elements [elements]
  (let [els (filter map? elements)
        ages (keep :age els)]
    {:persons (mapv (fn [e] (cond-> {:personId (:personId e)
                                     :gender (get gender-names (:genderConceptId e) (:genderConceptId e))
                                     :age-at-index (:age e)}
                              (:recordCount e) (assoc :records (:recordCount e))))
                    (sort-by :rank els))
     :gender-counts (frequencies (map #(get gender-names (:genderConceptId %) "other") els))
     :age {:min (when (seq ages) (apply min ages))
           :median (median ages)
           :max (when (seq ages) (apply max ages))}}))

(defn run [args ctx]
  (let [cohort-id (parse-id (:cohortId args))
        sample-id (parse-id (:sampleId args))
        source-key (or (some-> (:sourceKey args) str) (:source-key ctx) "EUNOMIA")
        auth (:auth ctx)
        base (str "/cohortsample/" cohort-id "/" source-key)]
    (if (nil? cohort-id)
      (js/Promise.resolve {:error "cohortId is required (numeric)"})
      (-> (webapi/request "GET" base {:auth auth})
          (.then
           (fn [listing]
             (let [samples (vec (filter map? (:samples listing)))
                   chosen (or sample-id (:id (last (sort-by :id samples))))]
               (cond
                 (nil? listing)
                 {:error (str "WebAPI returned no samples listing for cohort " cohort-id " on source " source-key)}

                 (empty? samples)
                 {:cohortId cohort-id :sourceKey source-key :samples []
                  :generationStatus (:generationStatus listing)
                  :interpretation "No samples are saved for this cohort on this source. The user can create one on the cohort's Samples tab (the cohort must be generated)."}

                 :else
                 (-> (webapi/request "GET" (str base "/" chosen) {:auth auth :query {:fields "elements"}})
                     (.then
                      (fn [sample]
                        {:cohortId cohort-id
                         :sourceKey source-key
                         :samples (mapv sample-row samples)
                         :shown (if sample
                                  (merge (sample-row sample) (summarise-elements (:elements sample)))
                                  {:sampleId chosen :error "sample not found"})
                         :interpretation "Describe the sample (size, sex split, age range). To explain one person's history, call get_person_profile with their personId and this cohortId."})))))))
          (.catch (fn [e]
                    {:error (str "WebAPI request failed: " (or (.-message e) e))}))))))

(def tool
  {:name "get_cohort_samples"
   :description "Read the patient samples saved for a cohort (the cohort's Samples tab in ATLAS): the samples with their size and age/sex criteria, and for one sample (sampleId, else the newest) its persons — personId, sex, age at index, record count — with the sex split and age range. Use it when the user asks about a cohort sample or wants example patients; follow with get_person_profile to explain one person."
   :schema schema
   :run run})
