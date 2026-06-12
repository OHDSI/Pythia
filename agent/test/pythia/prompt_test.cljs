(ns pythia.prompt-test
  (:require [cljs.test :refer [deftest is]]
            [clojure.string :as str]
            [pythia.prompt :as prompt]))

(deftest system-prompt-mentions-cohort
  (let [s (prompt/system-prompt {:route "/atlas/#/cohortdefinitions" :artifact nil})]
    (is (string? s))
    (is (str/includes? (str/lower-case s) "cohort"))))

(deftest system-prompt-introduces-pythia
  (let [s (prompt/system-prompt {:route nil :artifact nil})]
    (is (str/includes? s "PYTHIA"))))

(deftest system-prompt-injects-route-context
  (let [s (prompt/system-prompt {:route "/atlas/#/cohortdefinitions" :artifact nil})]
    (is (str/includes? s "Current context"))
    (is (str/includes? s "/atlas/#/cohortdefinitions"))))

(deftest system-prompt-injects-artifact-context
  (let [s (prompt/system-prompt {:route "/atlas/#/cohortdefinition/42"
                                 :artifact {:kind "cohort" :id 42 :name "T2DM"}})]
    (is (str/includes? s "cohort"))
    (is (str/includes? s "T2DM"))
    (is (str/includes? s "42"))))

(deftest system-prompt-no-context-block-when-empty
  ;; The static prompt legitimately mentions "## Current context" in its
  ;; "Current screen awareness" prose; the dynamic block is only appended when
  ;; route/artifact are present. Assert the dynamic block's distinctive prose
  ;; is absent rather than the header text.
  (let [s (prompt/system-prompt {:route nil :artifact nil})]
    (is (not (str/includes? s "The user is currently here.")))))

(deftest system-prompt-restored-sections-present
  ;; Sections that a prior porting pass had truncated; assert they are back.
  (let [s (prompt/system-prompt {:route nil :artifact nil})]
    (doseq [header ["## Phenotype Design Workflow"
                    "## OHDSI Conventions"
                    "## Diagnostic Interpretation"
                    "## Limited Vocabulary Fallback"
                    "## Rules"
                    "## Plans"
                    "## Web research"
                    "## Visual style"
                    "## ATLAS v3.0 cohort model (Phase B tools)"
                    "## Asking the user"
                    "## Current screen awareness — read before you edit"
                    "## Where the user is, and how to navigate"
                    "## Analysis types — feature analyses, characterizations, pathways, incidence rates"
                    "### Characterization prerequisite branch (IMPORTANT)"]]
      (is (str/includes? s header) (str "missing section: " header)))
    ;; format-views interpolation produced the navigate_to view bullets.
    (is (str/includes? s "- `cohort-edit` — params: id — Cohort editor"))
    (is (str/includes? s "- `home` — Home"))))
