(ns pythia.tools.search-ohdsi-studies
  "search_ohdsi_studies tool — on-demand search across the public
   `ohdsi-studies` GitHub org via the GitHub search API. Port of the JVM
   trexsql.agent.tools.search-ohdsi-studies. Optional GITHUB_TOKEN /
   BAO_GITHUB_TOKEN raises the rate limit."
  (:require [clojure.string :as str]
            [pythia.http :as http]))

(def ^:private github-search-url "https://api.github.com/search/repositories")

(def schema
  {:type "object"
   :properties {:query {:type "string" :description "Study topic, condition, or method name (e.g. 'covid vaccine safety', 'antidepressant comparative effectiveness')."}}
   :required ["query"]})

(defn- env [k]
  (or (when (exists? js/Deno) (.. js/Deno -env (get k)))
      (when (exists? js/process) (unchecked-get (unchecked-get js/process "env") k))))

(defn- bearer-token []
  (or (env "GITHUB_TOKEN") (env "BAO_GITHUB_TOKEN")))

(defn- to-result [repo]
  {:source "ohdsi-studies"
   :name (:full_name repo)
   :title (:name repo)
   :description (or (:description repo) "")
   :url (:html_url repo)
   :stars (:stargazers_count repo)
   :topics (or (:topics repo) [])
   :updated (:updated_at repo)})

(defn run [args _ctx]
  (let [query (str (or (:query args) ""))]
    (if (str/blank? query)
      (js/Promise.resolve {:results [] :note "empty query"})
      (let [q (str query " org:ohdsi-studies in:name,description,readme")
            token (bearer-token)
            headers (cond-> {"Accept" "application/vnd.github+json"
                             "X-GitHub-Api-Version" "2022-11-28"}
                      token (assoc "Authorization" (str "Bearer " token)))]
        (-> (http/get-json github-search-url
                           {:query {:q q :per_page 8 :sort "stars"}
                            :headers headers})
            (.then (fn [{:keys [status body]}]
                     (let [hits (cond
                                  (= 200 status) (or (:items body) [])
                                  :else [])]
                       {:results (mapv to-result hits)
                        :note (when (empty? hits)
                                "No matches in ohdsi-studies — try a broader query, or check that GITHUB_TOKEN is set if rate-limited.")})))
            (.catch (fn [e]
                      {:results [] :note (str "GitHub search failed: " (or (.-message e) e))})))))))

(def tool
  {:name "search_ohdsi_studies"
   :description "On-demand search across the ~187 repos in the public `ohdsi-studies` GitHub organization (network studies, study packages, protocols). Use when the user asks about a published OHDSI study, a network protocol, or wants a template for a new study. Returns repo name, description, URL, stars, and topics. Uses GitHub's search API; set GITHUB_TOKEN to raise rate limits."
   :schema schema
   :run run})
