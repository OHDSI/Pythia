# Spike 0.2 — `globalThis.Trex` circe/db API for the `validate_circe` tool

Date: 2026-06-11
Status: DONE
Chosen path: **(a) in-process via `globalThis.Trex` DB connection, calling circe DuckDB SQL functions.**

## TL;DR / recommended call

CIRCE rendering is exposed as **DuckDB scalar SQL functions** in the trexsql engine.
A Deno function renders a cohort expression to SQL entirely in-process via the same
`globalThis.Trex.databaseManager()` → memory connection pattern that `devx`'s
`duckdb.ts` already uses. No HTTP, no WebAPI, no auth gate.

The SQL the new tool must run (this is exactly what the JVM tool does in
`trexsql.circe/render-circe-sql`, `circe.clj:154-173`):

```sql
SELECT circe_sql_render_translate(
         circe_json_to_sql(<base64(expressionJson)>, <optionsJson>),
         'duckdb', '{}'
       ) AS sql
```

- First arg to `circe_json_to_sql` is the CIRCE CohortExpression JSON, **base64-encoded** (UTF-8). Raw JSON is rejected.
- Second arg is an options JSON string (schemas/table/cohortId/generateStats).
- For pure validation (no SQL), use `circe_check_cohort(<base64(expressionJson)>)` which returns a JSON array of `{severity,message}` warnings/errors.

## 1. In-process SQL execution pattern for a Deno function

From `/Users/ph/code/trex/plugins/devx/functions/duckdb.ts` (read in full):

- `globalThis.Trex.databaseManager()` returns a database manager (`duckdb.ts:16`).
- `dbm.getConnection("memory", "main", "main", "main", {})` returns a wrapper; `.connection` is a `TrexDB` (`duckdb.ts:27`). (`getConnection` logs a harmless `Error getting dialect for memory`; devx suppresses it.)
- `await conn.execute(sql, params)` runs the SQL and returns rows; devx reads `rows?.[0]?.column0` (`duckdb.ts:59,93`). Under the hood this is `op_execute_query_pinned` (`duckdb.ts:6`).
- **Caveat (load-bearing):** `getConnection()` leases a fresh pool session every call and does **not** reuse it. The caller MUST `conn.close()` (in a `finally`) or the shared DuckDB session pool (default 64) drains and the node wedges. devx wraps every call in try/finally close() and offers `openMemoryConnection()` for long-lived reuse (`duckdb.ts:50-102`). The `validate_circe` tool does one query per call, so the `duckdb()` one-shot pattern (lease → execute → close in finally) is the right shape.
- There is also a simpler `globalThis.Trex.sql(query, params)` helper used elsewhere (`plugins/devx/functions/index.ts:2153-2154`); it does not give explicit session control, so prefer the `databaseManager()` + close pattern for safety.

## 2. circe is a DuckDB SQL function (not HTTP)

The JVM `validate_circe` tool (`plugins/bao/java/src/trexsql/agent/tools/validate_circe.clj`)
prefers WebAPI `POST /WebAPI/cohortdefinition/sql` (`:64`) and only *falls back* to the
in-process renderer (`render-locally`, `:82-96`). But that in-process renderer
(`trexsql.circe/render-circe-to-sql` → `render-circe-sql`, `circe.clj:154-173`) is itself
**just a SQL query over the DB connection** — it calls the two circe SQL functions shown above.
So we do not need WebAPI at all; we call the SQL functions directly.

Discovered in the **running** engine (container `atlas3-trex-trex-1`) over pgwire:

```
$ docker run --rm --network atlas3-trex_default -e PGPASSWORD=mypass postgres:16-alpine \
    psql -h trex -p 5432 -U trex -d main -t -A -c \
    "SELECT function_name, parameter_types, return_type FROM duckdb_functions() WHERE function_name ILIKE '%circe%';"

 circe_check_cohort         | {VARCHAR}                 | VARCHAR
 circe_json_to_sql          | {VARCHAR,VARCHAR}         | VARCHAR
 circe_sql_render_translate | {VARCHAR,VARCHAR,VARCHAR} | VARCHAR
```

Signatures:
- `circe_json_to_sql(base64_expression_json VARCHAR, options_json VARCHAR) -> VARCHAR` (templated/ohdsi-sql).
- `circe_sql_render_translate(sql VARCHAR, dialect VARCHAR, params_json VARCHAR) -> VARCHAR` (translate to a target dialect, e.g. `'duckdb'`).
- `circe_check_cohort(base64_expression_json VARCHAR) -> VARCHAR` (JSON array of `{severity,message}`).

These functions are **process-global** (loaded by the `circe.trex` extension at boot, "Loading extension: circe.trex ... ok"). Proven: a fresh pgwire connection with no `LOAD` sees all 3 functions and they work — so a Deno memory connection does not need to `LOAD circe`:

```
$ ... -c "SELECT count(*) FROM duckdb_functions() WHERE function_name ILIKE 'circe%';"
3
```

(The JVM's `load-circe-extension!` in `util.clj:43-58` is defensive; in the running native stack the extension is already loaded.)

## 3 & 4. Worked example — proven against the running node

Minimal CIRCE CohortExpression (`/tmp/circe_min.json`):

```json
{"PrimaryCriteria":{"CriteriaList":[{"ConditionOccurrence":{"CodesetId":0}}],
"ObservationWindow":{"PriorDays":0,"PostDays":0},"PrimaryCriteriaLimit":{"Type":"First"}},
"ConceptSets":[],"QualifiedLimit":{"Type":"First"},"ExpressionLimit":{"Type":"First"},
"InclusionRules":[],"CensoringCriteria":[],"CollapseSettings":{"CollapseType":"ERA","EraPad":0},
"CensorWindow":{}}
```

Rendered to SQL over pgwire (same SQL the Deno function will run):

```
$ B64=$(base64 < /tmp/circe_min.json | tr -d '\n')
$ OPTS='{"cdmSchema":"main.cdm","resultSchema":"main.results","targetTable":"cohort","cohortId":1,"generateStats":false}'
$ psql ... -c "SELECT circe_sql_render_translate(circe_json_to_sql('$B64','$OPTS'),'duckdb','{}');"

CREATE TEMP TABLE Codesets  (codeset_id int NOT NULL, concept_id bigint NOT NULL);
ANALYZE Codesets;
CREATE TEMP TABLE qualified_events AS
SELECT event_id, person_id, start_date, end_date, op_start_date, op_end_date, visit_occurrence_id
FROM ( ... -- Begin Primary Events ... Condition Occurrence Criteria ... ) ...
```

Full rendered SQL length: **4922 chars** (proven via `SELECT length(...)`).

Validation-only call:

```
$ psql ... -c "SELECT circe_check_cohort('$B64');"
[{"severity":"INFO","message":"It's not specified what type of records to look for in condition occurrence at initial event"}]
```

Error handling (load-bearing): circe returns errors **inline** as a string starting with
`/* circe error: ... */` (the JVM detects this in `check-circe-error`, `circe.clj:131-140`).
Example with bad input:

```
$ psql ... -c "SELECT circe_json_to_sql('bm90LWpzb24=','$OPTS');"
/* circe error: java.lang.RuntimeException: com.fasterxml.jackson.core.JsonParseException: ... */
```

Also: a non-base64 first arg fails with `Invalid Input Error: circe_json_to_sql: base64 decode failed` — so always base64-encode.

## Recommended implementation for the new `validate_circe` (ClojureScript Deno function)

1. Accept `{expression, options?}`. `expression` may be a JSON string or object; serialize to a JSON string.
2. (Optional, cheap) pre-check shape like the JVM tool (`PrimaryCriteria.CriteriaList` present, `validate_circe.clj:40-61`).
3. base64-encode the expression JSON (UTF-8).
4. Build the options JSON. Defaults mirroring the JVM/WebAPI tool: `targetTable` `"cohort"`, `cohortId` a positive int (e.g. 1), `generateStats` false; `cdmSchema`/`resultSchema` are required by the validator (`circe.clj:55-78`) — use placeholder-qualified schemas (e.g. `@cdm_database_schema` is what WebAPI uses, but the SQL functions want concrete-looking values; `main.cdm`/`main.results` work for a validate-only pass).
5. Get a memory connection via `globalThis.Trex.databaseManager().getConnection("memory","main","main","main",{}).connection`.
6. Run, **in a try/finally that always closes the connection**:
   ```
   SELECT circe_sql_render_translate(circe_json_to_sql(<b64>, <optsJson>), 'duckdb', '{}') AS sql
   ```
   Read `rows[0].column0` (devx convention) or `rows[0].sql` — confirm the column accessor against `conn.execute`'s shape; devx uses `column0`.
7. If the returned string starts with `/* circe error`, treat it as a validation failure and surface the message. Otherwise return `{ok:true, sql}`. Optionally also call `circe_check_cohort(<b64>)` and surface non-empty warnings.

This drops the WebAPI HTTP round-trip the JVM tool used as its primary path; in the node we use the in-process functions directly, which is the whole reason for node hosting.

## Caveats

- **Session pool**: every `getConnection()` leases a pool session that MUST be returned via `conn.close()` (finally). Failing to do so drains the 64-slot pool and wedges the node (`duckdb.ts:84-101`).
- **Base64 required** for the expression arg; raw JSON errors out.
- **Errors are inline**, not exceptions: detect the `/* circe error` prefix.
- **Schemas**: `circe.clj`'s `validate-circe-options` requires non-blank `cdmSchema`/`resultSchema` and a positive integer `cohortId`. The SQL functions themselves accept whatever you pass; for validate-only, exact schema correctness does not matter since we never execute the rendered SQL.
- **Plugin HTTP routes are auth-gated** (pluginAuthz → 401), which is another reason the in-process DB path is preferred over any HTTP approach for an internal tool. This spike proved the SQL functions via pgwire from inside the container network rather than over an auth-gated HTTP route; the memory DuckDB connection a Deno function uses is the same engine instance, and the circe functions are process-global, so the result transfers directly.
- **column accessor**: devx reads `rows?.[0]?.column0`. The render SQL aliases the result `AS sql`; verify whether `conn.execute` keys by alias (`sql`) or positional (`column0`) at implementation time.
