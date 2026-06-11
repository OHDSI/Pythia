# SPIKE: trex node Deno-function HTTP contract + mount path for the Pythia agent

Date: 2026-06-11
Task: 0.1 (migration spike — investigative, not production code)

## TL;DR

- **Can an externally bind-mounted JS/TS function be loaded and served by the running
  trex node? YES — proven.** A trivial plugin placed at
  `/usr/src/plugins-dev/probe/` was discovered, registered, and its HTTP route went
  live after a container restart. Evidence below.
- **BUT the served URL is NOT under `/WebAPI/` and NOT on port 8080.** Mounted
  functions are served by the trexas Deno/Express app on **port 8001** under the
  prefix **`/plugins/<scope>/<source>`**. The current Pythia agent
  (`/WebAPI/trexsql/agent/*` on 8080) is a *different* server — the embedded OHDSI
  WebAPI (the Clojure `trexsql/agent/routes.clj` baked into the native lib). The two
  do not share a runtime. So the migration requires a **Caddy path rewrite** to map
  `/WebAPI/trexsql/agent/*` → the function's `/plugins/...` URL on 8001.
- **Two real blockers** (see "Blockers"): (1) functions are gated by
  `pluginAuthz`, which 401s any request lacking a trex `app.user_id` / admin
  service_role — the current native agent has no such gate; (2) changes need a
  **container restart** to load (no live hot-reload of a new plugin via a public API).

---

## 1. Function discovery & HTTP dispatch

### Discovery
`Plugins.initPlugins(app)` (`/Users/ph/code/trex/core/server/plugin/plugin.ts:124`)
scans two roots at boot, dev first then npm:
- `PLUGINS_DEV_PATH` (`/usr/src/plugins-dev`) — source `"dev"`
- `PLUGINS_PATH` (`/usr/src/plugins`) — source `"npm"`

`scanPluginDirectory` (`/Users/ph/code/trex/core/server/plugin/utils.ts:7`) walks each
root; for every subdir it reads `<dir>/package.json` and requires a `trex` config
object. Directories starting with `@` are recursed into (scope dirs). There is **no
`NODE_ENV` gate in the code** — dev plugins load unconditionally (the MCP docs claim a
`NODE_ENV=development` gate, but the actual `initPlugins` does not implement it; the
running container has `NODE_ENV` empty yet dev plugins are registered).

A plugin is "registered" when `package.json` has a `trex` key. The function sub-config
is `pkg.trex.functions` (dispatched at `plugin.ts:71` → `addFunctionPlugin`).

### HTTP dispatch (the route that gets created)
`addPlugin` in `/Users/ph/code/trex/core/server/plugin/function.ts:405` iterates
`trex.functions.api[]`. For each `{ source, function }` it calls `_addFunction`
(`function.ts:287`), which registers an Express catch-all:

```
fullSource = PLUGINS_BASE_PATH + scopeUrlPrefix(name) + source        // function.ts:306
app.all([fullSource, fullSource + "/*"], apiLimiter, authContext, pluginAuthz, handler) // :307
```

- `PLUGINS_BASE_PATH` = `Deno.env.get("PLUGINS_BASE_PATH") || "/plugins"`
  (`/Users/ph/code/trex/core/server/config.ts:4`). It is **unset** in the running
  container, so the default **`/plugins`** applies. (Do not be misled by an empty
  shell `echo` — the var is simply unset, and the `|| "/plugins"` default kicks in.)
- `scopeUrlPrefix("@scope/name")` = `"/scope"`; for a non-scoped name it is `""`
  (`utils.ts:44`).

The handler (`function.ts:307-402`) builds a **Web `Request`** from the Express req
(`new globalThis.Request(...)`, `function.ts:358`), invokes the worker, and forwards
the worker's **Web `Response`** back — including a dedicated SSE branch that pipes
`text/event-stream` bodies straight through without buffering
(`function.ts:376-388`). This SSE handling is exactly what the agent's streaming
response needs.

### Worker execution
`_callWorker` (`function.ts:167`) runs the function via
`globalThis.EdgeRuntime.userWorkers.create(options)` then `worker.fetch(req)`
(`function.ts:216-218`). `servicePath` = `<dir><function>` (e.g.
`/usr/src/plugins-dev/probe/functions`). The entry file is the directory's
`index.ts`/`index.js`, which calls **`Deno.serve((req: Request) => Response)`** — the
standard Web Fetch API. Confirmed against the real `pg-meta` function
(`/usr/src/plugins-dev/pg-meta/postgres-meta/functions/index.ts`: it ends with
`return new Response(...)` / `Response.json(...)`).

`globalThis.Trex` (e.g. `globalThis.Trex.sql(...)`,
`/Users/ph/code/trex/plugins/devx/functions/index.ts:2149`) is available *inside* the
worker for in-process DB access.

### eszip (optional)
If a function config sets `eszip`, the loader reads a prebuilt bundle
(`function.ts:205-212`) but **falls back to source on disk if the bundle is absent**.
So an unbundled source `index.ts` is fully supported (this is how every dev plugin in
the container runs today). The framework is NOT an immutable eszip baked into the
image — source mounts load fine.

---

## 2. Served URL + port + request/response types

- **Port: 8001** (trexas HTTP). The compose node config is
  `{"name":"trexas",...,"port":8001,"main_service_path":"/usr/src/core/server",...,"tls_port":8000}`
  (`docker-compose.atlas3-trex.yml:20`). The Express app in `core/server` listens on
  8000 in code (`core/server/index.ts:1385`) but the trexas extension exposes it as
  HTTP on **8001** / TLS on 8000.
- **URL prefix: `/plugins/<scope>/<source>`** (and `/<...>/*` subpaths).
- **Port 8080 is a different server**: the embedded OHDSI WebAPI (Spring/native lib),
  which is where the *current* agent lives. `/WebAPI/*` is NOT served by the Deno
  function runtime.

### Request/response objects — can we return a Web `Response`?
**YES.** The worker handler receives a Web Fetch `Request` and returns a Web
`Response`; the Express wrapper consumes `workerResponse.status/headers/body` directly
and streams SSE. This means our ClojureScript code returning the **Vercel AI SDK's
`Response` object directly works** — the runtime already does this for every function.
(The AI SDK `Response` is a standard Web `Response` with a `text/event-stream` body,
which hits the SSE pass-through branch at `function.ts:376`.)

### Evidence — probe was served (proof of external mount loading)
A probe plugin was created in the running container:

```
/usr/src/plugins-dev/probe/package.json   -> { "name":"@trex/probe", "trex":{"functions":{"api":[{"source":"/probe-api","function":"/functions"}]}} }
/usr/src/plugins-dev/probe/functions/index.ts -> Deno.serve((req)=>Response.json({ok:true,...}))
```

After `docker compose -f docker-compose.atlas3-trex.yml restart trex`, the boot log showed:

```
Found plugin probe (v0.0.1) [dev] in /usr/src/plugins-dev/probe
add fn /probe-api @ /usr/src/plugins-dev/probe/functions
Registered plugin probe [dev]
```

Curling the route:

| URL (port 8001)                       | result |
| ------------------------------------- | ------ |
| `/trex/probe-api`                     | 404 (wrong prefix — `/trex` is `BASE_PATH`, not the plugins base) |
| `/plugins/trex/probe-api`             | **401 `{"error":"Unauthorized"}`** — route MATCHED, only `pluginAuthz` gates it |
| `/plugins/trex/probe-api/hello`       | **401** — subpath also matched |

A 401 (not 404) from `pluginAuthz` is the proof the route is registered and dispatch
is wired; only auth stands between the request and the worker. The probe was removed
after testing (`rm -rf /usr/src/plugins-dev/probe`).

---

## 3. What a mounted function must provide + where it mounts

### Files the function must provide (host side, bind-mounted into the container)
A plugin directory containing:

```
<plugin-root>/
  package.json          # MUST contain a "trex" config object
  functions/
    index.ts            # Deno.serve((req: Request) => Response)  (source or eszip)
```

Minimal `package.json` for the agent (recommended scope `@trexsql`, source `/agent`):

```json
{
  "name": "@trexsql/agent",
  "version": "0.0.1",
  "trex": {
    "functions": {
      "env": { "_shared": { "AWS_REGION": "${AWS_REGION}" } },
      "api": [
        { "source": "/agent", "function": "/functions" }
      ]
    }
  }
}
```

With this, the served route is:
`PLUGINS_BASE_PATH("/plugins") + scopeUrlPrefix("@trexsql/agent")("/trexsql") + source("/agent")`
= **`/plugins/trexsql/agent`** (and `/plugins/trexsql/agent/*`) on **port 8001**.
So `/plugins/trexsql/agent/chat` reaches the worker, which sees URL path
`/plugins/trexsql/agent/chat` on its inbound Web `Request`.

### Container mount path
Mount the plugin under the dev plugins root: **`/usr/src/plugins-dev/<name>`**.
(`/usr/src/plugins-dev` is root-owned and not writable by the `node` uid, but a docker
bind-mount overrides it — same precedent as the frontend's
`./frontend-assets/pythia-plugin` mount.)

### docker-compose bind-mount line (add to the `trex` service `volumes:` in `docker-compose.atlas3-trex.yml`)

```yaml
      # Pythia agent as a mounted Deno function — served at /plugins/trexsql/agent/* on :8001
      - ./trex-plugins/agent:/usr/src/plugins-dev/agent:ro
```

(Host path is illustrative; put the built CLJS function + `package.json` under
`./trex-plugins/agent/` with the `functions/index.{js,ts}` entry. Use `:ro`.)

---

## 4. Caddy directive to route `/WebAPI/trexsql/agent/*`

The frontend posts to `/WebAPI/trexsql/agent/chat` and must keep working unchanged.
Currently Caddy sends all `/WebAPI/*` to `trex:8080` (the OHDSI WebAPI). We must carve
out the agent subpath and send it to the function on **8001** with a path rewrite from
`/WebAPI/trexsql/agent/...` → `/plugins/trexsql/agent/...`.

Add this **before** the existing `handle /WebAPI/*` block in `/Users/ph/code/trex-dx/Caddyfile`
(more-specific matcher first; Caddy evaluates `handle` blocks in source order):

```caddyfile
	# Pythia agent → mounted Deno function on the trex node (port 8001).
	# Rewrite the public WebAPI path to the plugin's served prefix.
	handle /WebAPI/trexsql/agent/* {
		uri replace /WebAPI/trexsql/agent /plugins/trexsql/agent
		reverse_proxy {$AGENT_HOST:trex}:{$AGENT_PORT:8001}
	}

	# Everything else under /WebAPI → embedded OHDSI WebAPI (unchanged).
	handle /WebAPI/* {
		reverse_proxy {$WEBAPI_HOST:atlas3-webapi}:{$WEBAPI_PORT:8080}
	}
```

Verified network reachability from the Caddy/frontend container (`atlas3-frontend`,
which serves the SPA AND proxies `/WebAPI/*`):
- `wget http://trex:8001/trex/api/ready` → `{"ready":true}`
- `wget http://trex:8001/plugins/trex/probe-api` → `HTTP/1.1 401 Unauthorized` (route reachable)

So `trex:8001` is reachable from Caddy on the compose network; no new network wiring
needed. (Optionally add `AGENT_HOST: trex` / `AGENT_PORT: "8001"` to the
`atlas3-frontend` service `environment:` to mirror the `WEBAPI_HOST` pattern.)

---

## Does it reload live, or need a restart?

**Needs a restart.** Plugin discovery runs once at boot in `Plugins.initPlugins`.
There is a dynamic-register endpoint `POST {BASE_PATH}/api/plugins/register`
(`core/server/index.ts:248`, `Plugins.registerFromPath` at `plugin.ts:221`) BUT it:
- requires **admin auth**, and
- restricts `path` to `DEVX_WORKSPACE_DIR` / `/var/devx-workspaces` only
  (`index.ts:260-287`),

so it is not a general hot-mount mechanism for `/usr/src/plugins-dev`. For the agent,
plan on a **`docker compose restart trex`** (≈ a few seconds to `ready` in testing)
after adding the bind mount, and on every change to the function's `package.json`
registration. Function *source* changes within an already-registered plugin may be
picked up per-request by the worker module cache settings (`noModuleCache:false`), but
do not rely on that for the spike — restart is the safe, proven path.

Note: the trexas worker re-runs `initPlugins` several times during a single boot (the
boot log shows the "Scanning and registering plugins" block 3×), so registration is
robust across the worker's internal respawns.

---

## Blockers / surprises

1. **AUTH GATE (important behavioral change).** Plugin function routes run through
   `authContext` + `pluginAuthz` (`function.ts:307`). `pluginAuthz`
   (`core/server/middleware/plugin-authz.ts:17`) returns **401 unless** the request
   carries a valid trex identity: an admin/service_role (via `apikey` header → admin,
   `auth-context.ts:96`) or a user with `app.user_id` (Bearer token or
   `sb-access-token` cookie, `auth-context.ts:42-58`). The **current native agent on
   8080 has no such gate** (a plain `curl -X POST .../agent/chat` streamed a response).
   So after migration the agent will REQUIRE the caller to be authenticated to the
   trex node. Action for the implementer: confirm the Atlas3 frontend sends a credential
   that `authContext` accepts (Bearer JWT or `sb-access-token` cookie) on its POST to
   `/WebAPI/trexsql/agent/chat`, and that Caddy forwards `Authorization`/`Cookie`
   headers (default `reverse_proxy` does). If the frontend cannot present a trex token,
   we need either (a) a public/anon allowance for this route, or (b) inject a
   service_role `apikey`. This must be resolved before the agent will work end-to-end.

2. **No live hot-reload** (see section above) — needs `docker compose restart trex`.

3. **The agent is NOT under `/WebAPI/` on the function runtime.** It serves under
   `/plugins/trexsql/agent` on 8001. The Caddy rewrite in section 4 is mandatory; you
   cannot simply point `/WebAPI/trexsql/agent` at 8080 anymore (that is the old native
   route we are removing). Keep the old native agent disabled to avoid two
   implementations answering — but note `/WebAPI/*` (everything except the agent
   subpath) still legitimately goes to 8080.

4. **Not a blocker, a confirmation:** the function framework loads **plain source**
   (no eszip required). External bind-mounted source loads and serves. This is proven,
   not assumed.

### Recommended path (no fallback needed)
External mounting works, so **no sidecar is required**. Mount the CLJS agent as a
dev plugin (`@trexsql/agent`, `source:/agent`), restart `trex`, and add the Caddy
rewrite. Resolve the auth gate (blocker #1) as part of implementation.
