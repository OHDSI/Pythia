(ns pythia.tools.review-plan-test
  (:require [cljs.test :refer [deftest is testing]]
            [pythia.tools.review-plan :as rp]))

(def ^:private rich-doc
  "## Goal\nBuild a cohort.\n\n## Success criteria\nCohort is saved.")

(deftest errors-when-no-active-plan
  (is (= "no active plan to review — call select_plan_template or create_plan first"
         (:error ((:run rp/tool) {:covers_request true :verdict "approved"} {})))))

(deftest errors-when-plan-has-no-steps
  (is (contains? ((:run rp/tool) {:covers_request true :verdict "approved"}
                                 {:plan {:steps []}})
                 :error)))

(deftest flags-missing-document-on-multi-step-plan
  (is (some #(re-find #"no :document" %)
            (rp/structural-issues {:steps [{:id "a"} {:id "b"}]}))))

(deftest single-step-plan-does-not-require-a-document
  (is (empty? (rp/structural-issues {:steps [{:id "a"}]}))))

(deftest flags-missing-goal-section
  (is (some #(re-find #"## Goal" %)
            (rp/structural-issues {:steps [{:id "a"} {:id "b"}]
                                    :document "## Success criteria\nDone."}))))

(deftest flags-missing-success-criteria-section
  (is (some #(re-find #"## Success criteria" %)
            (rp/structural-issues {:steps [{:id "a"} {:id "b"}]
                                    :document "## Goal\nDo the thing."}))))

(deftest no-issues-for-a-well-formed-multi-step-plan
  (is (empty? (rp/structural-issues {:steps [{:id "a"} {:id "b"}]
                                      :document rich-doc}))))

(deftest flags-duplicate-step-ids
  (is (some #(re-find #"duplicate step ids" %)
            (rp/structural-issues {:steps [{:id "a"} {:id "a"}] :document rich-doc}))))

(deftest passes-through-model-reported-fields-and-approves-when-clean
  (let [plan {:steps [{:id "a"} {:id "b"}] :document rich-doc}
        out ((:run rp/tool)
             {:covers_request true :gaps [] :risks ["ambiguous washout"] :verdict "approved"}
             {:plan plan})]
    (is (true? (:ok out)))
    (is (empty? (:structural-issues out)))
    (is (= {:covers-request true :gaps [] :risks ["ambiguous washout"] :verdict "approved"}
           (:model-reported out)))
    (is (re-find #"continue to the first not-done required step" (:instruction out)))))

(deftest needs-revision-verdict-triggers-fix-instruction
  (let [plan {:steps [{:id "a"} {:id "b"}] :document rich-doc}
        out ((:run rp/tool)
             {:covers_request false :gaps ["no exit criteria"] :risks [] :verdict "needs_revision"}
             {:plan plan})]
    (is (re-find #"Fix the plan" (:instruction out)))))

(deftest structural-issues-force-fix-instruction-even-when-approved
  (let [plan {:steps [{:id "a"} {:id "b"}]} ; no document -> structural issue
        out ((:run rp/tool)
             {:covers_request true :gaps [] :risks [] :verdict "approved"}
             {:plan plan})]
    (is (seq (:structural-issues out)))
    (is (re-find #"Fix the plan" (:instruction out)))))

(deftest missing-verdict-defaults-to-needs-revision
  (let [plan {:steps [{:id "a"}]}
        out ((:run rp/tool) {:covers_request true} {:plan plan})]
    (is (= "needs_revision" (get-in out [:model-reported :verdict])))))
