# Pythia Agent → ClojureScript Function Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move the Pythia/bao cohort-design agent (`trexsql.agent.*`) out of the trexsql GraalVM native lib into a ClojureScript Deno function, authored in trex-dx and mounted into the trex node, serving the unchanged `/WebAPI/trexsql/agent/*` path.

**Architecture:** A shadow-cljs (`:target :esm`) project under `trex-dx/agent/` compiles CLJS to ESM that the trex node's Deno function runtime imports. The function drives Bedrock + the tool loop with the JS Vercel AI SDK (`ai` + `@ai-sdk/amazon-bedrock`) called via CLJS interop, fetches WebAPI for search tools (bearer forwarded), and uses `globalThis.Trex` in-process for the one circe tool. Caddy reroutes `/WebAPI/trexsql/agent/*` to the function; the rest of bao stays in the native lib.

**Tech Stack:** ClojureScript, shadow-cljs, Deno, Vercel AI SDK (`ai`, `@ai-sdk/amazon-bedrock`), Caddy, Docker Compose, cljs.test.

---

## Spec

Source of truth: `docs/superpowers/specs/2026-06-11-pythia-agent-clojurescript-extraction-design.md`. Read it before starting.

## File Structure

Created in **trex-dx**:

```
agent/
  shadow-cljs.edn            # build config, :target :esm
  deps.edn                   # clojure deps for shadow-cljs
  package.json               # shadow-cljs + npm deps (ai, @ai-sdk/amazon-bedrock)
  src/pythia/
    entry.cljs               # function HTTP entry: parse request -> stream response
    sdk.cljs                 # thin CLJS wrappers over the Vercel AI SDK (interop)
    config.cljs              # env: model id, region, bearer, webapi base
    prompt.cljc              # system-prompt assembly (port of prompt.clj)
    rag.cljc                 # BM25 scorer + tokenizer (port of rag.clj)
    webapi.cljs              # fetch helper to :8080/WebAPI (bearer forwarded)
    trex.cljs                # globalThis.Trex in-process db/circe access
    tools/registry.cljs      # tool schemas -> SDK tool() defs + name->handler map
    tools/search_concepts.cljs
    tools/<...18 more...>.cljs
  resources/
    ohdsi-book-corpus.edn    # copied from bao classpath resource (RAG corpus)
  test/pythia/
    rag_test.cljs
    prompt_test.cljs
    tools/search_concepts_test.cljs
docs/superpowers/spikes/     # spike findings (committed notes)
```

Modified in **trex-dx**:
- `docker-compose.atlas3-trex.yml` — bind-mount the build output + agent env into `trex`.
- `Caddyfile` — route `/WebAPI/trexsql/agent/*` to the function.

Modified in **../trex** (decommission, last phase):
- `plugins/bao/java/project.clj`, `plugins/bao/java/src/trexsql/agent/**`, `plugins/bao/java/src/trexsql/webapi.clj` (route registration), `plugins/webapi/graalvm-config/*`.

---

## Phase 0 — De-risking spikes (do these first; each ends in committed notes)

The three unknowns below gate the real shape of later tasks. Each spike is a throwaway vertical probe whose **output is a short notes file** capturing the exact API/contract. Later tasks reference these notes.

### Task 0.1: Spike — how a mounted function serves an HTTP route on the node

**Files:**
- Create: `docs/superpowers/spikes/2026-06-11-function-http-contract.md`

- [ ] **Step 1: Inspect the function framework's HTTP dispatch and mount loading**

Read in `../trex`: `plugins/devx/functions/index.ts` (top-level request handler + how routes are matched), `plugins/devx/functions/routes/trex_routes.ts` (an example route module), and search the node core for how `PLUGINS_DEV_PATH` / `PLUGINS_PATH` functions are discovered and bound to a URL prefix:

Run:
```bash
cd /Users/ph/code/trex
grep -rnE "PLUGINS_DEV_PATH|PLUGINS_PATH|plugins-dev|registerFunction|function.*route|eszip|globalThis.Trex" core/ plugins/devx | head -40
```

- [ ] **Step 2: Determine the served URL + port for a mounted function**

Identify: (a) which node port serves functions (trexas `:8001` vs WebAPI `:8080`), (b) the URL prefix a mounted plugin function gets, (c) whether the function sees the raw `Request`/`Response` (Web Fetch API) so the Vercel SDK's `Response` can be returned directly.

- [ ] **Step 3: Prove a trivial mounted function responds**

Mount a one-line Deno function returning `new Response("ok")` into the running `atlas3-trex-trex-1` via the dev plugin path, and curl it. Record the exact mount path and the externally-reachable URL.

Run (adapt mount target to Step 1 findings):
```bash
docker exec atlas3-trex-trex-1 sh -lc 'ls -la /usr/src/plugins-dev 2>/dev/null; ls -la /usr/src/plugins 2>/dev/null'
```

- [ ] **Step 4: Write findings**

Record in the spike notes: the function file layout the node expects, the URL prefix + port, the request/response object types, and the Caddy `reverse_proxy` target needed to map `/WebAPI/trexsql/agent/*` onto it. Commit.

```bash
git add docs/superpowers/spikes/2026-06-11-function-http-contract.md
git commit -m "spike: function HTTP contract + mount path for the agent"
```

### Task 0.2: Spike — `globalThis.Trex` circe / db API

**Files:**
- Create: `docs/superpowers/spikes/2026-06-11-trex-global-api.md`

- [ ] **Step 1: Map the in-process API surface**

Read `../trex/plugins/devx/functions/duckdb.ts` fully (it already uses `globalThis.Trex.databaseManager()`), and find what circe entrypoints exist in-process:

Run:
```bash
cd /Users/ph/code/trex
grep -rnE "globalThis.Trex|databaseManager|render-circe|render_circe|circe" plugins/devx/functions core | head -40
```

- [ ] **Step 2: Decide the circe path for `validate_circe`**

The JVM tool calls `trexsql.circe/render-circe-to-sql` + `trexsql.extensions/get-default-db` in-process. Determine the JS equivalent: either (a) a `globalThis.Trex` DB connection running a circe SQL function, or (b) `POST :8080/WebAPI/...` if circe is also exposed over HTTP. Record the exact call.

- [ ] **Step 3: Prove it from a mounted function**

Using the mount mechanism from Task 0.1, run the chosen circe/db call against `globalThis.Trex` and capture a real result for a known cohort expression.

- [ ] **Step 4: Write findings + commit** (`git commit -m "spike: globalThis.Trex circe/db api"`).

### Task 0.3: Spike — CLJS ⇄ Deno ESM ⇄ Vercel AI SDK interop

**Files:**
- Create: `docs/superpowers/spikes/2026-06-11-cljs-sdk-interop.md`
- Create (throwaway): `agent/` minimal shadow-cljs project

- [ ] **Step 1: Stand up a minimal shadow-cljs `:target :esm` build**

Create `agent/package.json`:
```json
{
  "name": "pythia-agent",
  "type": "module",
  "devDependencies": { "shadow-cljs": "^2.28.0" },
  "dependencies": { "ai": "^6.0.146", "@ai-sdk/amazon-bedrock": "^3.0.0" }
}
```
Create `agent/shadow-cljs.edn`:
```clojure
{:deps {:aliases [:dev]}
 :builds
 {:fn {:target :esm
       :runtime :custom
       :output-dir "out"
       :modules {:pythia {:exports {handler pythia.entry/handler}}}}}}
```
Create `agent/deps.edn`: `{:paths ["src"] :deps {org.clojure/clojurescript {:mvn/version "1.11.132"}}}`.

- [ ] **Step 2: Write a CLJS module that calls `streamText` with one tool**

Create `agent/src/pythia/spike.cljs` that imports the SDK and, given a hardcoded message, calls `streamText` with `@ai-sdk/amazon-bedrock` and a single trivial `tool()`, returning `(.toUIMessageStreamResponse result)`. Use `(:require ["ai" :as ai] ["@ai-sdk/amazon-bedrock" :as bedrock])`.

- [ ] **Step 3: Verify Deno imports the ESM output and the stream shape matches**

Build (`npx shadow-cljs release fn`), import `out/pythia.js` from a tiny Deno script under the trex node (or `deno run` with the same npm import map), POST a message, and confirm the SSE frames include `text-delta` and `finish` — the exact shapes the Pythia frontend already consumes (verified live: `{"type":"text-delta",...}` / `{"type":"finish","finishReason":"stop"}`).

- [ ] **Step 4: Confirm `npm:` resolution under Deno**

Confirm whether the SDK is pulled via the devx `deno.json` import map (`npm:ai`, `npm:@ai-sdk/amazon-bedrock`) or bundled by shadow-cljs. Record which, and the exact import strings CLJS must use so Deno resolves them.

- [ ] **Step 5: Write findings + commit** (`git commit -m "spike: cljs <-> vercel ai sdk interop under deno"`).

**Gate:** Do not proceed past Phase 0 until all three spikes have committed notes. If 0.3 shows `:target :esm` fights the runtime, switch to `:target :node-library` and record the change before Phase 1.

---

## Phase 1 — Project scaffold & build

### Task 1.1: Promote the spike project to the real agent project

**Files:**
- Modify: `agent/shadow-cljs.edn`, `agent/package.json`, `agent/deps.edn`
- Create: `agent/src/pythia/config.cljs`

- [ ] **Step 1: Finalize `shadow-cljs.edn` exports**

Set `:modules {:pythia {:exports {handler pythia.entry/handler}}}` and add a `:test` build:
```clojure
:test {:target :node-test :output-to "out/test.js" :ns-regexp "-test$"}
```

- [ ] **Step 2: Write `config.cljs`**

```clojure
(ns pythia.config)
(defn env [k default] (or (some-> js/Deno.env (.get k)) default))
(def model-id      (env "BAO_AGENT_MODEL" "us.anthropic.claude-sonnet-4-6"))
(def aws-region    (env "AWS_REGION" "us-east-1"))
(def webapi-base   (env "BAO_AGENT_WEBAPI_URL" "http://localhost:8080/WebAPI"))
(defn bearer-token [] (env "AWS_BEARER_TOKEN_BEDROCK" nil))
```

- [ ] **Step 3: Build succeeds**

Run: `cd agent && npx shadow-cljs release fn`
Expected: `out/pythia.js` written, no compile errors.

- [ ] **Step 4: Commit**

```bash
git add agent/ && git commit -m "feat(agent): shadow-cljs esm scaffold + config"
```

### Task 1.2: Compose mount + Caddy route (wiring only; function still a stub)

**Files:**
- Modify: `docker-compose.atlas3-trex.yml` (the `trex` service `volumes:` + `environment:`)
- Modify: `Caddyfile`
- Create: `agent/src/pythia/entry.cljs` (stub)

- [ ] **Step 1: Stub `entry.cljs`**

```clojure
(ns pythia.entry)
(defn handler [^js req]
  (js/Response. "pythia-agent: ok" #js {:status 200}))
```
Rebuild: `cd agent && npx shadow-cljs release fn`.

- [ ] **Step 2: Mount the build output into the node**

In `docker-compose.atlas3-trex.yml`, under `trex:` `volumes:`, add (mount target per Task 0.1 findings):
```yaml
      - ./agent/out:/usr/src/plugins-dev/pythia-agent:ro
```
Under `trex:` `environment:` add:
```yaml
      BAO_AGENT_WEBAPI_URL: http://localhost:8080/WebAPI
```
(`BAO_AGENT_MODEL`, `AWS_REGION`, `AWS_BEARER_TOKEN_BEDROCK` already flow from `.env`.)

- [ ] **Step 3: Route the agent prefix in `Caddyfile`**

Before the existing `handle /WebAPI/* {...}` block, add a more-specific handler (Caddy matches most-specific path first; verify ordering):
```
	handle /WebAPI/trexsql/agent/* {
		reverse_proxy {$WEBAPI_HOST:atlas3-webapi}:8001
	}
```
(Target host:port per Task 0.1; `8001` is the trexas/function port placeholder.)

- [ ] **Step 4: Bring up and probe the stub through Caddy**

Run:
```bash
TREX_IMAGE=ghcr.io/ohdsi/trexsql:bao-native-fix docker compose -f docker-compose.atlas3-trex.yml up -d trex atlas3-frontend
curl -s -k -m 8 https://localhost/WebAPI/trexsql/agent/ -o /dev/null -w "%{http_code}\n"
```
Expected: `200` and body `pythia-agent: ok` (confirms mount + route reach the CLJS function).

- [ ] **Step 5: Commit** (`git commit -m "feat(agent): mount function into node + caddy route for /WebAPI/trexsql/agent/*"`).

---

## Phase 2 — Minimal chat vertical slice (no tools)

### Task 2.1: SDK wrapper namespace

**Files:**
- Create: `agent/src/pythia/sdk.cljs`

- [ ] **Step 1: Wrap the SDK calls confirmed in spike 0.3**

```clojure
(ns pythia.sdk
  (:require ["ai" :as ai]
            ["@ai-sdk/amazon-bedrock" :refer (createAmazonBedrock)]
            [pythia.config :as config]))

(defn model []
  ((createAmazonBedrock #js {:region (config/aws-region)
                             :apiKey (config/bearer-token)})
   (config/model-id)))

(defn stream-chat
  "messages: JS array of UIMessages. tools: JS object name->tool. system: string.
   Returns a Web Response carrying the UIMessageStream."
  [{:keys [messages tools system]}]
  (let [result (ai/streamText
                 #js {:model (model)
                      :system system
                      :messages (ai/convertToModelMessages messages)
                      :tools tools
                      :stopWhen (ai/stepCountIs 20)})]
    (.toUIMessageStreamResponse result)))
```
(Exact provider-construction + apiKey/token field name come from spike 0.3; adjust to the verified call. `stepCountIs`/`stopWhen` is the SDK's multi-step tool loop, replacing bao's `max-steps`=20.)

- [ ] **Step 2: Build** — `cd agent && npx shadow-cljs release fn`. Expected: no errors.
- [ ] **Step 3: Commit** (`git commit -m "feat(agent): vercel ai sdk wrapper"`).

### Task 2.2: Prompt port

**Files:**
- Create: `agent/src/pythia/prompt.cljc`
- Test: `agent/test/pythia/prompt_test.cljs`
- Reference: `../trex/plugins/bao/java/src/trexsql/agent/prompt.clj`

- [ ] **Step 1: Failing test for prompt assembly**

```clojure
(ns pythia.prompt-test
  (:require [cljs.test :refer [deftest is]] [pythia.prompt :as prompt]))
(deftest system-prompt-includes-core-instructions
  (let [s (prompt/system-prompt {:route "/atlas/#/cohortdefinitions" :artifact nil})]
    (is (string? s))
    (is (re-find #"(?i)cohort" s))))
```

- [ ] **Step 2: Run, verify fail** — `cd agent && npx shadow-cljs compile test && node out/test.js` → FAIL (`pythia.prompt` not found).

- [ ] **Step 3: Port `prompt.clj` to `.cljc`**

Copy the prompt text + assembly from `trexsql.agent.prompt`, replacing any JVM-only forms. Keep the dynamic-context appending (route + open-artifact summary) that `routes.clj` passed as `dynamic-context`. Expose `(system-prompt {:route :artifact})`.

- [ ] **Step 4: Run, verify pass** — `node out/test.js` → PASS.
- [ ] **Step 5: Commit** (`git commit -m "feat(agent): port system prompt"`).

### Task 2.3: Entry handler — parse request, stream a tool-less reply

**Files:**
- Modify: `agent/src/pythia/entry.cljs`
- Reference: `../trex/plugins/bao/java/src/trexsql/agent/routes.clj:111` (`chat-handler`)

- [ ] **Step 1: Implement the handler**

```clojure
(ns pythia.entry
  (:require [pythia.sdk :as sdk] [pythia.prompt :as prompt]))

(defn handler [^js req]
  (-> (.json req)
      (.then (fn [^js body]
               (sdk/stream-chat
                 {:messages (.-messages body)
                  :tools #js {}
                  :system (prompt/system-prompt
                            {:route (some-> body .-context .-route)
                             :artifact (some-> body .-context .-artifact)})})))
      (.catch (fn [e]
                (js/Response. (js/JSON.stringify #js {:error (str e)})
                              #js {:status 500})))))
```
(Body field names — `messages`, `context.route` — match what the frontend `DefaultChatTransport` sends; verify against `trex-dx/src/chat-session.ts` and `route-manifest.ts`.)

- [ ] **Step 2: Build + redeploy** — `cd agent && npx shadow-cljs release fn` then `docker compose -f docker-compose.atlas3-trex.yml up -d trex` (or rely on the read-only mount + a function reload; confirm reload behavior in Task 0.1).

- [ ] **Step 3: End-to-end smoke (no tools)**

Run:
```bash
curl -s -m 60 -N -X POST https://localhost/WebAPI/trexsql/agent/chat -k \
  -H 'Content-Type: application/json' \
  -d '{"messages":[{"role":"user","parts":[{"type":"text","text":"reply with exactly: pong"}]}]}' \
  | grep -iE "text-delta|finish|error" | head
```
Expected: `text-delta` frames spelling "pong" + `{"type":"finish",...}`, no error. (Mirrors the native smoke that passed.)

- [ ] **Step 4: Commit** (`git commit -m "feat(agent): tool-less chat vertical slice end-to-end"`).

---

## Phase 3 — Tools

### Task 3.1: WebAPI fetch helper + Trex in-process helper

**Files:**
- Create: `agent/src/pythia/webapi.cljs`, `agent/src/pythia/trex.cljs`
- Reference: `../trex/.../tools/_search_util.clj`, `../trex/.../tools/search_concepts.clj:101` (`call-webapi`)

- [ ] **Step 1: `webapi.cljs`**

```clojure
(ns pythia.webapi
  (:require [pythia.config :as config]))

(defn request
  "method: \"GET\"|\"POST\". path: appended to webapi-base. opts: {:body map :auth str}.
   Returns a promise of parsed JSON, or nil on non-200."
  [method path {:keys [body auth]}]
  (let [headers (cond-> #js {"Accept" "application/json"}
                  body (doto (aset "Content-Type" "application/json"))
                  auth (doto (aset "Authorization" auth)))]
    (-> (js/fetch (str (config/webapi-base) path)
                  #js {:method method :headers headers
                       :body (when body (js/JSON.stringify (clj->js body)))})
        (.then (fn [^js r] (when (= 200 (.-status r)) (.json r))))
        (.then #(js->clj % :keywordize-keys true)))))
```

- [ ] **Step 2: `trex.cljs` (per spike 0.2)**

```clojure
(ns pythia.trex)
(defn db-conn [] (some-> js/globalThis .-Trex (.databaseManager) (.getConnection "memory" "main" "main" "main" #js {}) .-connection))
;; circe entrypoint per spike 0.2 findings:
(defn render-circe-to-sql [expr] #_"<exact call from spike 0.2>")
```
(Fill `render-circe-to-sql` with the verified call. If spike 0.2 chose the HTTP path instead, drop this and route `validate_circe` through `webapi.cljs`.)

- [ ] **Step 3: Build + commit** (`git commit -m "feat(agent): webapi fetch + trex in-process helpers"`).

### Task 3.2: Tool registry + the SDK `tool()` shape

**Files:**
- Create: `agent/src/pythia/tools/registry.cljs`
- Reference: `../trex/.../agent/tools.clj` (schemas), `routes.clj` (how `:source-key`/`:request` reach a tool's `run`)

- [ ] **Step 1: Registry that turns a CLJS tool map into an SDK `tool()`**

```clojure
(ns pythia.tools.registry
  (:require ["ai" :refer (tool jsonSchema)]))

;; A tool is {:name :description :schema(map) :run (fn [args ctx] -> promise)}.
;; ctx carries {:auth :source-key} extracted from the incoming request, mirroring
;; the JVM tool's `req` arg ({:source-key :request}).
(defn ->sdk-tool [{:keys [description schema run]} ctx]
  (tool #js {:description description
             :inputSchema (jsonSchema (clj->js schema))
             :execute (fn [args] (run (js->clj args :keywordize-keys true) ctx))}))

(defn tools-object
  "tools: seq of tool maps. ctx: request-derived context. -> JS object name->sdk tool."
  [tools ctx]
  (reduce (fn [o t] (doto o (aset (:name t) (->sdk-tool t ctx)))) #js {} tools))
```

- [ ] **Step 2: Build + commit** (`git commit -m "feat(agent): tool registry -> sdk tool() adapter"`).

### Task 3.3: First worked tool — `search_concepts` (the template)

**Files:**
- Create: `agent/src/pythia/tools/search_concepts.cljs`
- Test: `agent/test/pythia/tools/search_concepts_test.cljs`
- Reference: `../trex/.../tools/search_concepts.clj` (port `call-webapi` + result shaping verbatim in CLJS)

- [ ] **Step 1: Failing test (mock `fetch`)**

```clojure
(ns pythia.tools.search-concepts-test
  (:require [cljs.test :refer [deftest is async]]
            [pythia.tools.search-concepts :as t]))
(deftest builds-vocabulary-search-call
  (async done
    (with-redefs [pythia.webapi/request
                  (fn [m p _] (is (= "POST" m)) (is (re-find #"/vocabulary/.+/search" p))
                    (js/Promise.resolve [{:CONCEPT_ID 201826 :STANDARD_CONCEPT "S" :DOMAIN_ID "Condition"}]))]
      (-> ((:run t/tool) {:query "type 2 diabetes" :domain "Condition"} {:source-key "EUNOMIA" :auth nil})
          (.then (fn [out] (is (= 201826 (-> out :results first :conceptId))) (done)))))))
```

- [ ] **Step 2: Run, verify fail** — `npx shadow-cljs compile test && node out/test.js` → FAIL.

- [ ] **Step 3: Port the tool**

```clojure
(ns pythia.tools.search-concepts
  (:require [pythia.webapi :as webapi]))

(def schema {:type "object"
             :properties {:query {:type "string"} :domain {:type "string"}}
             :required ["query"]})

(defn run [{:keys [query domain]} {:keys [source-key auth]}]
  (-> (webapi/request "POST" (str "/vocabulary/" source-key "/search")
                      {:auth auth :body {:QUERY query
                                         :DOMAIN_ID (when (seq domain) [domain])}})
      (.then (fn [rows]
               ;; port the STANDARD_CONCEPT='S' filter + ambiguity scoring from
               ;; search_concepts.clj verbatim.
               (clj->js {:results (->> rows (filter #(= "S" (:STANDARD_CONCEPT %)))
                                       (map (fn [r] {:conceptId (:CONCEPT_ID r)
                                                     :conceptName (:CONCEPT_NAME r)
                                                     :domain (:DOMAIN_ID r)})))})))))

(def tool {:name "search_concepts" :description "Search the OMOP vocabulary..."
           :schema schema :run run})
```

- [ ] **Step 4: Run, verify pass** — `node out/test.js` → PASS.
- [ ] **Step 5: Commit** (`git commit -m "feat(agent): port search_concepts tool (template)"`).

### Task 3.4: Port the remaining fetch-based tools (one task each, same template as 3.3)

Each tool below is a self-contained repeat of Task 3.3's pattern: a `schema`, a `run` that calls `pythia.webapi/request` and shapes the result exactly as its JVM source does, a `tool` map, plus a mocked-`fetch` test asserting the URL/verb and one mapped field. Port the JVM source's request URL, body, and result shaping verbatim — do not invent behavior.

For each: **Create** `agent/src/pythia/tools/<name>.cljs` + **Test** `agent/test/pythia/tools/<name>_test.cljs`; **Reference** `../trex/plugins/bao/java/src/trexsql/agent/tools/<name>.clj`. Steps per tool: (1) write failing mock-fetch test, (2) run→fail, (3) port schema+run+result-shaping from the JVM file, (4) run→pass, (5) commit `feat(agent): port <name> tool`.

- [ ] **Task 3.4a `search_phenotypes`** — GET phekb.org + forums.ohdsi.org (external URLs in source; no auth). Shape: ranked list.
- [ ] **Task 3.4b `search_concept_sets`** — GET `/conceptset/` collection, score by `_search-util/score-match`. Port `_search_util` helpers into `pythia.webapi` or a `pythia.tools.search_util` ns first (shared).
- [ ] **Task 3.4c `search_existing_cohorts`** — GET `/cohortdefinition/` + score-match.
- [ ] **Task 3.4d `search_characterizations`** — GET `/cohort-characterization/` + score-match.
- [ ] **Task 3.4e `search_feature_analyses`** — GET `/feature-analysis/` + score-match.
- [ ] **Task 3.4f `search_incidence_rates`** — GET `/ir/` + score-match.
- [ ] **Task 3.4g `search_pathways`** — GET `/pathway-analysis/` + score-match.
- [ ] **Task 3.4h `get_cohort_overlap`** — GET cohort overlap endpoint(s).
- [ ] **Task 3.4i `get_cohort_generation_summary`** — GET generation/info endpoint(s).
- [ ] **Task 3.4j `summarise_attrition`** — GET inclusion-rule results.
- [ ] **Task 3.4k `get_reference_phenotype`** — GET (note source passes `:throw-exceptions false`; just treat non-200 as nil).
- [ ] **Task 3.4l `verify_concept_mapping`** — GET `/vocabulary/.../concept/<id>` + domain/vocab checks.
- [ ] **Task 3.4m `search_ohdsi_studies`** — GET github search URL.
- [ ] **Task 3.4n `get_artifact`** — GET artifact by `{:kind :id}`.
- [ ] **Task 3.4o `web_search`** — POST `https://lite.duckduckgo.com/lite/` (form-encoded; set `Content-Type: application/x-www-form-urlencoded` and a URLSearchParams body, not JSON). Parse top-N results.

### Task 3.5: `search_ohdsi_book` + RAG (BM25 port)

**Files:**
- Create: `agent/src/pythia/rag.cljc`, `agent/src/pythia/tools/search_ohdsi_book.cljs`
- Create: `agent/resources/ohdsi-book-corpus.edn` (copy from bao classpath resource)
- Test: `agent/test/pythia/rag_test.cljs`
- Reference: `../trex/plugins/bao/java/src/trexsql/agent/rag.clj`

- [ ] **Step 1: Locate + copy the corpus resource**

Run:
```bash
cd /Users/ph/code/trex && find plugins/bao -name '*.edn' | xargs grep -l "" 2>/dev/null | grep -iE "book|corpus|ohdsi" 
```
Copy the corpus edn into `agent/resources/ohdsi-book-corpus.edn`.

- [ ] **Step 2: Failing BM25 test**

```clojure
(ns pythia.rag-test
  (:require [cljs.test :refer [deftest is]] [pythia.rag :as rag]))
(deftest bm25-ranks-exact-term-higher
  (let [docs [{:id 1 :text "diabetes mellitus type 2"} {:id 2 :text "hypertension"}]
        hits (rag/bm25-search docs "diabetes" 5)]
    (is (= 1 (:id (first hits))))))
```

- [ ] **Step 3: Run→fail**, then **Step 4: port** `tokenize`, the BM25 (`k1`=1.2, `b`=0.75) scorer, stopwords, and `bm25-search` from `rag.clj` into `rag.cljc` (pure; no `clojure.java.io`). Corpus loaded via `Deno.readTextFile` + `cljs.reader/read-string` in the tool, passed into `bm25-search`.

- [ ] **Step 5: Run→pass; port `search_ohdsi_book` tool to call `rag/bm25-search`; commit** (`feat(agent): port RAG BM25 + search_ohdsi_book`).

### Task 3.6: Client-side / stub tools + `validate_circe` (in-process)

**Files:**
- Create: `agent/src/pythia/tools/validate_circe.cljs` + remaining registry-only tools
- Reference: `../trex/.../tools/validate_circe.clj`, `tools.clj` (client-side tool stubs: `draft_concept_set_spec`, `add_*`, proposal-emitting tools)

- [ ] **Step 1: Port `validate_circe` using the spike-0.2 circe path**

`run` calls `pythia.trex/render-circe-to-sql` (in-process) or the HTTP fallback, returns `{:ok :sql :issues}` matching the JVM tool. Test with a mocked `pythia.trex/render-circe-to-sql`.

- [ ] **Step 2: Port the client-side/proposal tools**

Tools like `draft_concept_set_spec` and the cohort-building tools (`add_criteria`, inclusion rules, etc.) are server stubs that emit a proposal payload the frontend cards render. Port their `run` to return the same data the JVM stub returned (the frontend proposal cards in `trex-dx/src/*ProposalCard.vue` / `AskUserCard.vue` are the contract — do not change their expected shape).

- [ ] **Step 3: Per-tool: failing test → port → pass → commit.**

### Task 3.7: Wire all tools into the entry handler

**Files:**
- Modify: `agent/src/pythia/entry.cljs`, create `agent/src/pythia/tools.cljs` (aggregate)

- [ ] **Step 1: Aggregate all tool maps**

```clojure
(ns pythia.tools
  (:require [pythia.tools.search-concepts :as sc] [pythia.tools.search-phenotypes :as sp]
            ;; ...all tool nses... )
  )
(def all [sc/tool sp/tool #_...])
```

- [ ] **Step 2: Build the tools object per request and pass to `stream-chat`**

In `entry.cljs`, derive `ctx` `{:auth (header req "authorization") :source-key <default/source>}`, then `(registry/tools-object pythia.tools/all ctx)` into `sdk/stream-chat`.

- [ ] **Step 3: End-to-end tool turn**

Run the concept-search curl from Task 2.3 Step 3 (full sentence asking to search the vocabulary). Expected: `tool-input-available` + `tool-output-available` for `search_concepts` + `finish`. (Identical to the native smoke that now passes.)

- [ ] **Step 4: Commit** (`git commit -m "feat(agent): wire all tools into chat handler"`).

---

## Phase 4 — Parity & cutover

### Task 4.1: Frontend parity pass

**Files:**
- Reference: `trex-dx/src/chat-session.ts`, `trex-dx/src/*ProposalCard.vue`, `AskUserCard.vue`

- [ ] **Step 1: Exercise the Pythia panel against the rerouted path**

Bring up the full native stack (with the agent mount + Caddy route). Open `https://localhost/atlas/`, open the Pythia panel, run: a plain question; a concept search (tool); a concept-set draft (proposal card); an AskUser flow. Confirm each renders as before.

- [ ] **Step 2: Capture a baseline screenshot per flow** (mirrors existing `pythia-*.png`), commit notes if any event-shape gaps found, fix the offending tool's output shape, re-test.

- [ ] **Step 3: Commit** (`git commit -m "test(agent): frontend parity verified against cljs function"`).

### Task 4.2: Make the cutover the default

**Files:**
- Modify: `docker-compose.atlas3-trex.yml`, `Caddyfile`, `.env` (doc), `README.md`

- [ ] **Step 1: Document the agent build/mount in README** (how to `shadow-cljs watch`, where output mounts, the Caddy route).
- [ ] **Step 2: Ensure `up` brings the function mount by default** (the volume + route are now in the committed compose/Caddyfile).
- [ ] **Step 3: Commit** (`git commit -m "docs: document the cljs agent function + default wiring"`).

---

## Phase 5 — Decommission the agent in the native lib (../trex)

> Separate repo, separate PR. Do this only after Phase 4 parity holds.

### Task 5.1: Remove the agent from bao, keep everything else

**Files (../trex):**
- Delete: `plugins/bao/java/src/trexsql/agent/**`
- Modify: `plugins/bao/java/src/trexsql/webapi.clj` (drop `agent-routes` registration), `plugins/bao/java/project.clj` (drop `tools.all` if listed; keep clj-http `:aot`), `plugins/webapi/graalvm-config/*` (drop `trexsql/agent/*` resource entries; **keep** the clj-http resource trim)

- [ ] **Step 1: Remove the agent route registration**

In `trexsql.webapi`, remove the `[trexsql.agent.routes ...]` require + its route mount, so `/WebAPI/trexsql/agent/*` is no longer served by the servlet (Caddy now routes it to the function anyway).

- [ ] **Step 2: Delete `trexsql.agent.*`** including the `tools.all` reachability shim added during the native fix, and revert the `model-id` change (now unused).

- [ ] **Step 3: Keep clj-http config**

Do **not** revert `project.clj` clj-http `:aot` or the `resource-config.json` `.clj` trim — `trexsql.proxy` still uses `trexsql.http-client`. Confirm:
```bash
cd /Users/ph/code/trex && grep -rn "http-client" plugins/bao/java/src/trexsql/proxy.clj
```

- [ ] **Step 4: Rebuild the native lib + smoke**

Run `plugins/webapi/build-native-lib.sh` (via `Dockerfile.native-lib`). Boot the node, confirm `/WebAPI/info` 200 and a non-agent trexsql route (e.g. proxy or cache) still works, and that `/WebAPI/trexsql/agent/chat` is now served only by the mounted function.

- [ ] **Step 5: Commit** (`git commit -m "refactor: remove agent layer from bao (moved to trex-dx cljs function)"`).

---

## Self-Review (completed)

- **Spec coverage:** architecture → Phases 1–2; components (prompt/tools/rag/bedrock/config) → Phases 2–3; build/tooling → Phase 1 + spike 0.3; cutover → Phase 4; decommission incl. "keep clj-http for proxy" → Phase 5; the three named risks → Phase 0 spikes 0.1/0.2/0.3. No spec section is unaddressed.
- **Placeholder scan:** the only deferred specifics (`render-circe-to-sql` exact call, SDK provider construction, mount URL/port) are explicitly the **outputs of Phase 0 spikes**, not lazy gaps — each later task names the spike it draws from. The 15 fetch-tool ports (3.4a–o) are intentionally the same proven template (Task 3.3) with per-tool endpoint/shape deltas, since they are mechanical repeats of one verified pattern.
- **Type consistency:** `tool` map shape `{:name :description :schema :run}` is used identically in registry (3.2), template (3.3), every 3.4 tool, and aggregation (3.7); `pythia.webapi/request` signature `(method path {:body :auth})` is consistent across 3.1, 3.3, 3.4; `ctx` `{:auth :source-key}` consistent from 3.2 → 3.7.
