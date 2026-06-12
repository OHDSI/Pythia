# Pythia agent (ClojureScript)

The Pythia cohort-design agent, compiled from ClojureScript to an ESM module and
mounted into the trex node's Deno function runtime. Served at
`/plugins/trexsql/agent/*` on port **8001**, backed by the Vercel AI SDK
streaming from Amazon Bedrock (bearer-token auth).

## Layout

```
agent/
  src/pythia/
    config.cljs    env config (model id, region, bearer token, WebAPI url)
    prompt.cljc    system-prompt assembly + dynamic ## Current context block
    sdk.cljs       Vercel AI SDK wrapper (bearer Bedrock + stream-chat)
    entry.cljs     ESM entry; exports `handler` (Web fetch -> streaming Response)
  test/pythia/
    prompt_test.cljs
  shadow-cljs.edn  :fn esm build (handler export) + :test node-test build
  package.json     pins ai@^6, @ai-sdk/amazon-bedrock@^4.0.115; build/sync scripts
  deno.json        local bare-name -> npm: import map (for local driver runs)
  out/             build output (gitignored): handler.js, test.cjs

  plugin/          the MOUNTABLE plugin directory (self-contained)
    package.json   @trexsql/agent, trex.functions.api -> /agent, /functions
    functions/
      index.ts     Deno.serve delegating POST -> handler; GET /health
      handler.js   COPY of out/handler.js (committed; the mounted artifact)
      deno.json    bare "ai"/"@ai-sdk/amazon-bedrock" -> npm: specifiers
```

The shadow `:fn` build emits **bare** `import * from "ai"` /
`from "@ai-sdk/amazon-bedrock"` (via `:js-provider :import` +
`:keep-as-import`). Those bare specifiers are resolved at runtime by
`plugin/functions/deno.json`, which the trex function loader wires in via the
`imports` field on the api entry (`/functions/deno.json`).

## Build

```sh
cd agent
npm install
npm run test     # shadow-cljs compile test && node out/test.cjs
npm run dist     # release fn  +  copy out/handler.js -> plugin/functions/handler.js
```

`npm run dist` is what you run before mounting / committing the plugin: it
rebuilds the ESM handler and refreshes the committed copy under
`plugin/functions/`.

## Mounting into the trex node

`plugin/` is bind-mounted read-only into the trex container at
`/usr/src/plugins-dev/pythia-agent` (see `docker-compose.atlas3-trex.yml`).
Plugin discovery runs once at boot, so adding/altering the mount needs a
restart:

```sh
docker compose -f docker-compose.atlas3-trex.yml restart trex
```

The boot log will show `add fn /agent @ /usr/src/plugins-dev/pythia-agent/functions`.

## npm deps in the worker

The container's Deno can fetch npm packages at runtime (registry reachable);
the first request resolves and caches `ai` + `@ai-sdk/amazon-bedrock` into the
deno npm cache. No vendoring required. The deno.json pins
`@ai-sdk/amazon-bedrock@^4.0.115` (the line that fixes the zod-v4 tool-use
stream-schema bug) and `ai@^6`.

## Calling it

Routes require an `apikey: <service_role>` header (trex `pluginAuthz`). POST a
UIMessage array:

```sh
curl -sN -X POST http://localhost:8001/plugins/trexsql/agent/chat \
  -H "apikey: $SERVICE_ROLE" -H "content-type: application/json" \
  -d '{"messages":[{"role":"user","parts":[{"type":"text","text":"reply with exactly: pong"}]}],
       "context":{"route":"/atlas/#/cohortdefinitions","artifact":null}}'
```

SSE frames stream back: `text-delta` deltas then `finish`.
