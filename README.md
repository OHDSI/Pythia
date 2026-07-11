# trex-dx — Pythia plugin

Pythia is the AI-assisted cohort design advisor for ATLAS v3.0. It is a
single-spa micro-frontend that mounts inside the ATLAS host and talks to
the bao `/chat` backend.

## Development

```bash
npm install      # install dependencies
npm run dev        # vite dev server
npm run type-check # vue-tsc type check
npm test           # run unit tests (vitest)
npm run build      # build the system module into dist/
```

`npm run build` emits a SystemJS module at `dist/index.system.js` plus
`dist/style.css`. The ATLAS host loads these as a plugin; copy the built
artifacts to the host's `public/plugins/pythia-plugin/` (or wire up your
deployment to pull the CI `pythia-plugin-dist` artifact).

## Running with Atlas3 (Docker)

`docker-compose.yml` brings up the full Atlas3 + Pythia stack (Postgres,
WebAPI, and the frontend) with the Pythia plugin built from this repo.

```bash
cp .env.example .env          # fill in AWS_BEARER_TOKEN_BEDROCK
docker compose up -d
```

## Route manifest

`src/routes.manifest.json` is a vendored copy of ATLAS v3.0's generated
route manifest, consumed by `src/route-manifest.ts`. Refresh it by running
`npm run generate:routes` in the Atlas3 root and copying
`src/router/routes.manifest.json` over the vendored file.

## Capability manifest

`agent/resources/capabilities.manifest.json` is a vendored copy of ATLAS
v3.0's generated capability manifest — the single source of truth for the 19
artifact-editing tool schemas the Pythia agent proposes (ATLAS owns those
schemas). It is baked into the agent build at compile time via
`shadow.resource/inline` in `agent/src/pythia/manifest.cljs` (`resources` is on
`:source-paths` in `agent/shadow-cljs.edn`). The 3 conversation tools
(`ask_user`, `create_plan`, `update_plan_step`) have no ATLAS executor and stay
authored inline in `agent/src/pythia/tools/client.cljs`. Refresh the manifest by
running `npm run generate:capabilities` in the Atlas3 root and copying
`src/plugins/host/capabilities/capabilities.manifest.json` over the vendored
file.

## CI

`.github/workflows/ci.yml` runs on every push and pull request to `main`
and `develop`: it installs dependencies, type-checks, runs the unit tests,
builds the plugin, and uploads the build as the `pythia-plugin-dist`
artifact.
