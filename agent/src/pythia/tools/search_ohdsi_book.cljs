(ns pythia.tools.search-ohdsi-book
  "search_ohdsi_book tool — BM25 retrieval over the Book of OHDSI (2nd Edition),
   chunked by H2 heading. Methodology grounding (washout, censoring, exit
   strategies, study design) so recommendations cite canonical OHDSI text.

   Port of the JVM trexsql.agent.tools.search-ohdsi-book. The corpus ships as
   plain EDN at resources/book-of-ohdsi/passages.edn and is read once + cached."
  (:require [clojure.string :as str]
            [cljs.reader :as reader]
            [pythia.rag :as rag]
            [pythia.resources :as resources]))

(def ^:private default-k 3)

(def ^:private corpus-cache (atom nil))

(defn reset-cache! "Test hook: clear the cached corpus." [] (reset! corpus-cache nil))

(defn- load-corpus
  "Read + parse the passages EDN once, build the BM25 corpus, cache it.
   Returns a Promise of the corpus map (possibly empty)."
  []
  (if-let [c @corpus-cache]
    (js/Promise.resolve c)
    (-> (resources/read-text "book-of-ohdsi/passages.edn")
        (.then (fn [text]
                 (let [docs (if (str/blank? (str text))
                              []
                              (try (vec (reader/read-string text))
                                   (catch :default e
                                     (js/console.warn "[search_ohdsi_book] EDN parse failed:"
                                                      (or (.-message e) e))
                                     [])))
                       corpus (rag/build-corpus docs :text)]
                   (reset! corpus-cache corpus)
                   corpus))))))

(def schema
  {:type "object"
   :properties {:query {:type "string" :description "Methodology question or topic, e.g. \"exit strategy continuous drug exposure\" or \"index date washout\"."}
                :k {:type "number" :description "Number of passages to return (default 3, max 10)."}}
   :required ["query"]})

(defn- snippet
  "Up to ~340 chars centred on the first matching query term."
  [text query-terms]
  (let [t (str text)
        n (count t)
        lower (str/lower-case t)
        hit (some (fn [term]
                    (let [i (str/index-of lower term)]
                      (when i [term i])))
                  query-terms)
        centre (or (some-> hit second) 0)
        start (max 0 (- centre 80))
        end (min n (+ centre 260))
        prefix (if (pos? start) "…" "")
        suffix (if (< end n) "…" "")]
    (str prefix (subs t start end) suffix)))

(defn- round3 [x]
  (/ (js/Math.round (* x 1000)) 1000))

(defn- shape-hit [{:keys [doc score]} qterms]
  {:chapter (:chapter doc)
   :title (:title doc)
   :section (:section doc)
   :file (:file doc)
   :score (round3 score)
   :snippet (snippet (:text doc) qterms)
   :url (str (:url doc) (when-not (str/blank? (:anchor doc))
                          (str "#" (:anchor doc))))})

(defn run
  "Tool entrypoint. Args: {:query \"...\" :k 3}."
  [args _ctx]
  (let [query (str (or (:query args) ""))
        k (max 1 (min 10 (long (or (:k args) default-k))))]
    (if (str/blank? query)
      (js/Promise.resolve {:results [] :note "empty query"})
      (-> (load-corpus)
          (.then (fn [corpus]
                   (let [qterms (rag/tokenize query)]
                     (cond
                       (zero? (:n corpus))
                       {:results [] :note "book index unavailable"}

                       (empty? qterms)
                       {:results [] :note "no searchable terms after stopword filtering"}

                       :else
                       {:results (mapv #(shape-hit % qterms)
                                       (rag/bm25-search corpus query k))}))))
          (.catch (fn [e]
                    {:results [] :note (str "search_ohdsi_book failed: " (or (.-message e) e))}))))))

(def tool
  {:name "search_ohdsi_book"
   :description "Search The Book of OHDSI (2nd Edition) for canonical guidance on OMOP CDM, cohort definition, study design, washout/exit/censoring patterns, vocabularies, and methodology. Returns BM25-ranked passages with chapter, section, snippet, and URL. Call this for ANY methodology question (\"how do I handle washout\", \"what's the OHDSI exit-strategy convention\", \"explain incidence rate denominators\") and cite the chapter/section in your reply."
   :schema schema
   :run run})
