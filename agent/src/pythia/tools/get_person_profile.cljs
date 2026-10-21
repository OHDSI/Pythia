(ns pythia.tools.get-person-profile
  "get_person_profile tool — reads one person's record (the ATLAS patient
   profile) and condenses it to what explains them: demographics, cohort
   memberships, observation time, the most frequent concepts per domain and
   the events closest to the cohort index date.

   WebAPI endpoints: GET /{sourceKey}/person/{personId}?cohort={cohortId},
   GET /cohortdefinition (names of the cohorts the person is in)
   With a cohort, record days are relative to that cohort's index date;
   without one, relative to the start of observation."
  (:require [clojure.string :as str]
            [pythia.webapi :as webapi]))

(def schema
  {:type "object"
   :properties {:personId {:type "number" :description "Person id (from a cohort sample or the profile URL). REQUIRED."}
                :cohortId {:type "number" :description "Optional cohort id: days are then counted from this cohort's index date."}
                :windowDays {:type "number" :description "Only count records within this many days before/after day 0 (default 365)."}
                :topN {:type "number" :description "Concepts per domain to list (default 8)."}
                :sourceKey {:type "string" :description "Optional source key. Defaults to the chat session's source."}}
   :required ["personId"]})

(defn- parse-id [raw]
  (cond (number? raw) (long raw)
        (string? raw) (let [n (js/parseInt (str/trim raw) 10)] (when-not (js/isNaN n) n))
        :else nil))

(defn- iso-date [ms]
  (when (number? ms) (subs (.toISOString (js/Date. ms)) 0 10)))

(def ^:private era-domains
  "Derived eras repeat the underlying condition/drug records."
  #{"conditionera" "drugera"})

(defn- concept-label [r]
  (let [n (:conceptName r)]
    (if (and (seq n) (not= n "No matching concept")) n (str "concept " (:conceptId r)))))

(defn- mapped? [r] (not (contains? #{0 nil} (:conceptId r))))

(defn summarise-profile [person {:keys [window top-n names]}]
  (let [records (filter map? (:records person))
        in-window (filter #(and (number? (:startDay %)) (<= (- window) (:startDay %) window)) records)
        by-domain (group-by :domain (remove #(era-domains (:domain %)) in-window))]
    {:gender (:gender person)
     :year-of-birth (:yearOfBirth person)
     :age-at-index (:ageAtIndex person)
     :total-records (or (:recordCount person) (count records))
     :observation-periods (mapv #(hash-map :type (:type %) :from-day (:x1 %) :to-day (:x2 %))
                                (:observationPeriods person))
     :cohorts (mapv #(cond-> {:cohortId (:cohortDefinitionId %)
                              :start (iso-date (:startDate %)) :end (iso-date (:endDate %))}
                       (get names (:cohortDefinitionId %)) (assoc :name (get names (:cohortDefinitionId %))))
                    (:cohorts person))
     :window-days window
     :domains
     (into (sorted-map)
           (for [[domain rs] by-domain]
             [domain {:records (count rs)
                      :top-concepts
                      (->> (group-by concept-label (filter mapped? rs))
                           (map (fn [[label xs]]
                                  {:concept label :records (count xs)
                                   :first-day (apply min (map :startDay xs))}))
                           (sort-by :records >)
                           (take top-n)
                           vec)}]))
     :around-day-0
     (->> (remove #(era-domains (:domain %)) records)
          (filter mapped?)
          (filter #(and (number? (:startDay %)) (<= -30 (:startDay %) 30)))
          (sort-by #(js/Math.abs (:startDay %)))
          (map #(hash-map :day (:startDay %) :domain (:domain %) :concept (concept-label %)))
          distinct
          (take 25)
          vec)}))

(defn run [args ctx]
  (let [person-id (parse-id (:personId args))
        cohort-id (parse-id (:cohortId args))
        window (or (parse-id (:windowDays args)) 365)
        top-n (or (parse-id (:topN args)) 8)
        source-key (or (some-> (:sourceKey args) str) (:source-key ctx) "EUNOMIA")
        auth (:auth ctx)]
    (if (nil? person-id)
      (js/Promise.resolve {:error "personId is required (numeric)"})
      (-> (js/Promise.all
           #js [(webapi/request "GET" (str "/" source-key "/person/" person-id)
                                {:auth auth :query (when cohort-id {:cohort cohort-id})})
                (webapi/request "GET" "/cohortdefinition" {:auth auth})])
          (.then
           (fn [[person cohorts]]
             (if-not person
               {:error (str "No profile for person " person-id " on source " source-key ".")}
               {:personId person-id
                :sourceKey source-key
                :day-0 (if cohort-id
                         (str "index date of cohort " cohort-id)
                         "start of observation")
                :profile (summarise-profile
                          person {:window window :top-n top-n
                                  :names (into {} (for [c (when (sequential? cohorts) cohorts)]
                                                    [(:id c) (:name c)]))})
                :interpretation "Explain the person in clinical terms: who they are, why they entered the cohort (its name says what it requires; the records around day 0 show the qualifying event), and their main conditions and treatments. Days are relative to day-0. This is one patient — do not generalise from it."})))
          (.catch (fn [e]
                    {:error (str "WebAPI request failed: " (or (.-message e) e))}))))))

(def tool
  {:name "get_person_profile"
   :description "Read one person's patient profile (the ATLAS Profiles view): sex, age at index, observation time, cohort memberships, the most frequent conditions/drugs/procedures/visits/measurements within a window around day 0, and the records closest to day 0. Pass cohortId to count days from that cohort's index date. Use it when the user is on a profile or asks about a specific patient, e.g. one from get_cohort_samples."
   :schema schema
   :run run})
