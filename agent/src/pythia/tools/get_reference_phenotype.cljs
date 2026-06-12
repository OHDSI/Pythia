(ns pythia.tools.get-reference-phenotype
  "get_reference_phenotype tool — resolves a single reference phenotype by source:

   - OHDSI Phenotype Library (default / :source \"ohdsi-library\"): looks the id
     up in the bundled cohorts index for metadata + :summary, and fetches the
     full Circe JSON body from raw.githubusercontent.com (pinned to v3.37.0).
     Restores the JVM trexsql.agent.tools.get-reference-phenotype index-enrichment
     (the on-disk path has no worker equivalent; GitHub-raw is the body source).
   - HDR UK (:source \"hdruk\"): fetches /detail/ metadata + /export/codes/ clinical
     codes (capped) per the HDR UK API spike.

   Both live paths are fail-soft."
  (:require [clojure.string :as str]
            [pythia.http :as http]
            [pythia.phenotype-sources :as ps]))

(def ^:private library-tag "v3.37.0")
(def ^:private max-codes 50)

(def schema
  {:type "object"
   :properties {:cohortId {:type "number" :description "OHDSI Phenotype Library cohort id (the numeric `:id` returned in an `ohdsi-library` search_phenotypes hit)."}
                :id {:type "string" :description "Source-native id (e.g. an HDR UK phenotype id like \"PH6\"). Use with :source."}
                :source {:type "string" :description "Source to resolve against: \"ohdsi-library\" (default) or \"hdruk\"."}}
   :required []})

(defn- parse-id [raw]
  (cond
    (number? raw) (long raw)
    (string? raw) (let [n (js/parseInt (str/trim raw) 10)]
                    (when-not (js/isNaN n) n))
    :else nil))

(defn- github-raw-url [id]
  (str "https://raw.githubusercontent.com/OHDSI/PhenotypeLibrary/"
       library-tag "/inst/cohorts/" id ".json"))

(defn- github-blob-url [id]
  (str "https://github.com/OHDSI/PhenotypeLibrary/blob/"
       library-tag "/inst/cohorts/" id ".json"))

(def ^:private summary-keys
  [:entry-domains :n-primary-criteria :n-inclusion-rules :n-concept-sets
   :concept-sets :exit-strategy :censor-criteria? :primary-event-limit
   :expression-limit])

(defn- resolve-ohdsi
  "OHDSI Library: index metadata + GitHub-raw Circe body. Restores the JVM
   index enrichment (:name/:description/:status/:tags/:summary)."
  [id]
  (-> (ps/load-index)
      (.then (fn [index]
               (let [meta (get (ps/index-by-id index) id)]
                 (-> (http/get-json (github-raw-url id) {})
                     (.then (fn [{:keys [status body]}]
                              (if (= 200 status)
                                {:cohortId id
                                 :source "ohdsi-library"
                                 :tag library-tag
                                 :name (:name meta)
                                 :description (:description meta)
                                 :status (:status meta)
                                 :tags (:tags meta)
                                 :summary (when meta (select-keys meta summary-keys))
                                 :body body
                                 :url (github-blob-url id)}
                                {:error (str "cohort " id " not found via GitHub")
                                 :tag library-tag})))))))
      (.catch (fn [e]
                {:error (str "get_reference_phenotype failed: " (or (.-message e) e))
                 :tag library-tag}))))

(defn- ->code [c]
  {:code (:code c)
   :description (:description c)
   :coding-system (get-in c [:attributes :coding_system])})

(defn- resolve-hdruk
  "HDR UK: /detail/ metadata + /export/codes/ clinical codes (capped). Fail-soft."
  [id]
  (let [base (str ps/hdruk-base "/api/v1/phenotypes/" id)]
    (-> (js/Promise.all
         #js [(http/get-json (str base "/detail/")
                             {:headers {"Accept" "application/json"}})
              (http/get-json (str base "/export/codes/")
                             {:headers {"Accept" "application/json"}})])
        (.then (fn [pair]
                 (let [[detail codes] (array-seq pair)]
                   (if (= 200 (:status detail))
                     (let [d (:body detail)
                           code-list (when (= 200 (:status codes)) (:body codes))]
                       {:source "hdruk"
                        :id id
                        :name (:name d)
                        :description (some-> (:publications d) first :details)
                        :status (:status d)
                        :coding-system (:coding_system d)
                        :publications (:publications d)
                        :codes (->> (or code-list []) (take max-codes) (mapv ->code))
                        :url (str ps/hdruk-base "/phenotypes/" id)})
                     {:error (str "HDR UK phenotype " id " not found")}))))
        (.catch (fn [e]
                  {:error (str "get_reference_phenotype failed: " (or (.-message e) e))})))))

(defn run
  "Tool entrypoint. Args:
   - OHDSI: {:cohortId 1234} (or {:source \"ohdsi-library\" :id \"1234\"})
   - HDR UK: {:source \"hdruk\" :id \"PH6\"}"
  [args _ctx]
  (let [source (some-> (:source args) str/lower-case)]
    (if (= source "hdruk")
      (let [id (or (:id args) (:cohortId args))]
        (if (str/blank? (str id))
          (js/Promise.resolve {:error "id is required for HDR UK"})
          (resolve-hdruk (str id))))
      ;; default / ohdsi-library
      (let [id (parse-id (or (:cohortId args) (:id args)))]
        (if (nil? id)
          (js/Promise.resolve {:error "cohortId is required (numeric)"})
          (resolve-ohdsi id))))))

(def tool
  {:name "get_reference_phenotype"
   :description "Fetch the full reference definition of a single phenotype returned by search_phenotypes. For an OHDSI Phenotype Library hit (default, or `:source \"ohdsi-library\"` with the numeric `:cohortId`), returns the full Circe JSON body plus catalog metadata (name, status, tags) and a structural `:summary` (entry domains, criteria counts, concept sets). For an HDR UK hit (`:source \"hdruk\"` with the `:id`, e.g. \"PH6\"), returns the phenotype metadata, publications, and clinical codes (code, description, coding system). Use after search_phenotypes when you want to study or mirror a definition in detail."
   :schema schema
   :run run})
