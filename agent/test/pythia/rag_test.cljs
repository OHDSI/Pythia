(ns pythia.rag-test
  (:require [cljs.test :refer [deftest is testing]]
            [pythia.rag :as rag]))

(deftest tokenize-lowercases-splits-and-drops-stopwords
  (is (= ["washout" "period"] (rag/tokenize "The Washout Period")))
  (is (= ["covid" "19"] (rag/tokenize "COVID-19")))
  (is (= [] (rag/tokenize "the and of"))))

(def ^:private docs
  [{:id 1 :text "washout period censoring exit strategy"}
   {:id 2 :text "incidence rate denominator population time at risk"}
   {:id 3 :text "concept set vocabulary mapping snomed"}])

(deftest exact-term-doc-ranks-first
  (let [corpus (rag/build-corpus docs :text)
        hits (rag/bm25-search corpus "washout period" 3)]
    (is (pos? (count hits)))
    (is (= 1 (:id (:doc (first hits)))) "doc with the query terms ranks first")
    (is (> (:score (first hits)) 0))))

(deftest filters-zero-score-docs
  (let [corpus (rag/build-corpus docs :text)
        hits (rag/bm25-search corpus "snomed" 5)]
    (is (= 1 (count hits)))
    (is (= 3 (:id (:doc (first hits)))))))

(deftest internal-keys-stripped-from-results
  (let [corpus (rag/build-corpus docs :text)
        hit (first (rag/bm25-search corpus "incidence" 1))]
    (is (= 2 (:id (:doc hit))))
    (is (not (contains? (:doc hit) :_tf)))
    (is (not (contains? (:doc hit) :_len)))))

(deftest respects-k-limit
  (let [corpus (rag/build-corpus docs :text)
        ;; a term in every doc-ish; use a query hitting two docs
        hits (rag/bm25-search corpus "washout incidence" 1)]
    (is (= 1 (count hits)))))
