(ns pythia.tools.validate-circe
  "validate_circe tool — round-trips a draft Circe JSON CohortExpression
   through the trexsql circe renderer to confirm it parses and compiles to
   SQL. Used BEFORE proposing a non-trivial cohort so Pythia can catch
   malformed expressions and self-correct.

   Port of the JVM trexsql.agent.tools.validate-circe. The JVM tool's PRIMARY
   path was WebAPI POST /cohortdefinition/sql with an in-process renderer as
   fallback. In the mounted node the in-process circe SQL functions are
   reachable directly via globalThis.Trex (see pythia.trex), so that is the
   primary path here; the WebAPI round-trip is the fallback when globalThis.Trex
   is not available in the worker.

   Returns:
     {:ok true  :sql \"<rendered SQL>\" :warnings []}
     {:ok false :errors [\"...\"]}"
  (:require [clojure.string :as str]
            [pythia.trex :as trex]
            [pythia.webapi :as webapi]))

(def schema
  {:type "object"
   :properties {:expression
                {:type "object"
                 :description "Circe CohortExpression JSON (object or JSON string)."}}
   :required ["expression"]})

(def ^:private max-sql 4000)

;; --- pure helpers ---------------------------------------------------------

(defn coerce-expression
  "Args may pass :expression as a JSON string or already-parsed data. Always
   yields a JSON string (nil -> nil)."
  [expr]
  (cond
    (nil? expr) nil
    (string? expr) expr
    :else (js/JSON.stringify (clj->js expr))))

(defn valid-circe-shape?
  "Cheap structural pre-check: does the parsed body look like a Circe
   CohortExpression (PrimaryCriteria with a CriteriaList)?"
  [body]
  (boolean
   (and (map? body)
        (let [pc (or (get body "PrimaryCriteria") (get body :PrimaryCriteria))
              cl (and (map? pc) (or (get pc "CriteriaList") (get pc :CriteriaList)))]
          (sequential? cl)))))

(defn pre-parse
  "Parse + structural pre-check. Returns {:ok true :body data} or
   {:ok false :errors [...]}."
  [expr-json]
  (let [body (try (js->clj (js/JSON.parse expr-json) :keywordize-keys false)
                  (catch :default e {::parse-error (or (.-message e) (str e))}))]
    (cond
      (and (map? body) (contains? body ::parse-error))
      {:ok false :errors [(str "expression is not valid JSON: " (::parse-error body))]}

      (not (valid-circe-shape? body))
      {:ok false
       :errors ["expression has no PrimaryCriteria.CriteriaList — does not look like a Circe CohortExpression"]}

      :else
      {:ok true :body body})))

(defn circe-error?
  "circe returns errors inline as a string starting with `/* circe error`."
  [s]
  (boolean (and (string? s)
                (str/starts-with? (str/triml s) "/* circe error"))))

(defn b64-encode
  "UTF-8-safe base64 of `s`. circe rejects raw JSON; the expression arg must be
   base64-encoded."
  [s]
  (let [bytes (.encode (js/TextEncoder.) s)
        binary (->> bytes (map (fn [b] (js/String.fromCharCode b))) (apply str))]
    (js/btoa binary)))

(defn sql-escape
  "Escape single quotes for a SQL string literal."
  [s]
  (str/replace (str s) "'" "''"))

(def ^:private options-json
  "Validate-only options. Schemas just need to be non-blank; the rendered SQL
   is never executed."
  (js/JSON.stringify
   #js {:cdmSchema "main.cdm"
        :resultSchema "main.results"
        :targetTable "cohort"
        :cohortId 1
        :generateStats false}))

(defn shape-render-result
  "Shape a non-error rendered SQL string into the JVM tool's success map."
  [sql]
  {:ok true
   :sql (subs sql 0 (min max-sql (count sql)))
   :sql-truncated? (> (count sql) max-sql)
   :warnings []})

;; --- in-process (primary) -------------------------------------------------

(defn- render-in-process [expr-json]
  (let [b64 (b64-encode expr-json)
        sql (str "SELECT circe_sql_render_translate("
                 "circe_json_to_sql('" (sql-escape b64) "', '" (sql-escape options-json) "'), "
                 "'duckdb', '{}') AS sql")]
    (-> (trex/query sql)
        (.then (fn [result]
                 (let [s (str result)]
                   (cond
                     (str/blank? s)
                     {:ok false :errors ["in-process circe renderer returned an empty result"]}

                     (circe-error? s)
                     {:ok false :errors [(str/trim s)]}

                     :else
                     (shape-render-result s))))))))

;; --- WebAPI (fallback) ----------------------------------------------------

(defn- render-via-webapi [expr-json auth]
  (-> (webapi/request-status "POST" "/cohortdefinition/sql"
                             {:auth auth
                              :body {:expression expr-json
                                     :options {:cdmSchema "@cdm_database_schema"
                                               :resultSchema "@results_database_schema"
                                               :targetTable "@target_cohort_table"
                                               :targetCohortId 0}}})
      (.then (fn [{:keys [status body]}]
               (cond
                 (= 200 status)
                 (let [sql (or (:templateSql body)
                               (:parameterizedSql body)
                               (when (string? body) body))]
                   (if (string? sql)
                     (shape-render-result sql)
                     {:ok false :errors ["WebAPI /cohortdefinition/sql returned no SQL"]}))

                 (and status (>= status 400))
                 (let [msg (cond
                             (string? body) body
                             (map? body) (or (:message body) (:error body) (str body))
                             :else (str body))]
                   {:ok false :errors [msg] :http-status status})

                 :else
                 {:ok false
                  :errors ["WebAPI /cohortdefinition/sql returned no usable response"]
                  :http-status status})))))

(defn run
  "Tool entrypoint. `args` is {:expression <json|object>}; `ctx` carries :auth."
  [args ctx]
  (let [raw (or (:expression args) (:circeJson args))
        expr-json (coerce-expression raw)
        auth (:auth ctx)]
    (if (str/blank? (str expr-json))
      (js/Promise.resolve {:ok false :errors ["expression is required"]})
      (let [pre (pre-parse expr-json)]
        (if-not (:ok pre)
          (js/Promise.resolve pre)
          (-> (if (trex/available?)
                (render-in-process expr-json)
                (render-via-webapi expr-json auth))
              (.catch (fn [e]
                        {:ok false
                         :errors [(str "validate_circe failed: " (or (.-message e) e))]}))))))))

(def tool
  {:name "validate_circe"
   :description "Validate a draft Circe CohortExpression by compiling it to SQL. Pass `:expression` (the Circe JSON object or string). Returns `{:ok true :sql <rendered SQL>}` on success or `{:ok false :errors [...]}` if the expression is malformed or fails to compile. Call this BEFORE proposing a non-trivial cohort so you can catch errors and self-correct."
   :schema schema
   :run run})
