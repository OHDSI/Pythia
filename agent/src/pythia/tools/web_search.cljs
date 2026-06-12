(ns pythia.tools.web-search
  "web_search tool — DuckDuckGo Lite scrape, no API key required. POSTs a
   form-encoded `q=<query>` to https://lite.duckduckgo.com/lite/, regexes out
   the anchor + snippet pairs, and returns the top N results. Port of the JVM
   trexsql.agent.tools.web-search (HTML extraction replicated via regex)."
  (:require [clojure.string :as str]
            [pythia.http :as http]))

(def ^:private ddg-url "https://lite.duckduckgo.com/lite/")

(def ^:private link-rx
  (js/RegExp. "<a[^>]+href=[\"']([^\"']+)[\"'][^>]+class=[\"']result-link[\"'][^>]*>([^<]*)</a>" "gs"))

(def ^:private snippet-rx
  (js/RegExp. "<td class=[\"']result-snippet[\"']>([\\s\\S]*?)</td>" "gs"))

(def ^:private tag-rx (js/RegExp. "<[^>]*>" "gs"))

(def schema
  {:type "object"
   :properties {:query {:type "string"
                        :description "What to search for. Use specific terms; for clinical phenotype validation include 'OHDSI', 'OMOP', 'PheKB', or 'phenotype' to bias toward relevant sources."}
                :num_results {:type "number"
                              :description "Number of results to return (default 5, max 10)."}}
   :required ["query"]})

(defn- strip-tags [s]
  (-> (or s "")
      (str/replace tag-rx "")
      str/trim))

(defn- re-seq-js
  "All matches of a global JS regex against `s`, each as a vector of capture
   groups [g1 g2 ...]."
  [rx s]
  (let [r (js/RegExp. (.-source rx) (.-flags rx))]
    (loop [acc []]
      (if-let [m (.exec r (or s ""))]
        (recur (conj acc (vec (rest (array-seq m)))))
        acc))))

(defn parse-html
  "Parse a DuckDuckGo Lite results page into a vector of result maps. Pure
   helper for unit testing."
  [html n]
  (let [links (->> (re-seq-js link-rx html)
                   (mapv (fn [[url title]] {:url url :title (str/trim (or title ""))})))
        snippets (->> (re-seq-js snippet-rx html)
                      (mapv (fn [[s]] (strip-tags s))))]
    (->> (map vector links (concat snippets (repeat "")))
         (take n)
         (mapv (fn [[l s]] (assoc l :snippet s))))))

(defn- clamp-n [raw-n]
  (let [n (if (number? raw-n) (long raw-n) 5)]
    (-> n (max 1) (min 10))))

(defn run
  "Tool entrypoint. `args` is {:query <string> :num_results <number>?}.
   Returns {:results [...]} on success, or {:error <string>} on failure /
   blank query."
  [args _ctx]
  (let [query (str/trim (str (or (:query args) "")))
        n (clamp-n (:num_results args))]
    (if (str/blank? query)
      (js/Promise.resolve {:error "query is required"})
      (-> (http/post-form ddg-url
                          {:form {:q query}
                           :headers {"User-Agent" "Mozilla/5.0 (compatible; Pythia/1.0)"
                                     "Accept" "text/html"}})
          (.then (fn [{:keys [status text]}]
                   (let [results (parse-html (or text "") n)]
                     (cond
                       (not= 200 status)
                       {:error (str "DuckDuckGo returned HTTP " status)}

                       (empty? results)
                       {:results [] :note (str "No results for: " query)}

                       :else
                       {:results results}))))
          (.catch (fn [e]
                    {:error (str "web_search failed: " (or (.-message e) e))}))))))

(def tool
  {:name "web_search"
   :description "Search the open web (DuckDuckGo) for clinical guidelines, published literature, OHDSI documentation, drug-class definitions, etc. Use sparingly: prefer local lookups (search_concepts, search_phenotypes, search_existing_*) for things in the OMOP vocabulary or already-saved artifacts. Returns top-N results with title, URL, and snippet."
   :schema schema
   :run run})
