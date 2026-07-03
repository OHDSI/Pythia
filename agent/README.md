# Pythia agent (ClojureScript)

The Pythia cohort-design agent, compiled from ClojureScript to a trex
agents-plugin (eve-layout) directory: `instructions.md` + `tools/*.js` +
`agent.edn`, loaded by trex's shared agent runtime (model loop, tool
dispatch, durability, eve-compatible HTTP surface — see
`core/server/agents/README.md` in the [trex](https://github.com/OHDSI/trex)
repo, which owns the runtime this plugin is authored against). Mounted at
`/plugins/pythia/pythia/*`.

## Layout

```
agent/
  src/pythia/
    config.cljs      WebAPI base URL (model/credentials are the trex resolver's job)
    prompt.cljc       base-prompt: persona + OHDSI workflow + "## Request context
                       format" (documents the <context> JSON the trex runtime
                       appends per turn — route/artifact/plan)
    agent_tools.cljs   adapter: pythia.tools/all -> trex eve `defineTool` results
    tools/*.cljs       tool definitions ({:name :description :schema :run?})
  test/pythia/
    prompt_test.cljs
    resources_test.cljs
    tools/parity_test.cljs   NAME-parity audit against the JVM tool-specs baseline
    ...
  shadow-cljs.edn      :tools esm build (exports `tools` + `instructions`)
                        + :test node-test build
  scripts/
    import-map.deno.json   maps eve/tools -> test/eve_tools_stub.mjs (generators + smoke)
    gen-wrappers.mjs       out/tools.js -> plugin/agent/tools/<name>.js (deno)
    gen-instructions.mjs   out/tools.js -> plugin/agent/instructions.md (deno)
    smoke-tools.mjs        imports every generated wrapper, asserts trex loader shape (deno)
  package.json         build/sync/dist/smoke scripts
  out/                 build output (gitignored): tools.js

  plugin/              the MOUNTABLE plugin directory (self-contained)
    package.json       @trex/pythia, trex.agents -> [{name: "pythia", dir: "agent"}]
    agent/              the eve-layout agent directory (TREX_AGENT_DIR at runtime)
      instructions.md   GENERATED, committed (by gen-instructions.mjs)
      agent.edn         {:max-steps 20}, no :model (falls back to
                         TREX_AGENTS_DEFAULT_MODEL, or the resolver's per-provider pick)
      tools/
        <name>.js       GENERATED, committed (by gen-wrappers.mjs) — one default
                        export per tool, re-exporting from _build/tools.js
        _build/tools.js GENERATED, committed COPY of out/tools.js (directories
                        under tools/ are invisible to the trex tool loader)
      resources/        GENERATED, committed COPY of agent/resources/ (EDN corpora)
```

Generated artifacts under `plugin/agent/` (`instructions.md`, `tools/*.js`,
`tools/_build/tools.js`, `resources/`) are **committed**, matching the
convention this plugin already used for `plugin/functions/handler.js` before
task P3 restructured it onto the trex agents-plugin layout. Run `npm run
dist` and commit the result whenever `src/pythia/**` or `resources/**`
change.

The shadow `:tools` build target (`:target :esm`) emits **bare**
`import * from "eve/tools"` (via `:js-provider :import` + `:keep-as-import`).
That bare specifier is resolved two different ways depending on context:

- **At generation time**, `npm run sync` runs `scripts/gen-wrappers.mjs` /
  `scripts/gen-instructions.mjs` under `deno run --import-map=scripts/import-map.deno.json`,
  which maps `eve/tools` to `test/eve_tools_stub.mjs` (a minimal brand +
  validation stub — the same one shadow-cljs's `:test` build already uses).
- **At runtime**, the trex agents plugin loader generates its own import map
  per agent worker mapping `eve` / `eve/tools` / `eve/evals` to trex's real
  eve-shim (see `core/server/plugin/agents.ts` `buildAgentWorkerConfig` in the
  trex repo) — each generated `tools/<name>.js` wrapper re-imports
  `tools/_build/tools.js`, whose `eve/tools` import resolves through that map.

## Build

```sh
cd agent
npm install
npm run test     # shadow-cljs compile test && node out/test.cjs
npm run dist      # release :tools build + regenerate plugin/agent/{instructions.md,tools/,resources/}
npm run smoke     # deno: import every generated tools/*.js wrapper, assert loader-required shape
```

`npm run dist` is what you run before mounting / committing the plugin: it
rebuilds the compiled bundle and refreshes every generated file under
`plugin/agent/`. Full verify loop: `npm run dist && npm test && npm run smoke`.

## Mounting

`plugin/` is the self-contained, publishable directory: `plugin/package.json`
declares `"trex": {"agents": [{"name": "pythia", "dir": "agent", "env": {...}}]}`.
trex's plugin loader only mounts agents-type plugins scoped to `@trex/...`
(auth requirement — see the trex agents README's HTTP surface section), reads
`plugin/agent/instructions.md` + `agent.edn` + `tools/*.js` at boot, and
starts one Deno worker per agent with `TREX_AGENT_DIR` set to
`<plugin-root>/agent` (i.e. `plugin/agent/` here) — which is why
`pythia.resources/candidate-paths` prepends `$TREX_AGENT_DIR/resources/<rel>`.

## Calling it

Once mounted, trex exposes the eve-compatible session API and a `/chat`
convenience endpoint under `/plugins/pythia/pythia/...` — see the trex agents
README's "HTTP surface" section for the full session/stream/chat protocol.
