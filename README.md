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

`docker-compose.yml` brings up the full Atlas3 stack (Postgres, WebAPI with
the bao backend, and the frontend) with the Pythia plugin built from this
repo. The Atlas3 services build from the `third_party/Atlas3` submodule.

```bash
git submodule update --init   # fetch third_party/Atlas3
cp .env.example .env          # fill in AWS_BEARER_TOKEN_BEDROCK
npm ci && npm run build       # produce ./dist (the plugin bundle)
docker compose up --build     # build images and start the stack
```

Then open https://localhost/atlas/ and click the Pythia FAB. The frontend
is built with `VITE_BAO_AGENT_ENABLED=true` (the published image disables
it), and your locally built `./dist` is mounted over the plugin baked into
the image — so rebuilding the plugin and refreshing the page picks up your
changes.

## Alternative: trex embedded-WebAPI stack

`docker-compose.atlas3-trex.yml` runs the same Atlas3 + Pythia stack but
serves OHDSI WebAPI from trex's embedded WebAPI instead of the standard
`ohdsi/webapi` Java image. The two compose files are alternatives — use
whichever WebAPI backend you want.

```bash
cp .env.example .env          # fill in AWS_BEARER_TOKEN_BEDROCK
docker compose -f docker-compose.atlas3-trex.yml up -d
```

## Route manifest

`src/routes.manifest.json` is a vendored copy of ATLAS v3.0's generated
route manifest, consumed by `src/route-manifest.ts`. Refresh it by running
`npm run generate:routes` in the Atlas3 root and copying
`src/router/routes.manifest.json` over the vendored file.

## CI

`.github/workflows/ci.yml` runs on every push and pull request to `main`
and `develop`: it installs dependencies, type-checks, runs the unit tests,
builds the plugin, and uploads the build as the `pythia-plugin-dist`
artifact.
