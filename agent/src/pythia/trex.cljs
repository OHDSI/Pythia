(ns pythia.trex
  "In-process trexsql access from the mounted Deno worker via globalThis.Trex.
   Mirrors devx's duckdb.ts: lease a fresh in-memory DuckDB session, run one
   query, and ALWAYS close it in a finally — the 64-slot session pool wedges
   the node otherwise.")

(defn available?
  "True if globalThis.Trex (the in-process trexsql DB API) is present in this
   worker. The in-process circe SQL functions are only reachable when it is."
  []
  (boolean
   (some-> (unchecked-get js/globalThis "Trex"))))

(defn- db-conn
  "Lease a fresh in-memory DuckDB connection. Caller MUST close it."
  []
  (let [^js trex (unchecked-get js/globalThis "Trex")
        ^js dbm (.databaseManager trex)
        ^js wrapper (.getConnection dbm "memory" "main" "main" "main" #js {})]
    (unchecked-get wrapper "connection")))

(defn query
  "Run `sql` against the in-memory DuckDB instance and resolve the first row's
   first column (`column0`, the devx convention). Leases one pool session and
   ALWAYS closes it. Returns a Promise of the string result (or \"\")."
  [sql]
  (let [^js conn (db-conn)]
    (-> (js/Promise.resolve (.execute conn sql))
        (.then (fn [rows]
                 (or (some-> rows (aget 0) (unchecked-get "column0")) "")))
        (.finally (fn []
                    (try (.close conn) (catch :default _ nil)))))))
