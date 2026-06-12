(ns pythia.tools.search-ohdsi-book-test
  (:require [cljs.test :refer [deftest is async]]
            [pythia.resources :as resources]
            [pythia.tools.search-ohdsi-book :as sob]))

(def ^:private corpus-edn
  (pr-str
   [{:chapter "cohort" :title "Cohort Exit" :section "Exit Strategies"
     :file "exit.md" :url "https://book/exit" :anchor "exit-strategies"
     :text "A cohort exit strategy defines when a person leaves the cohort. Fixed duration washout and censoring are common."}
    {:chapter "vocab" :title "Vocabularies" :section "SNOMED"
     :file "vocab.md" :url "https://book/vocab" :anchor "snomed"
     :text "SNOMED concept set vocabulary mapping for standard concepts."}]))

(defn- mock-read-text! [text]
  (let [orig resources/read-text]
    (sob/reset-cache!)
    (set! resources/read-text (fn [_] (js/Promise.resolve text)))
    (fn []
      (set! resources/read-text orig)
      (sob/reset-cache!))))

(deftest empty-query-short-circuits
  (async done
    (-> (js/Promise.resolve ((:run sob/tool) {:query ""} {}))
        (.then (fn [out]
                 (is (= [] (:results out)))
                 (is (= "empty query" (:note out)))
                 (done))))))

(deftest bm25-ranks-and-shapes
  (async done
    (let [restore (mock-read-text! corpus-edn)]
      (-> ((:run sob/tool) {:query "cohort exit washout"} {})
          (.then (fn [out]
                   (let [hits (:results out)
                         top (first hits)]
                     (is (pos? (count hits)))
                     (is (= "Cohort Exit" (:title top)))
                     (is (= "exit.md" (:file top)))
                     (is (= "https://book/exit#exit-strategies" (:url top)))
                     (is (number? (:score top)))
                     (is (re-find #"cohort exit" (clojure.string/lower-case (:snippet top)))))
                   (restore)
                   (done)))))))

(deftest unavailable-corpus-noted
  (async done
    (let [restore (mock-read-text! nil)]
      (-> ((:run sob/tool) {:query "anything"} {})
          (.then (fn [out]
                   (is (= [] (:results out)))
                   (is (= "book index unavailable" (:note out)))
                   (restore)
                   (done)))))))

(deftest k-limits-results
  (async done
    (let [restore (mock-read-text! corpus-edn)]
      (-> ((:run sob/tool) {:query "concept cohort" :k 1} {})
          (.then (fn [out]
                   (is (>= 1 (count (:results out))))
                   (restore)
                   (done)))))))
