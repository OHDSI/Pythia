(ns pythia.rag
  "Pure BM25 retrieval primitives, ported from the JVM trexsql.agent.rag /
   search-ohdsi-book scorer (k1=1.2, b=0.75). No IO — callers load the corpus
   docs and hand them in."
  (:require [clojure.string :as str]))

(def ^:private k1 1.2)
(def ^:private b 0.75)

(def stopwords
  #{"a" "an" "the" "and" "or" "but" "if" "of" "to" "in" "on" "for" "with"
    "is" "are" "was" "were" "be" "been" "being" "this" "that" "these"
    "those" "it" "its" "as" "at" "by" "from" "we" "you" "they" "i" "he"
    "she" "their" "his" "her" "our" "your" "do" "does" "did" "have" "has"
    "had" "will" "would" "should" "can" "could" "may" "might" "must" "not"
    "no" "yes" "than" "then" "so" "such"})

(defn tokenize [s]
  (->> (str/split (str/lower-case (str s)) #"[^a-z0-9]+")
       (remove str/blank?)
       (remove stopwords)
       vec))

(defn build-corpus
  "Build an immutable BM25 corpus from `docs`. `text-fn` extracts the searchable
   text from each doc. Returns {:docs :n :df :avg-len}; each doc keeps its
   original keys plus internal :_tf/:_len."
  [docs text-fn]
  (let [tokenized (mapv (fn [d]
                          (let [toks (tokenize (text-fn d))]
                            (assoc d :_tf (frequencies toks)
                                     :_len (max 1 (count toks)))))
                        docs)
        n (count tokenized)
        df (reduce (fn [acc d]
                     (reduce (fn [a t] (update a t (fnil inc 0))) acc (keys (:_tf d))))
                   {}
                   tokenized)
        avg-len (if (pos? n)
                  (/ (reduce + (map :_len tokenized)) (double n))
                  1.0)]
    {:docs tokenized :n n :df df :avg-len avg-len}))

(defn- idf [df n term]
  (let [d (get df term 0)]
    #?(:clj  (Math/log (+ 1.0 (/ (- n d 0.5) (+ d 0.5))))
       :cljs (js/Math.log (+ 1.0 (/ (- n d 0.5) (+ d 0.5)))))))

(defn bm25-score [doc query-terms df n avg-len]
  (let [{:keys [_tf _len]} doc]
    (reduce
     (fn [score t]
       (let [f (get _tf t 0)]
         (if (zero? f)
           score
           (let [w (idf df n t)
                 num (* f (+ k1 1))
                 den (+ f (* k1 (+ 1 (- b) (* b (/ _len avg-len)))))]
             (+ score (* w (/ num den)))))))
     0.0
     query-terms)))

(defn bm25-search
  "Score every doc in `corpus` against `query`; return the top-`k` as
   [{:doc <doc without internal keys> :score}], highest score first, only
   positive-scoring docs."
  ([corpus query] (bm25-search corpus query 5))
  ([corpus query k]
   (let [{:keys [docs n df avg-len]} corpus
         qterms (tokenize query)]
     (->> docs
          (map (fn [d] {:doc (dissoc d :_tf :_len)
                        :score (bm25-score d qterms df n avg-len)}))
          (filter (fn [{:keys [score]}] (pos? score)))
          (sort-by (fn [{:keys [score]}] (- score)))
          (take k)
          vec))))
