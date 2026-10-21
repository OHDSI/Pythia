(ns pythia.tools.get-cohort-overlap
  "get_cohort_overlap tool — pulls per-pair overlap counts between two or more
   generated cohorts. Port of the JVM trexsql.agent.tools.get-cohort-overlap.
   WebAPI endpoint: GET /cohortdefinition/{id}/info (person count per source;
   WebAPI 3 exposes no pairwise intersection)."
  (:require [clojure.string :as str]
            [pythia.webapi :as webapi]
            [pythia.tools.sources :as sources]))

(def schema
  {:type "object"
   :properties {:cohortIds {:type "array" :items {:type "number"}
                            :description "2+ saved cohort ids."}
                :sourceKey {:type "string"}}
   :required ["cohortIds"]})

(defn- parse-id [v]
  (cond (number? v) (long v)
        (string? v) (let [n (js/parseInt (str/trim v) 10)] (when-not (js/isNaN n) n))
        :else nil))

(defn- pop-count [report]
  (or (:totalRecords report)
      (:personCount report)
      (some-> report :summary :persons)))

(defn- generation-on
  "The cohort's completed generation on `source-key` from /info, or nil."
  [info source-key key-by-id]
  (some #(when (and (= "COMPLETE" (str (:status %)))
                    (= source-key (get key-by-id (get-in % [:id :sourceId]))))
           %)
        (when (sequential? info) info)))

(defn run [args ctx]
  (let [ids (->> (or (:cohortIds args) [])
                 (map parse-id)
                 (remove nil?)
                 distinct
                 vec)
        source-key (or (some-> (:sourceKey args) str) (:source-key ctx) "EUNOMIA")
        auth (:auth ctx)]
    (if (< (count ids) 2)
      (js/Promise.resolve {:error "cohortIds must contain at least 2 distinct ids"})
      (-> (sources/key-by-id auth)
          (.then
           (fn [key-by-id]
             (js/Promise.all
              (clj->js
               (for [id ids]
                 (-> (webapi/request "GET" (str "/cohortdefinition/" id "/info") {:auth auth})
                     (.then (fn [info] #js [id (generation-on info source-key key-by-id)]))))))))
          (.then
           (fn [pairs]
             (let [reports (into {} (map (fn [p] [(aget p 0) (aget p 1)])) pairs)
                   missing (->> reports (filter (comp nil? val)) (mapv key))]
               (if (seq missing)
                 {:error (str "no completed generation for cohort id(s) " (str/join "," missing)
                              " on source " source-key " — generate first")}
                 (let [counts (into {} (for [[id r] reports] [id (pop-count r)]))
                       pairs* (for [a ids b ids :when (< a b)] [a b])
                       pair-data
                       (mapv
                        (fn [[a b]]
                          (let [na (counts a) nb (counts b)
                                overlap (some (fn [[_ v]]
                                                (when (and (= a (:targetCohortId v))
                                                           (= b (:comparatorCohortId v)))
                                                  (:overlap v)))
                                              (or (get-in reports [a :overlap]) {}))]
                            {:cohortA a :cohortAPersons na
                             :cohortB b :cohortBPersons nb
                             :overlap overlap
                             :note (cond
                                     (nil? overlap) "WebAPI does not expose the intersection of two cohorts; compare the person counts only."
                                     (and na overlap (zero? na)) "cohort A is empty"
                                     (and na overlap (>= overlap (* 0.95 na)))
                                     "near-total overlap — these cohorts may be redundant"
                                     (and na overlap (<= overlap (* 0.01 na)))
                                     "near-zero overlap — comparator may be too disjoint"
                                     :else nil)}))
                        pairs*)]
                   {:sourceKey source-key
                    :counts counts
                    :pairs pair-data})))))
          (.catch (fn [e]
                    {:error (str "WebAPI request failed: " (or (.-message e) e))}))))))

(def tool
  {:name "get_cohort_overlap"
   :description "Pairwise overlap between 2+ generated cohorts on a source. Use when comparing target vs. comparator populations or checking whether two phenotypes are redundant. Flags near-total overlap (likely redundant) and near-zero overlap (likely too disjoint for a comparator design)."
   :schema schema
   :run run})
