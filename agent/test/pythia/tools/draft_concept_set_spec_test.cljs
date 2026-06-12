(ns pythia.tools.draft-concept-set-spec-test
  (:require [cljs.test :refer [deftest is async testing]]
            [pythia.tools.draft-concept-set-spec :as dcs]))

(defn- run [args] (js/Promise.resolve ((:run dcs/tool) args {})))

(deftest records-spec-and-emits-next-steps
  (async done
    (-> (run {:name "Confirmatory T2DM treatment"
              :clinical_terms ["metformin" "sulfonylurea"]
              :domain "Drug"
              :include_descendants true})
        (.then (fn [out]
                 (is (true? (:ok out)))
                 (is (= "Confirmatory T2DM treatment" (get-in out [:spec :name])))
                 (is (= ["metformin" "sulfonylurea"] (get-in out [:spec :clinical_terms])))
                 (is (= "RxNorm (Ingredient)" (get-in out [:spec :vocabulary])) "vocab inferred from Drug domain")
                 (is (true? (get-in out [:spec :include_descendants])))
                 (is (= 3 (count (:next_steps out))))
                 (is (re-find #"\"metformin\", \"sulfonylurea\"" (first (:next_steps out))))
                 (is (re-find #"DOMAIN_ID=Drug" (first (:next_steps out))))
                 (done))))))

(deftest explicit-vocabulary-wins
  (async done
    (-> (run {:name "x" :clinical_terms ["a"] :vocabulary "LOINC"})
        (.then (fn [out]
                 (is (= "LOINC" (get-in out [:spec :vocabulary])))
                 (done))))))

(deftest requires-name
  (async done
    (-> (run {:clinical_terms ["a"]})
        (.then (fn [out]
                 (is (false? (:ok out)))
                 (is (re-find #"name is required" (first (:errors out))))
                 (done))))))

(deftest requires-terms
  (async done
    (-> (run {:name "x" :clinical_terms []})
        (.then (fn [out]
                 (is (false? (:ok out)))
                 (is (re-find #"clinical_terms is required" (first (:errors out))))
                 (done))))))
