# Spike: Agent auth — `pluginAuthz`, service credential, forward-from-bao

**Task 0.4** · 2026-06-11 · investigative spike (findings only, no production code).

Migrating the Pythia agent out of the trexsql native lib (port 8080, **no** auth gate)
into a ClojureScript Deno function mounted at `/plugins/trexsql/agent` on the trex node
(port 8001). Mounted plugin routes on :8001 are gated by `pluginAuthz`, which returns 401
for the OHDSI WebAPI JWT the Pythia frontend sends. This spike determines what credential
`pluginAuthz` actually accepts and what the migrated auth design should be.

All file refs are in the `trex` repo (`/Users/ph/code/trex`) unless noted. Running stack:
compose project `atlas3-trex`, node container `atlas3-trex-trex-1`, OHDSI DB
`atlas3-trex-postgres-1` (database `testdb`).

---

## 1. What `pluginAuthz` accepts (and how each is verified)

Every plugin function route is registered in `core/server/plugin/function.ts:307`:

```
app.all([fullSource, fullSource + "/*"], apiLimiter, authContext, pluginAuthz, handler)
```

So the chain is **always** `authContext` → `pluginAuthz`. `authContext`
(`core/server/middleware/auth-context.ts`) populates `req.pgSettings`; `pluginAuthz`
(`core/server/middleware/plugin-authz.ts`) makes the allow/deny decision off it.

`pluginAuthz` logic (`plugin-authz.ts:9-51`):
- `app.user_role === "admin"` → **bypass** (next, no scope check). service_role maps to this.
- else if **no** `app.user_id` → **401 Unauthorized** (`:17-20`). This is why `anon` is rejected.
- else compute required scopes by regex-matching `req.originalUrl` against
  `REQUIRED_URL_SCOPES` (`:24-28`); if none match → allow (`:30-32`); otherwise the user's
  application roles must grant one of the scopes, else **403** (`:45-48`).

All token verification is `verifyAccessToken` (`core/server/auth/jwt.ts:134`): an
**HS256 JWT, HMAC-SHA256** over `header.body`, key = HKDF-derived secret from
`TREX_ROOT_KEY` (label `trex.jwt.hs256.v1`, `auth/keys.ts:5`, `jwt.ts:67-71`). Signature
+ `exp` checked; **no DB lookup** for the token itself (app-roles lookup is separate).

### Accepted-credentials table

| Credential | Header / cookie | Verification | Resulting `pgSettings` | Passes `pluginAuthz`? |
|---|---|---|---|---|
| **User access token** (trex-issued, `role:"authenticated"`, `app_metadata.trex_role`) | `Authorization: Bearer <jwt>` **or** `sb-access-token=<jwt>` cookie | `verifyAccessToken` (HS256, HKDF key); must NOT be role `service_role`/`anon` (`auth-context.ts:51`) | `app.user_id=sub`, `app.user_role=trex_role`; app-roles loaded from `trexdb.user_role` (`:64-70`) | **Yes** if user_id present & scopes satisfied |
| **service_role key** | `apikey: <jwt>` header **only** | `verifyAccessToken`; `claims.role==="service_role"` (`:96`) | `role=service_role`, **`app.user_role=admin`** (`:99`) | **Yes** — admin bypass |
| **anon key** | `apikey: <jwt>` header | `verifyAccessToken`; `claims.role==="anon"` (`:88`) | `role=anon`, **no `app.user_id`** (`:89-92`) | **No** — 401 (no user_id) |
| anything else / none | — | falls through | `role=anon` (`:108`) | **No** — 401 |

Key design point (`auth-context.ts:42-51`): `service_role`/`anon` keys are **rejected in the
Bearer / cookie channels** and only honoured via the `apikey` header. This deliberately
prevents a leaked URL or browser context from wielding the long-lived admin key.

---

## 2. Auth systems in play

There are **two** auth surfaces, but only one is relevant to `pluginAuthz`:

**(a) Supabase/GoTrue-style HS256 tokens — THIS is what `pluginAuthz` uses.**
`core/server/auth/auth-router.ts` (mounted at `${BASE_PATH}/auth/v1`, `index.ts:126`) is a
hand-rolled GoTrue-compatible router. It signs user access tokens (`signAccessToken`,
`jwt.ts:89`) and mints the static `anon` / `service_role` keys (`generateAnonKey` /
`generateServiceRoleKey`, `jwt.ts:174-204`) — both are HS256 JWTs with
`{role, iss:"supabase", exp: ~year 2126}`, signed with the same HKDF key. Confirmed live
(see §5): the running service_role key decodes to `{"role":"service_role","iss":"supabase",
"exp":4934420779}`, header `{"alg":"HS256","typ":"JWT"}`.

**(b) better-auth — NOT used by `pluginAuthz`.** `core/server/auth.ts` instantiates
`betterAuth(...)` with the `admin`, `jwt`, and `oidcProvider` plugins
(`auth.ts:1-2, 111-121`), mounted under `${BASE_PATH}/api/auth`. Its session secret is the
separate HKDF label `trex.better-auth.session.v1`. better-auth/oidcProvider issues its own
tokens (RS256-style, different signer) which `verifyAccessToken` (HS256, HKDF key) would
**reject**. So better-auth is the interactive SSO/OIDC surface, irrelevant to service-to-
service authz here.

### Secret names & derivation (no values)
- **`TREX_ROOT_KEY`** — 32 random bytes, base64. Generated once by `scripts/derive-secrets.ts:35-39`
  into `secrets/root.env` (mode 0600). Root of everything.
  Confirmed present: `trex-dx/secrets/root.env` contains exactly `TREX_ROOT_KEY`.
- **JWT signing key** — HKDF(`TREX_ROOT_KEY`, label `trex.jwt.hs256.v1`), base64. Emitted into
  `secrets/derived.env` under **multiple aliases** for downstream verifiers
  (`derive-secrets.ts:56, 77-81`): `PGRST_JWT_SECRET`, `AUTH_JWT_SECRET`, `API_JWT_SECRET`,
  `METRICS_JWT_SECRET` — all the **same** HMAC secret. Confirmed: `trex-dx/secrets/derived.env`
  holds these names (no anon/service_role key in this file).
- **`auth.anonKey` / `auth.serviceRoleKey`** — the actual static keys. Minted at runtime by
  `ensureAuthKeys` (`core/server/auth/api-keys.ts:37-51`) and stored in DB table
  **`trexdb.setting`** (rows keyed `auth.anonKey`, `auth.serviceRoleKey`, `auth.jwtSecret`).
  They are **not** written to any `secrets/*.env` file. Rotatable via `rotateServiceRoleKey`
  (`jwt.ts:235`).

---

## 3. Is a real `client_credentials` flow available?

**No.** `grep -rn "client_credentials\|clientCredentials"` across the whole `trex` repo
(*.ts/*.js/*.clj/*.cljs) returns **zero** hits. The only token endpoint that issues
`pluginAuthz`-acceptable tokens is `POST ${BASE_PATH}/auth/v1/token`
(`auth-router.ts:249-259`), and it supports only `grant_type=password` and
`grant_type=refresh_token` — both require an end-user email+password. There is **no**
confidential-client (client_id + client_secret → short-lived service token) grant.

better-auth's `oidcProvider` exists but (i) does not enable a client-credentials grant in
its config (`auth.ts:117-120`), and (ii) even if it did, its tokens are signed by a
different key and would fail `verifyAccessToken`'s HS256 check. The MCP `apikey`/`sbp_`
keys (`index.ts:153-242`) are a separate admin-gated surface checked elsewhere, **not** by
`pluginAuthz`.

**Canonical service credential = the static `service_role` key** (HS256 JWT,
`role:"service_role"`). It lives in **`trexdb.setting` row `auth.serviceRoleKey`** (DB:
`testdb` in the running stack). A forwarder attaches it via the **`apikey: <service_role>`
header** (NOT `Authorization: Bearer`, which `auth-context.ts:42-51` rejects for this role).

---

## 4. User-JWT validation & "forward from bao" viability

**How the user JWT is validated today.** The OHDSI WebAPI runs embedded in the trex node on
:8080 (`docker-compose.atlas3-trex.yml:74-81`, booted via pgwire `webapi_start()`, lines
164-196). It uses OHDSI's standard **Spring Security / atlas-security DB auth**
(`SECURITY_AUTH_DB_ENABLED: "true"`, compose `:128`). The frontend logs in at
`/WebAPI/user/login/db` and receives the OHDSI Bearer JWT; WebAPI's own Spring Security
filter chain validates it on each `/WebAPI/*` call. The Atlas3 Caddy fronts it:
`handle /WebAPI/* { reverse_proxy {$WEBAPI_HOST}:{$WEBAPI_PORT:8080} }` (`trex-dx/Caddyfile:7-8`).

**The trexsql servlet has no auth of its own.** `org.trex.TrexServlet` is registered as a
plain `ServletRegistrationBean(servlet, "/trexsql/*")`
(`TrexSQLAutoConfiguration.java:67-71`) → serves `/WebAPI/trexsql/*`. It does no JWT check
(`servlet.clj:99-111`); whether `/WebAPI/trexsql/*` is `permitAll` or protected is decided
by OHDSI WebAPI's Shiro/Spring config inside the WebAPI image, **not** by any file in bao.
The current in-lib agent (`/WebAPI/trexsql/agent/chat`, `agent/routes.clj:205-208`)
therefore inherits whatever WebAPI applies to `/trexsql/*` and adds nothing.

**The user JWT already authorizes the agent's tool calls.** Agent tools call WebAPI at
`http://localhost:8080/WebAPI` and **forward the caller's `Authorization` Bearer header**
(`agent/tools/_search_util.clj:7-14, 34-40`). So the user JWT must keep flowing through to
the agent for tool calls regardless of how the route itself is gated.

**Is "validate at bao :8080, forward to :8001 with a service credential" viable? — Yes.**
- Keep the path `/WebAPI/trexsql/agent/*` on :8080 so it traverses WebAPI's existing Spring
  Security chain → the **user JWT is validated for free**. (If `/trexsql/*` is currently
  `permitAll` in the WebAPI image, the forwarder must add its own `verifyAccessToken`-style
  check or the security config must be tightened to protect that path — verify against the
  deployed WebAPI's security config.)
- bao **already has a reverse-proxy** (`trexsql/proxy.clj` `proxy-request`) that strips
  hop-by-hop headers and forwards the rest to a `base-url`. A new Reitit route
  `/agent/*` in `webapi.clj`/`agent/routes.clj` can call `proxy-request` to
  `http://localhost:8001/plugins/trexsql/agent`, **adding `apikey: <service_role>`** and
  **preserving the user's `Authorization: Bearer`** so the function (and its downstream
  tool calls) still sees the user JWT. The service_role key passes `pluginAuthz` (proven §5);
  the user Bearer rides along untouched.

**Where the forwarder lives — recommendation: in bao's Clojure servlet** (a new
`/agent/*` Reitit route delegating to `proxy.clj`). It already runs in-process on :8080
behind WebAPI security, already forwards headers, and is the natural home. The service_role
key would be read at init from `trexdb.setting` (or injected as an env var derived from it).

*Alternative if bao-servlet proxying is undesirable:* a **Caddy/sidecar** layer — Caddy
`handle /WebAPI/trexsql/agent/*` that `reverse_proxy`s to `:8001/plugins/trexsql/agent` with
a header transform attaching `apikey`. Tradeoff: Caddy cannot *validate* the OHDSI JWT
(it only routes), so this loses the "validate at :8080" benefit unless it still proxies the
agent path through WebAPI first; and the service_role key must be injected into Caddy's env,
widening its blast radius. The in-bao route is preferred.

---

## 5. Proof the service credential passes the gate

Live, against `atlas3-trex-trex-1` on :8001. The plugin route probed is
`/plugins/trex/pg-meta-api/` (a registered function route; scope prefix `/trex` comes from
`scopeUrlPrefix` on the `@trex/pg-meta` plugin name). The service_role key was read from
`trexdb.setting` into a shell variable and **never printed**.

```
no-auth      -> 401          # gated
apikey=anon  -> 401          # anon has no app.user_id → pluginAuthz 401
apikey=srk   -> 500          # PASSED authz; 500 is the pg-meta worker (WASM load error)
Bearer=srk   -> 401          # service_role rejected in Bearer channel (auth-context.ts:42-51)
```

The `apikey=service_role` response body was a worker-level runtime error
(`ENOENT ... libpg-query.wasm` from the pg-meta function) — i.e. the request reached the
**worker behind** `authContext`+`pluginAuthz`, conclusively proving the credential cleared
the gate. The contrast with `Bearer=srk → 401` confirms the channel rule: a forwarder must
send the service_role key in **`apikey`**, not `Authorization`.

Decoded service_role claims (structure only): `{"role":"service_role","iss":"supabase",
"iat":...,"exp":4934420779}`, header `{"alg":"HS256","typ":"JWT"}`. 3-segment JWT,
length 122 chars analog.

---

## Recommended auth design (for the migrated agent)

1. **Keep `/WebAPI/trexsql/agent/*` on :8080** so the request passes OHDSI WebAPI's existing
   Spring Security chain and the **user's OHDSI Bearer JWT is validated there** (confirm/
   tighten the WebAPI security config so `/trexsql/agent/*` is *protected*, not `permitAll`).
2. **Add a thin `/agent/*` proxy route in bao's Clojure servlet** that reverse-proxies (reuse
   `trexsql/proxy.clj`) to `http://localhost:8001/plugins/trexsql/agent`, **attaching the
   static `service_role` key as the `apikey` header** (source: `trexdb.setting.auth.serviceRoleKey`)
   to satisfy `pluginAuthz`, while **passing the user's `Authorization: Bearer` through** so
   the agent function and its WebAPI tool calls (`_search_util.clj`) keep acting as the user.
3. There is **no client_credentials grant** and **no public-function escape hatch** — the
   canonical service credential is the static `service_role` key, and `apikey` is the only
   channel that accepts it.

### Public-function escape hatch?
**None.** `_addFunction` unconditionally wires `authContext`+`pluginAuthz` onto every route
(`function.ts:307`); api entries carry no `public`/`authz`/`anon` flag (the only recognized
keys are `source`/`function`/`imports`/`eszip`/`env`/`allowHostFsAccess`/limits — see
`function.ts:286-403`, `addPlugin` at `:458-478`). The closest thing is the **scopes**
mechanism: a route matching no `REQUIRED_URL_SCOPES` entry needs only *a valid user_id or
service_role* (not a specific scope) — but it still requires authentication; `anon` never
passes.
