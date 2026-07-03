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

The published package is `@trex/pythia`, declaring one agent named `pythia`
in `plugin/package.json`'s `trex.agents`. trex derives the mount point from
the plugin's npm scope and the agent name (`/<scope>/<name>/...`), so this
agent is served at **`/plugins/trex/pythia/*`** — not `/plugins/pythia/pythia`
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
  shadow-cljs.edn        :tools esm build (exports `tools` + `instructions`)
                          + :test node-test build
  scripts/
    import-map.deno.json     maps eve/tools -> test/eve_tools_stub.mjs (generators + smoke)
    gen-wrappers.mjs         out/tools.js -> plugin/agent/tools/<name>.js (deno)
    gen-instructions.mjs     out/tools.js -> plugin/agent/instructions.md (deno)
    smoke-tools.mjs          imports every generated wrapper, asserts trex loader shape (deno)
  package.json           build/sync/dist/smoke scripts
  out/                   build output (gitignored): tools.js

  plugin/                the MOUNTABLE plugin directory (self-contained, published as @trex/pythia)
    package.json         @trex/pythia, trex.agents -> [{name: "pythia", dir: "agent", env: {...}}]
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

The shadow `:tools` build target (`:target :esm`) emits **bare**
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
npm run dist       # release :tools build + regenerate plugin/agent/{instructions.md,tools/,resources/}
npm run smoke      # deno: import every generated tools/*.js wrapper, assert loader-required shape
```

`npm run dist` is what you run before mounting / committing the plugin: it
runs `npm run build` (shadow-cljs release of the `:tools` target) then
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
`/plugins/trex/pythia/*` on port 8001.

## Environment variables

| Var | Where it's set | Purpose |
|---|---|---|
| `TREX_AGENTS_DEFAULT_MODEL` | `docker-compose.yml`, set to `bedrock/${BAO_AGENT_MODEL:-minimax.minimax-m2.5}` | Fallback model string trex's agent runtime resolves when an agent declares no `:model` (this plugin's `agent.edn` declares none — see `agent.edn`). `BAO_AGENT_MODEL` is the operator-facing override var; the runtime itself only reads `TREX_AGENTS_DEFAULT_MODEL`. |
| `AWS_REGION` | `docker-compose.yml` (`us-east-1`) | Bedrock region for the `bedrock/...` model prefix. |
| `AWS_BEARER_TOKEN_BEDROCK` | `./.env` (via `env_file`, see `.env.example`) | Bedrock bearer-token auth (bypasses SigV4); required for any `bedrock/...` model to actually make a call. |
| `BAO_AGENT_WEBAPI_URL` | injected per-agent via `plugin/package.json`'s `trex.agents[0].env` (`${BAO_AGENT_WEBAPI_URL:-http://localhost:8080/WebAPI}`), not a container-wide env | Base URL Pythia's tools (e.g. `search_concepts`, `validate_circe`) use to call OHDSI WebAPI. Forwarded into the agent worker by trex's per-agent manifest-env mechanism, not read as a raw process env by the plugin. |

## Calling it

Once mounted, trex exposes the eve-compatible session API and a `/chat`
convenience endpoint under `/plugins/trex/pythia/...` — see the trex agents
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
`eve/evals`, matching the proven structure of the trex repo's
`plugins-dev/toy-agent/evals/` fixture): `evals.config.ts` plus one
`*.eval.ts` per scenario. Run them with the real `eve` CLI against a live,
mounted stack:

```sh
npx eve@latest eval --url http://localhost:8001/plugins/trex/pythia
```

This requires the docker-compose stack up (`docker compose up`) and a real
model credential (`AWS_BEARER_TOKEN_BEDROCK` set, or another provider's
model/env pair — see "Environment variables" above): `eve eval --url` polls
`/eve/v1/health` and verifies `/eve/v1/info` on the target before running
anything, then drives real sessions through the live agent. The five evals
authored here:

| File | Asserts |
|---|---|
| `diabetes-concept-search.eval.ts` | `search_concepts` is called for a diabetes cohort request; reply mentions standard concepts. |
| `circe-validation.eval.ts` | `validate_circe` is called before a draft cohort is proposed. |
| `inclusion-rule-proposal.eval.ts` | `add_inclusion_rule` (a `clientOnly` tool — no server-side `:execute`) shows up in the stream as a `"pending"` tool call when asked to add an inclusion rule. |
| `book-rag.eval.ts` | `search_ohdsi_book` is called for a methodology question (washout period). |
| `medical-advice-refusal.eval.ts` | Judge-checked: the agent declines personal medical advice and redirects to cohort-design scope. |

These evals were authored and syntax/type-checked in this session (`deno
check` against the real `eve@0.19.0` npm package's types via an import map —
no stub was needed since the real package resolves cleanly under `npm:`
specifiers) but **not run live**: no model credential or running stack was
available in that environment. Treat a live `eve eval --url` pass as
deployment verification, not something this repo's CI can assume.
