(ns pythia.tools.phenotype-patterns-test
  "The aggregation is pure, so it is tested directly on index-shaped entries —
   no network, no bundled-index coupling."
  (:require [cljs.test :refer [deftest is testing async]]
            [pythia.tools.phenotype-patterns :as pp]))

(def ^:private entries
  [{:id 1 :name "Type 2 diabetes mellitus" :status "Accepted"
    :n-inclusion-rules 3 :primary-event-limit "First" :exit-strategy "end_of_observation"
    :entry-domains ["ConditionOccurrence"]
    :concept-sets [{:name "Type 2 diabetes mellitus"} {:name "Type 1 diabetes"}
                   {:name "Gestational diabetes"} {:name "Metformin"}]}
   {:id 2 :name "Type 2 diabetes with metformin" :status "Accepted"
    :n-inclusion-rules 2 :primary-event-limit "First" :exit-strategy "end_of_observation"
    :entry-domains ["ConditionOccurrence" "DrugExposure"]
    :concept-sets [{:name "Type 2 diabetes"} {:name "Type 1 diabetes"}
                   {:name "Exclude pregnancy"} {:name "Metformin"}]}
   {:id 3 :name "Type 2 diabetes, incident" :status "Under review"
    :n-inclusion-rules 4 :primary-event-limit "All" :exit-strategy "fixed_duration"
    :entry-domains ["ConditionOccurrence"]
    :concept-sets [{:name "Type 2 diabetes"} {:name "No prior insulin"}]}])

(deftest reports-only-the-exclusions-the-names-state
  (let [s (pp/summarise "type 2 diabetes" entries)
        excluded (into {} (map (juxt :value :count) (:explicitly-excluded s)))
        also (into {} (map (juxt :value :count) (:also-used s)))]
    ;; Stated in the name -> reported as an exclusion.
    (is (= 1 (get excluded "Exclude pregnancy")))
    (is (= 1 (get excluded "No prior insulin")))
    ;; NOT stated: "Type 1 diabetes" in a T2DM definition is almost certainly a
    ;; competing-diagnosis exclusion, but the index carries no Circe body to
    ;; confirm it. Reporting it as an exclusion would be a confident guess, so
    ;; it goes to :also-used with the caveat instead.
    (is (nil? (get excluded "Type 1 diabetes")))
    (is (= 2 (get also "Type 1 diabetes")))
    (is (re-find #"confirm with get_reference_phenotype" (:also-used-note s)))))

(deftest drops-the-condition-itself-from-the-patterns
  (let [s (pp/summarise "type 2 diabetes" entries)
        all (concat (:explicitly-excluded s) (:also-used s))]
    ;; "T2DM definitions usually use a T2DM concept set" tells the agent nothing.
    (is (not-any? #(re-find #"(?i)type 2 diabetes" (:value %)) all))))

(deftest reports-the-sets-used-alongside
  (let [s (pp/summarise "type 2 diabetes" entries)
        also (into {} (map (juxt :value :count) (:also-used s)))]
    (is (= 2 (get also "Metformin")))))

(deftest surfaces-the-new-user-shape
  (let [s (pp/summarise "type 2 diabetes" entries)]
    (is (= [{:value "First" :count 2} {:value "All" :count 1}] (:entry-event-limit s)))
    (is (= 3 (:definitions-considered s)))
    (is (= 3 (:median-inclusion-rules s)))
    (is (= 3 (count (:sources s))))))

(deftest empty-match-says-so-instead-of-inventing
  (async done
    (-> (js/Promise.resolve ((:run pp/tool) {:condition ""} {}))
        (.then (fn [out] (is (= "condition is required" (:error out))) (done))))))

(deftest interpretation-flags-the-new-user-default
  ;; Two of three definitions enter on the FIRST event, which the agent should
  ;; act on rather than accept the editor's default of every qualifying event.
  (let [out (pp/interpretation (pp/summarise "type 2 diabetes" entries))]
    (is (re-find #"(?i)first qualifying event" out))
    (is (re-find #"(?i)patterns, not rules" out))))
