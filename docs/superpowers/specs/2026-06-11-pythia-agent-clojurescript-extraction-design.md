# Extract the Pythia agent to a ClojureScript function in trex-dx

Date: 2026-06-11
Status: Approved (design)

## Summary

Move the Pythia / bao cohort-design **agent endpoints** out of the trexsql GraalVM
native library and into **trex-dx** as a **ClojureScript** function that runs in the
trex node's Deno function runtime. The agent stops being part of the closed-world
native image, ending the native-image class of bugs (clj-http runtime compilation,
build-time env freezes, tool-namespace reachability) and enabling fast iteration.

This is an **extraction**, not a removal of bao. `bao`/`trexsql` (cache, db, circe,
jobs, study, the embedded WebAPI engine) stay exactly as they are. Only
`trexsql.agent.*` leaves.

## Motivation

The agent currently lives in `trexsql.agent.*` (JVM Clojure) compiled into
`libwebapi-native.so` and served at `/WebAPI/trexsql/agent/chat`. Getting it to run
in the native image required four separate GraalVM fixes (clj-http `:aot` + run-time
init + `.clj` resource trim; runtime `model-id`; a `tools.all` reachability shim).
Every agent change costs a ~25-minute native rebuild. Running the agent as plain JS
removes all of that: Bedrock over `fetch`, no native-image, edit-and-reload iteration.

## Decisions (locked)

1. **Subject:** the bao/Pythia cohort-design agent (`trexsql.agent.*`) — bedrock
   client, tool registry + ~19 tools, prompt, RAG, routes/SSE.
2. **Host:** a trex devx **Deno function**, authored and built in **trex-dx** and
   **mounted into the trex node** (`PLUGINS_DEV_PATH=/usr/src/plugins-dev`). In-process
   trexsql access via the runtime-injected `globalThis.Trex`.
3. **Bedrock core:** the JS **Vercel AI SDK** (`ai` + `@ai-sdk/amazon-bedrock`, already
   in the devx `deno.json`), called from ClojureScript via JS interop. The SDK owns
   Bedrock streaming, the tool-call loop, and UIMessageStream output.
4. **Cutover:** **extract and replace.** Keep the path `/WebAPI/trexsql/agent/*` and
   reroute it to the function (frontend unchanged). Remove `trexsql.agent.*` from the
   native lib; keep the rest of bao.

## Architecture

```
Pythia frontend (@ai-sdk/vue useChat, api:'/WebAPI/trexsql/agent/chat')
        │  POST UIMessageStream
        ▼
Caddy (trex-dx)
   /WebAPI/trexsql/agent/*  ─────────────►  trex node Deno function  (NEW, CLJS)
   /WebAPI/*                ─────────────►  trex node WebAPI servlet (:8080, unchanged)
                                                 │
   the CLJS function:                            │ in-process
     - Vercel AI SDK (ai + @ai-sdk/amazon-bedrock) for stream + tool loop
     - tools → fetch :8080/WebAPI (bearer forwarded)   ◄───────────────┘ (search tools)
     - validate_circe → globalThis.Trex (in-process circe / db)
     - rag → BM25 over a bundled corpus file
```

The function is mounted just like `frontend-assets/pythia-plugin` is mounted today —
a bind mount into the container plus a Caddy route. No change to the published trexsql
image is required to host it (the lib only loses the agent in a later trexsql release).

## Components (ported from `trexsql.agent.*`)

| Source (JVM Clojure) | Target | Notes |
|---|---|---|
| `prompt.clj` | `prompt.cljc` | System-prompt assembly; mostly string data, ports directly. |
| `tools.clj` (registry) | `tools.cljs` | Schemas become SDK `tool()` definitions. The JVM require-cycle that forced `requiring-resolve` is an AOT artifact and disappears in CLJS — static requires are fine. |
| `tools/*.clj` (~19) | `tools/*.cljs` | Handlers re-implemented over `fetch` (WebAPI) / `globalThis.Trex` (circe). |
| `rag.clj` | `rag.cljc` | Pure BM25 scorer + tokenizer; corpus via `Deno.readTextFile`. No embeddings ship today. |
| `bedrock.clj`, `routes.clj`, `sse.clj` | — | **Replaced** by the SDK: no AWS event-stream codec, no reitit/ring, no clj-http. |
| env (`BAO_AGENT_MODEL`, `AWS_BEARER_TOKEN_BEDROCK`, `AWS_REGION`) | SDK provider config | Read at runtime; the build-time-freeze bug cannot exist in JS. |

### Tool data access
- Search/list tools call `GET|POST {BAO_AGENT_WEBAPI_URL or :8080/WebAPI}/...` and
  forward the incoming request's `Authorization` bearer (today's `_search-util` /
  `call-webapi` pattern, re-expressed with `fetch`).
- `validate_circe` is the one tool that uses in-process trexsql (`render-circe-to-sql`
  + `get-default-db`). In the node function this is `globalThis.Trex.databaseManager()`
  — the reason node hosting was chosen over a sidecar.

## Build & tooling

- **shadow-cljs**, `:target :esm` (recommended) → ESM modules the Deno runtime imports;
  npm deps via Deno `npm:` specifiers / the existing devx import map. Alternative if
  `:esm` fights the runtime: `:target :node-library`.
- Source under `trex-dx/agent/` (`src/`, `shadow-cljs.edn`, `deps.edn`). Build output to
  a dir bind-mounted into the trex container; a build script + compose mount mirror the
  existing `frontend-assets/pythia-plugin` mounting.
- Dev loop: `shadow-cljs watch` → output reloads in the mounted function; no native
  rebuild, no image rebuild.

## Cutover & decommission

1. Add the function mount + Caddy route for `/WebAPI/trexsql/agent/*`. Verify parity:
   replay the concept-search tool turn (the smoke test that now passes on native) and
   exercise the Pythia panel end to end.
2. **Remove from the native lib** (later trexsql change): `trexsql.agent.*` (including the
   `tools.all` reachability shim and the runtime `model-id` change) and the agent route
   registration in the servlet.
3. **Keep in the native lib:** cache, db, circe, jobs, study, the WebAPI engine — and the
   **clj-http native config** (`:aot` + `resource-config` `.clj` trim). That fix is NOT
   agent-only: `trexsql.proxy` also uses `trexsql.http-client`, so clj-http must keep
   loading correctly in the image after the agent leaves.

## Testing

- **Unit (CLJS):** tools (schema + handler over a mocked `fetch`), prompt assembly, RAG
  BM25 ranking.
- **Integration:** run the function against a live trex node; replay representative turns
  (plain text turn; `search_concepts` tool turn; a `validate_circe` turn exercising
  `globalThis.Trex`).
- **E2E:** the Pythia frontend panel against the rerouted path (unchanged transport).

## Risks & first steps (spikes precede the full port)

1. **CLJS ⇄ Deno ESM ⇄ `ai` SDK interop.** First task is a minimal spike: from CLJS call
   `streamText` with one `tool()` against `@ai-sdk/amazon-bedrock`, served through the
   function framework, and confirm `@ai-sdk/vue` `useChat` renders the stream. This
   de-risks the whole approach before porting 19 tools.
2. **Mounted-function HTTP contract.** Confirm how a `PLUGINS_DEV_PATH` function registers
   an HTTP route the node serves, what port/path Caddy must target for
   `/WebAPI/trexsql/agent/*`, and exactly what `globalThis.Trex` exposes for circe.
3. **Wire-format parity.** The frontend expects the existing UIMessageStream event shapes
   (`text-delta`, `tool-input-available`, `tool-output-available`, `finish`). The SDK
   produces these, but tool-output shapes and the AskUser/proposal-card events must match
   what the proposal cards in `trex-dx/src/*.vue` consume.

## Out of scope

- The devx coding/dev agent (separate, already TS/Deno).
- Any change to bao functionality other than removing the agent layer.
- Publishing a new trexsql image (the native-lib agent removal lands in a later trexsql
  release; until then the mounted function simply shadows the in-lib agent via routing).
