(ns pythia.artifacts-test
  (:require [cljs.test :refer [deftest is]]
            [pythia.artifacts :as artifacts]))

(deftest kind->path-covers-all-six-kinds
  (is (= #{"cohort" "concept_set" "feature_analysis"
           "characterization" "pathway" "incidence_rate"}
         (set (keys artifacts/kind->path)))))

(deftest kind->path-builds-expected-paths
  (is (= "/cohortdefinition/5" ((artifacts/kind->path "cohort") 5)))
  (is (= "/conceptset/9/expression" ((artifacts/kind->path "concept_set") 9)))
  (is (= "/feature-analysis/3" ((artifacts/kind->path "feature_analysis") 3)))
  (is (= "/cohort-characterization/7/design" ((artifacts/kind->path "characterization") 7)))
  (is (= "/pathway-analysis/2" ((artifacts/kind->path "pathway") 2)))
  (is (= "/ir/11" ((artifacts/kind->path "incidence_rate") 11))))

(deftest unknown-kind-returns-nil
  (is (nil? (artifacts/kind->path "bogus"))))
