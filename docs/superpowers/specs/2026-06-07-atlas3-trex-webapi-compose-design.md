# Atlas3 + trex-embedded-WebAPI Docker Compose — Design

**Date:** 2026-06-07
**Repo:** `trex-dx` (currently holds only `LICENSE` + `README.md`)
**Goal:** Add a single Docker Compose stack that runs the **Atlas3** frontend with
the **pythia** (bao) AI agent enabled, but with the OHDSI WebAPI served by the
**trex** image's *embedded WebAPI* (a DuckDB extension) instead of the upstream
`ohdsi/webapi` Java image. Single-node trex setup.

## Background

Two sibling repos drive this design:

- `../Atlas3` — has a `docker-compose.yml` that runs four services: a preloaded
  Postgres (`ohdsi/broadsea-atlasdb`), a WebAPI image built from
  `ghcr.io/ohdsi/webapi` with a TrexSQL jar + the **bao** plugin baked in, a
  one-shot DB-init that loads SQL into the OHDSI DB, and a Caddy/Vite frontend.
  Pythia = the bao agent, surfaced in the UI by the **build-time** flag
  `VITE_BAO_AGENT_ENABLED` and powered by `AWS_BEARER_TOKEN_BEDROCK` /
  `BAO_AGENT_MODEL` consumed by the bao plugin inside WebAPI.

- `../trex` — ships `docker-compose.dx.yml`, a **single-node** trex stack (one
  node that is `data_node:true` + `trexas` UI/API + `pgwire`). trex carries a
  WebAPI plugin at `plugins/webapi`: a DuckDB loadable extension (`webapi.trex`)
  that, on `LOAD webapi; SELECT webapi_start();`, `dlopen`s
  `libwebapi-native.so` (a GraalVM native image) and boots OHDSI WebAPI on port
  **8080** inside the trex process. Critically, `Dockerfile.native-lib` states the
  native lib is built "embedding OHDSI WebAPI **+ the trexsql/bao integration**"
  and pulls in `plugins/bao/java` — so the **bao/pythia endpoints live inside
  trex's embedded WebAPI**, making pythia feasible without the upstream Java image.

### How trex's WebAPI starts (the key mechanic)

There is **no automatic boot**. WebAPI listens on 8080 only after something runs,
against the trex node, the SQL:

```sql
LOAD webapi;
SELECT webapi_start();
```

The extension (`plugins/webapi/src/lib.rs`) reads its native lib path from
`WEBAPI_NATIVE_LIB` (default `libwebapi-native.so` on `LD_LIBRARY_PATH`) and the
embedded Spring app reads standard `SPRING_*` / `DATASOURCE_*` env (see the smoke
harness `plugins/webapi/smoke/trex-run.sh`, which sets `SPRING_APPLICATION_JSON`).

## Decisions (locked)

| Decision | Choice |
|---|---|
| Image source | **Pull published images** (`ghcr.io/ohdsi/trexsql:latest`); **build only the Atlas3 frontend locally** so `VITE_BAO_AGENT_ENABLED=true` is baked in. |
| Support files | **Vendor** `Caddyfile`, `atlasdb/*.sql`, and `.env.example` into `trex-dx`. |
| Pythia scope | **Must work end-to-end.** |
| WebAPI start trigger | **One-shot init container** runs the `LOAD/start` SQL via `psql` over trex's pgwire port. |

## Architecture

A single new compose file: **`docker-compose.atlas3-trex.yml`** in the repo root.
Single-node. Distinct compose project name (e.g. `atlas3-trex`) so it never
collides with the default trex or Atlas3 stacks.

### Services

1. **`trex-init`** — one-shot. `ghcr.io/ohdsi/trexsql:latest`, entrypoint
   `/usr/local/bin/trex-init`. Generates `./secrets/{root,derived}.env` (trex's
   crypto/JWT keys). Short-circuits if they exist.

2. **`postgres`** — `postgres:16`, DB `testdb`. trex's **own** metadata DB
   (auth keys, plugin registry, schema migrations). Mirrors trex's dx compose.

3. **`atlas3-postgres`** — `ohdsi/broadsea-atlasdb:2.3.0`. The **preloaded OHDSI
   DB** the embedded WebAPI talks to (webapi schema, flyway, CDM/vocab). Mirrors
   Atlas3.

4. **`trex`** — the single node. `ghcr.io/ohdsi/trexsql:latest` with a
   `SWARM_CONFIG` defining one node: `data_node:true` + `flight` + `trexas`
   (8001/8000) + `pgwire` (5432). **Publishes host port for `8080`** (the
   embedded WebAPI runs in this process). Environment carries:
   - trex core: `SWARM_NODE`, `DATABASE_URL` → `postgres`, `SCHEMA_DIR`,
     `BASE_PATH=/trex`, etc. (copied from dx compose).
   - WebAPI/Spring: `DATASOURCE_*`, `SPRING_FLYWAY_*`, `SECURITY_AUTH_*`,
     `TREXSQL_*` pointing at **`atlas3-postgres`** (copied from Atlas3's webapi
     service env).
   - pythia/bao: `AWS_REGION`, `BAO_AGENT_MODEL`, and `AWS_BEARER_TOKEN_BEDROCK`
     via `env_file: .env`.
   - `WEBAPI_NATIVE_LIB` only if the default name doesn't resolve on
     `LD_LIBRARY_PATH` (verify during implementation).
   - Healthcheck: trex `/trex/api/ready` returns 200 (node up).

5. **`webapi-start`** — one-shot. `postgres:16-alpine` (for `psql`). `depends_on`
   trex healthy. Runs `psql -h trex -p 5432 ... -c "LOAD webapi; SELECT
   webapi_start();"`, then polls `http://trex:8080/WebAPI/info` until 200. This is
   the explicit boot trigger for the embedded WebAPI.

6. **`atlas3-db-init`** — one-shot. `postgres:16-alpine`. `depends_on`
   `webapi-start` completed. Runs the vendored `atlasdb/*.sql` against
   `atlas3-postgres` (sources, source_daimon, auth users, permissions). Mirrors
   Atlas3.

7. **`atlas3-frontend`** — **built locally** from `../Atlas3` (the one external
   build dependency), `build-arg VITE_BAO_AGENT_ENABLED=true` plus the auth
   build-args from Atlas3's compose. Caddy on host 80/443. Env: `WEBAPI_HOST=trex`
   (so the vendored Caddyfile reverse-proxies `/WebAPI/*` to `trex:8080`),
   `WEBAPI_URL`, `CADDY_HOSTNAME`, `CADDY_TLS_DIRECTIVE`. `depends_on`
   `atlas3-db-init` completed.

### Dropped from trex's stack

`postgrest`, `studio`, `realtime` — not needed; trex here is only a WebAPI host,
not its own UI. (Verify the node still reaches `/trex/api/ready` healthy without
them; add back only if boot depends on them.)

### Data flow

```
browser → Caddy (atlas3-frontend :80/:443)
            ├─ /atlas/*   → static Atlas3 SPA (pythia FAB visible: build flag on)
            └─ /WebAPI/*  → trex:8080  (embedded WebAPI, incl. bao/pythia endpoints)
                                │
        bao agent ── AWS_BEARER_TOKEN_BEDROCK ──→ Bedrock
                                │
                          atlas3-postgres (OHDSI DB: webapi schema, CDM, sources)
        trex core ──────→ postgres (testdb: trex metadata)
```

### Boot ordering

`trex-init` + `postgres` + `atlas3-postgres` → `trex` (healthy) → `webapi-start`
(boots WebAPI, completes) → `atlas3-db-init` (loads SQL, completes) →
`atlas3-frontend`.

## Vendored files (new in trex-dx)

- `Caddyfile` (copy of Atlas3's).
- `atlasdb/050_auth_user.sql`, `100_populate_source_source_daimon.sql`,
  `200_admin_permissions.sql`, `210_trex_proxy_permissions.sql`.
- `.env.example` (`AWS_BEARER_TOKEN_BEDROCK=`, `BAO_AGENT_MODEL=...`,
  `VITE_BAO_AGENT_ENABLED=true`, `POSTGRES_PASSWORD=mypass`).
- `secrets/` is generated by `trex-init` (gitignored).
- README section documenting prerequisites (`../Atlas3` checked out for the
  frontend build), `.env` setup, and `docker compose -f
  docker-compose.atlas3-trex.yml up -d`.

## Risks / verification steps (carried into the plan)

1. **Published trex image bundles the WebAPI artifacts.** Confirm
   `ghcr.io/ohdsi/trexsql:latest` actually contains `webapi.trex` (in the
   extensions dir) and `libwebapi-native.so` (on `LD_LIBRARY_PATH`). If CI did
   not stage them for the host arch, fall back to building the trex image
   (`Dockerfile.native-lib` → image) — would reopen the "pull vs build" choice.
2. **`webapi_start()` reads container env.** Verify the embedded Spring app picks
   up `DATASOURCE_*`/`SPRING_*` from the trex container environment (vs. requiring
   `SPRING_APPLICATION_JSON` as the smoke harness uses). Adjust env shape if so.
3. **Node boots without postgrest/studio/realtime.** Confirm `/trex/api/ready`
   reaches 200; re-add the minimum dependency if not.
4. **bao endpoint path parity.** Confirm the frontend's pythia calls hit WebAPI
   paths that trex's embedded bao actually serves (the upstream Atlas3 image and
   trex's bao integration must expose the same routes).
5. **pgwire accepts `LOAD`/`SELECT webapi_start()`.** Confirm trex's pgwire
   surface permits loading the extension and running the function (auth/role).

## Out of scope

- Production hardening (TLS certs beyond Caddy internal, secret management).
- Rebuilding/publishing the trex image (only used if risk #1 forces it).
- Any change to `../trex` or `../Atlas3` source.
