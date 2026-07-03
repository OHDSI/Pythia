(ns pythia.prompt-test
  (:require [cljs.test :refer [deftest is]]
            [clojure.string :as str]
            [pythia.prompt :as prompt]))

(deftest base-prompt-mentions-cohort
  (is (string? prompt/base-prompt))
  (is (str/includes? (str/lower-case prompt/base-prompt) "cohort")))

(deftest base-prompt-introduces-pythia
  (is (str/includes? prompt/base-prompt "PYTHIA")))

(deftest base-prompt-restored-sections-present
  ;; Sections that a prior porting pass had truncated; assert they are back.
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
                  "### Characterization prerequisite branch (IMPORTANT)"
                  "## Request context format"]]
    (is (str/includes? prompt/base-prompt header) (str "missing section: " header)))
  ;; format-views interpolation produced the navigate_to view bullets.
  (is (str/includes? prompt/base-prompt "- `cohort-edit` — params: id — Cohort editor"))
  (is (str/includes? prompt/base-prompt "- `home` — Home")))

(deftest routes-through-select-plan-template
  (is (re-find #"select_plan_template" prompt/base-prompt)
      "prompt must instruct the model to select a template first"))

(deftest base-prompt-covers-reviewing-own-work
  (is (str/includes? prompt/base-prompt "## Reviewing your own work"))
  (is (str/includes? prompt/base-prompt "review_plan"))
  (is (str/includes? prompt/base-prompt "review_artifact"))
  (is (str/includes? prompt/base-prompt "not a proposal tool"))
  (is (str/includes? prompt/base-prompt "select_plan_template runs")))

(deftest base-prompt-documents-request-context-format
  ;; context-block/plan-block are gone (P3): the trex runtime appends the
  ;; <context> JSON itself. base-prompt now statically documents its shape
  ;; and preserves the old plan-block "current step" semantics in prose.
  (is (str/includes? prompt/base-prompt "sourceKey"))
  (is (str/includes? prompt/base-prompt "\"route\""))
  (is (str/includes? prompt/base-prompt "\"artifact\""))
  (is (str/includes? prompt/base-prompt "\"plan\""))
  (is (str/includes? prompt/base-prompt "\"steps\""))
  (is (str/includes? prompt/base-prompt "current step")
      "must preserve plan-block's current-step semantics")
  (is (str/includes? prompt/base-prompt "required step whose")))
