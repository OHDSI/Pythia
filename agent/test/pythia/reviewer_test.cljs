(ns pythia.reviewer-test
  (:require [cljs.test :refer [deftest is]]
            [clojure.string :as str]
            [pythia.prompt :as prompt]
            [pythia.reviewer :as reviewer]
            [pythia.tools :as tools]))

(deftest reviewer-tools-are-existing-server-tools
  ;; A clientOnly tool would end the nested loop with no result.
  (let [server (set (map :name tools/server))]
    (doseq [n reviewer/tool-names]
      (is (contains? server n) (str n " must be a server tool")))))

(deftest reviewer-cannot-change-anything
  (is (not-any? #(re-find #"^(update|add|set|save|create|remove)_" %) reviewer/tool-names)))

(deftest reviewer-prompt-judges-against-the-plan-goal-and-states-a-verdict
  (is (str/includes? reviewer/prompt "plan-goal"))
  (is (str/includes? reviewer/prompt "never a description of the"))
  (doseq [v ["VERDICT: pass" "VERDICT: problems" "VERDICT: not verified"]]
    (is (str/includes? reviewer/prompt v))))

(deftest main-prompt-delegates-review-without-leading-the-reviewer
  (is (str/includes? prompt/base-prompt "`agent: \"reviewer\"`"))
  (is (str/includes? prompt/base-prompt "`Review <kind> <id>.`"))
  (is (str/includes? prompt/base-prompt "Do not describe what you built")))
