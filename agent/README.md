# Pythia agent (ClojureScript)

The Pythia cohort-design agent: instructions, prompt, and 44 tools authored
in ClojureScript here and compiled to a trex **agents plugin** — an
eve-layout directory (`instructions.md` + `agent.edn` + `tools/*.js`) that
trex's shared agent runtime loads and runs. This plugin owns the prompt and
the tools; it does **not** own the model loop, sessions, streaming, or the
eve-compatible HTTP surface — those live in trex core
(`core/server/agents/` in the [trex](https://github.com/OHDSI/trex) repo,
which this plugin is authored against; see that directory's `README.md` for
the day-to-day agents-plugin contract and `COMPAT.md` for exactly where
trex's runtime matches real eve and where it diverges).

The published package is `@ohdsi/pythia-agent`, declaring one agent named `pythia`
in `plugin/package.json`'s `trex.agents`. trex derives the mount point from
the plugin's npm scope and the agent name (`/<scope>/<name>/...`), so this
agent is served at **`/plugins/ohdsi/pythia/*`** — not `/plugins/pythia/pythia`
as an earlier revision of this file said.

## Layout

```
agent/
  src/pythia/
    config.cljs        WebAPI base URL (model/credentials are trex's resolver's job)
    prompt.cljc         base-prompt: persona + OHDSI workflow + "## Request context
                         format" (documents the <context> JSON the trex runtime
                         appends per turn — route/artifact/plan)
    agent_tools.cljs     adapter: pythia.tools/all -> trex eve `defineTool` results
                         (server tools get :execute; tool maps with no :run become
                         :clientOnly true, proposal-style calls the frontend resolves)
    tools/*.cljs         tool definitions ({:name :description :schema :run?})
  test/pythia/
    prompt_test.cljs
    resources_test.cljs
    tools/parity_test.cljs   NAME-parity audit against the JVM tool-specs baseline
    ...
  shadow-cljs.edn        :fn esm build target, :tools module (exports `tools`
                          + `instructions`) + :test node-test build
  scripts/
    import-map.deno.json     maps eve/tools -> test/eve_tools_stub.mjs (generators + smoke)
    gen-wrappers.mjs         out/tools.js -> plugin/agent/tools/<name>.js (deno)
    gen-instructions.mjs     out/tools.js -> plugin/agent/instructions.md (deno)
    smoke-tools.mjs          imports every generated wrapper, asserts trex loader shape (deno)
  package.json           build/sync/dist/smoke scripts
  out/                   build output (gitignored): tools.js

  plugin/                the MOUNTABLE plugin directory (self-contained, published as @ohdsi/pythia-agent)
    package.json         @ohdsi/pythia-agent, trex.agents -> [{name: "pythia", dir: "agent", env: {...}}]
    agent/                the eve-layout agent directory (TREX_AGENT_DIR at runtime)
      instructions.md     GENERATED, committed (by gen-instructions.mjs)
      agent.edn           {:max-steps 20}, no :model (falls back to
                           TREX_AGENTS_DEFAULT_MODEL, or the resolver's per-provider pick)
      tools/
        <name>.js         GENERATED, committed (by gen-wrappers.mjs) — one default
                          export per tool, re-exporting from _build/tools.js
        _build/tools.js   GENERATED, committed COPY of out/tools.js (directories
                          under tools/ are invisible to the trex tool loader)
      resources/          GENERATED, committed COPY of agent/resources/ (EDN corpora:
                          book-of-ohdsi/passages.edn, phenotype-library/cohorts-index.edn)
    evals/                eve-format evals — see "Evals" below
      evals.config.ts
      *.eval.ts
```

Generated artifacts under `plugin/agent/` (`instructions.md`, `tools/*.js`,
`tools/_build/tools.js`, `resources/`) are **committed** — run `npm run dist`
and commit the result whenever `src/pythia/**` or `resources/**` change.

The shadow `:fn` build target (`:target :esm`, module `:tools`) emits **bare**
`import * from "eve/tools"` (via `:js-provider :import` + `:keep-as-import`).
That bare specifier is resolved two different ways depending on context:

- **At generation time**, `npm run sync` runs `scripts/gen-wrappers.mjs` /
  `scripts/gen-instructions.mjs` under `deno run --import-map=scripts/import-map.deno.json`,
  which maps `eve/tools` to `test/eve_tools_stub.mjs` (a minimal brand +
  validation stub — the same one shadow-cljs's `:test` build already uses).
- **At runtime**, trex's agents-plugin loader generates its own import map per
  agent worker mapping `eve` / `eve/tools` / `eve/evals` to trex's real
  eve-shim (see `core/server/plugin/agents.ts`'s `buildAgentWorkerConfig` in
  the trex repo) — each generated `tools/<name>.js` wrapper re-imports
  `tools/_build/tools.js`, whose `eve/tools` import resolves through that map.

## Where the loop lives

This directory authors the agent's *surface*: instructions, the tool
definitions, and the prompt. It does not author or run:

- the model loop / tool-call dispatch (`stepCountIs`-style multi-step
  agentic loop),
- sessions, turns, or durability,
- the eve-compatible HTTP surface (`/eve/v1/session*`, `/eve/v1/health`,
  `/eve/v1/info`, the `/chat` convenience route),
- streaming (NDJSON event stream: `turn.started`, `actions.requested`,
  `action.result`, `message.appended`, `message.completed`,
  `turn.completed`/`turn.failed`, `session.waiting`/`session.failed`).

All of that is trex's shared agent runtime (`core/server/agents/` in the
trex repo) — one Deno worker per registered agent, shared across every
agents-plugin trex mounts. This plugin is a "bring your own instructions and
tools" leaf; the loop, session store, and wire protocol are provided.

## Build

```sh
cd agent
npm install
npm run test      # shadow-cljs compile test && node out/test.cjs
npm run dist       # release :fn build (:tools module) + regenerate plugin/agent/{instructions.md,tools/,resources/}
npm run smoke      # deno: import every generated tools/*.js wrapper, assert loader-required shape
```

`npm run dist` is what you run before mounting / committing the plugin: it
runs `npm run build` (`shadow-cljs release fn`) then
`npm run sync` (deno-driven `gen-wrappers.mjs` + `gen-instructions.mjs`,
then re-copies `resources/` into `plugin/agent/resources/`). It rebuilds the
compiled bundle and refreshes every generated file under `plugin/agent/`.
Full verify loop: `npm run dist && npm test && npm run smoke`. The
regenerated files under `plugin/agent/` are committed as part of any change
to `src/pythia/**` or `resources/**`.

## Mounting

`plugin/` is the self-contained, publishable directory: `plugin/package.json`
declares `"trex": {"agents": [{"name": "pythia", "dir": "agent", "env": {...}}]}`.
trex's plugin loader only mounts agents-type plugins scoped to `@trex/...`
(auth requirement — see the trex agents README's HTTP surface section), reads
`plugin/agent/instructions.md` + `agent.edn` + `tools/*.js` at boot, and
starts one Deno worker per agent with `TREX_AGENT_DIR` set to
`<plugin-root>/agent` (i.e. `plugin/agent/` here) — which is why
`pythia.resources/candidate-paths` prepends `$TREX_AGENT_DIR/resources/<rel>`.

In this repo's dev stack (`docker-compose.yml`), `agent/plugin` is bind-mounted
read-only into the trex container's plugins-dev path and served at
`/plugins/ohdsi/pythia/*` on port 8001.

## Environment variables

| Var | Where it's set | Purpose |
|---|---|---|
| `TREX_AGENTS_DEFAULT_MODEL` | `docker-compose.yml`, set to `bedrock/${BAO_AGENT_MODEL:-minimax.minimax-m2.5}` | Fallback model string trex's agent runtime resolves when an agent declares no `:model` (this plugin's `agent.edn` declares none — see `agent.edn`). `BAO_AGENT_MODEL` is the operator-facing override var; the runtime itself only reads `TREX_AGENTS_DEFAULT_MODEL`. |
| `AWS_REGION` | `docker-compose.yml` (`us-east-1`) | Bedrock region for the `bedrock/...` model prefix. |
| `AWS_BEARER_TOKEN_BEDROCK` | `./.env` (via `env_file`, see `.env.example`) | Bedrock bearer-token auth (bypasses SigV4); required for any `bedrock/...` model to actually make a call. |
| `BAO_AGENT_WEBAPI_URL` | injected per-agent via `plugin/package.json`'s `trex.agents[0].env` (`${BAO_AGENT_WEBAPI_URL:-http://localhost:8080/WebAPI}`), not a container-wide env | Base URL Pythia's tools (e.g. `search_concepts`, `validate_circe`) use to call OHDSI WebAPI. Forwarded into the agent worker by trex's per-agent manifest-env mechanism, not read as a raw process env by the plugin. |

## Calling it

Once mounted, trex exposes the eve-compatible session API and a `/chat`
convenience endpoint under `/plugins/ohdsi/pythia/...` — see the trex agents
README's "HTTP surface" section for the full session/stream/chat protocol.

This repo's Atlas3 frontend (`src/chat-session.ts`) talks to the `/chat`
convenience route directly: `POST /WebAPI/trex/pythia/chat` with a body of
`{ messages, metadata: { sourceKey, context: { route, artifact } | null, plan } }`.
`sourceKey` picks the OMOP data source, `context` is the current Atlas3
route + open artifact (for `navigate_to` and undo), and `plan` is the active
plan card, if any — `agent_tools.cljs`'s `->tool-ctx` rebuilds these into the
legacy `{:auth :source-key :plan}` shape every `:run` tool function expects.

## Evals

`plugin/evals/` holds eve-format evals (`defineEval`/`defineEvalConfig` from
`eve/evals`): `evals.config.ts` plus one `*.eval.ts` per scenario. They run
with the real `eve` CLI against the live, mounted stack:

```sh
docker compose up -d        # with BAO_AGENT_MODEL + AWS_BEARER_TOKEN_BEDROCK in .env
cd agent && npm run evals   # wraps scripts/run-evals.sh: --strict --junit, proxy URL
npm run evals -- --tag workflow   # subset by tag; ids also work (npm run evals -- navigate)
```

One runner quirk: eve's eval CLI verifies that the target's `/eve/v1/info`
agent name matches the invoking directory's `package.json` name
(scope-stripped). Our package is `@ohdsi/pythia-agent` (-> `pythia-agent`)
but the agent's mounted name is `pythia`, so `run-evals.sh` invokes eve from
`plugin/eval-root/` — a tiny eval-project root whose `package.json` is named
`pythia`, with symlinks back to the shared `plugin/evals/` and `plugin/.eve/`.
Eval sources and artifacts stay where they always were. Those symlinks
require a POSIX checkout — on Windows, enable `core.symlinks` (and re-clone,
or `git config core.symlinks true && git checkout -- plugin/eval-root`) or
the eval-root fixture breaks (eve finds zero evals).

### Auth: go through the WebAPI proxy, not :8001 directly

`/plugins/ohdsi/pythia` on :8001 is gated by trex's `authContext` +
`pluginAuthz` middleware, which requires a `apikey: <service_role>` header
(see `core/server/middleware/auth-context.ts` and `plugin-authz.ts` in the
trex repo). `eve eval` has **no `--header`/`-H` flag** — that flag exists
only on `eve dev` (checked against the installed `eve@0.19.0` CLI's
`createCliProgram` in `node_modules/eve/dist/src/cli/run.js`). The only
eval-side auth knob is the `EVE_EVAL_AUTH_TOKEN` env var, and it is sent
*exclusively* as `Authorization: Bearer <token>`
(`eve/dist/src/evals/cli/eval-client.js`) — which doesn't help here, because
trex's `authContext` explicitly refuses to accept `service_role`/`anon`
keys over the Authorization channel (they must arrive via `apikey`) and
falls through to "no valid auth" instead. So neither a CLI flag nor
`EVE_EVAL_AUTH_TOKEN` can get a bare `--url http://localhost:8001/plugins/ohdsi/pythia`
run past pluginAuthz.

Point `eve eval` at the **WebAPI proxy** instead — trex's bao plugin
(`plugins/bao/java/src/trexsql/webapi.clj`'s `agent-proxy-handler`) forwards
`/WebAPI/trex/pythia/*` to `:8001/plugins/ohdsi/pythia/*` and injects the
`apikey: <service_role>` header itself, unconditionally, on every request
it proxies (see trex repo, `fix(bao): route /WebAPI/trex/pythia to the
agents plugin mount`). That means no `EVE_EVAL_AUTH_TOKEN` is even required
for the eval run to pass pluginAuthz — the proxy supplies it:

```sh
docker compose up   # brings up trex (:8001/:8080) and the atlas3-caddy frontend (:443)
npx eve@latest eval --url https://localhost/WebAPI/trex/pythia
```

This is the same base URL Caddy fronts for the browser (`Caddyfile`'s
`handle /WebAPI/*` block, matching `WEBAPI_URL`'s default of
`https://localhost/WebAPI`) — Caddy's dev TLS cert is self-signed (`tls
internal`); if eve's fetch rejects it, run with
`NODE_TLS_REJECT_UNAUTHORIZED=0` for that one command.

If you only need to sanity-check the mount is alive (not run structured
evals), curl it directly with the service_role key as a fallback — read
`auth.serviceRoleKey` from the trex metadata DB, or the
`BAO_AGENT_SERVICE_ROLE_KEY` env fallback (see `read-service-role-key` in
`webapi.clj`):

```sh
curl -sS http://localhost:8001/plugins/ohdsi/pythia/eve/v1/health \
  -H "apikey: $BAO_AGENT_SERVICE_ROLE_KEY"
```

Historical note: pinned trexsql images before `sha-0320fd70...` had a bao
agent-proxy bug that 500'd **bodyless GETs** (`/eve/v1/health`,
`/eve/v1/info`) through the canonical route, blocking eve's health probe
(fixed by OHDSI/trex#143). The interim sidecar workaround
(`eval-auth-proxy.mjs`) was removed when the fixed image was pinned; if the
canonical route's health probe ever 500s again while `:8001` answers `401`,
suspect a proxy regression of the same shape.

### Models: two separate paths

- **Agent under eval** — trex's Deno worker resolves
  `TREX_AGENTS_DEFAULT_MODEL=bedrock/$BAO_AGENT_MODEL` (compose). For eval
  runs set `BAO_AGENT_MODEL=us.anthropic.claude-sonnet-4-6` in `.env`.
- **Judge** — Claude Sonnet 4.6 on Bedrock, configured in
  `evals/evals.config.ts` as an AI SDK `LanguageModel` **instance**
  (`evals/judge-model.ts`, bearer-token auth from `AWS_BEARER_TOKEN_BEDROCK`).
  A judge model *string* would route through the Vercel AI Gateway
  (`AI_GATEWAY_API_KEY`) — that's why the instance form is used. The judge
  runs inside the Node `eve` CLI process; trex's `PASSTHROUGH_ENV` does not
  apply to it.

### The suite

| File | Tags | Asserts |
|---|---|---|
| `diabetes-concept-search.eval.ts` | workflow | `search_concepts` called with a diabetes query; no failed actions; reply mentions standard concepts. |
| `circe-validation.eval.ts` | workflow | `validate_circe` called before a draft cohort is proposed; no failed actions. |
| `inclusion-rule-proposal.eval.ts` | workflow, hitl | `add_inclusion_rule` appears exactly once as a **pending** clientOnly proposal. |
| `book-rag.eval.ts` | rag | `search_ohdsi_book` called; judge (>=0.7): concrete washout guidance attributed to the Book of OHDSI. |
| `medical-advice-refusal.eval.ts` | safety | No cohort-editing proposals; judge (>=0.7): declines personal medical advice, redirects to scope. |
| `existing-cohorts-first.eval.ts` | workflow | `search_existing_cohorts` called for a "define a cohort" request (workflow step 1). |
| `two-stage-concept-set.eval.ts` | workflow | `draft_concept_set_spec` (with clinical terms) ordered **before** `search_concepts`, for a standalone single-concept-set request. |
| `navigate.eval.ts` | workflow, hitl | `navigate_to {view: "cohorts"}` pending, exactly once. |
| `clarify-ambiguous-target.eval.ts` | workflow, hitl | `ask_user` pending when the target cohort is ambiguous; no premature `add_inclusion_rule`. |
| `grounded-concept-ids.eval.ts` | grounding | Every concept ID quoted in the reply appears in a `search_concepts` output (scenario: GI hemorrhage, which exists in Eunomia's vocabulary). |
| `cohort-build-flow.eval.ts` | flow, slow | Multi-turn design + go-ahead ends in a pending `set_entry_event` proposal — the first proposal of a from-scratch build. |

Assertions are deliberately **data-independent** (Eunomia's vocabulary is a
subset): they match tool inputs, call status, and reply text — never specific
concept IDs or result counts coming back from WebAPI. Scenario *wording*,
however, is calibrated to the live stack (verified on Sonnet 4.6, 11/11 across
two consecutive runs):

- **Scenario terms must exist in Eunomia's vocabulary subset** when an eval
  needs real search results — Eunomia has no hypertension or diabetes
  concepts at all, but "Gastrointestinal hemorrhage" is a Standard Condition
  concept there, which is why the grounding and build-flow scenarios use it.
- **Plan-mediated flows dead-end under `eve eval`.** For multi-phase requests
  the prompt (correctly) makes the agent's first call
  `select_plan_template`, and plan-step tools (`update_plan_step`) are
  clientOnly: the turn ends with the call pending, and only the Atlas3
  frontend resolves such calls — `eve eval` has no client-side resolver. The
  workflow scenarios are therefore worded as the prompt's documented no-plan
  case (single artifact, "no plan needed"), which keeps the agent on the
  direct proposal path. The same protocol shape is why `cohort-build-flow`
  asserts a pending `set_entry_event` rather than `add_criteria`: a turn ends
  at its first pending clientOnly proposal, and criteria proposals only
  follow once a user accepts the entry event.

### CI

`.github/workflows/agent-evals.yml` runs the suite nightly and on manual
dispatch: boots the compose stack, runs `agent/scripts/run-evals.sh`
(strict + JUnit), and uploads `.eve/` artifacts. It requires the
`AWS_BEARER_TOKEN_BEDROCK` Actions secret and skips with a notice when the
secret is absent.
