# Pythia

Pythia is the AI-assisted cohort design advisor for ATLAS v3.0. This repo
contains both halves of it:

- **`src/`** — the Pythia frontend plugin (`@ohdsi/pythia-plugin`): a Vue 3
  single-spa micro-frontend that mounts inside the ATLAS host as the chat
  panel. `npm run build` emits a SystemJS module (`dist/index.system.js` +
  `dist/style.css`) that the host loads as a plugin.
- **`agent/`** — the Pythia agent (`@ohdsi/pythia-agent`): prompt, resources,
  and tools authored in ClojureScript and compiled to a trex agents plugin
  that trex's shared agent runtime serves. See `agent/README.md` for details.
- **`docker-compose.yml`** — the full runnable stack: Postgres, the trex node
  with embedded OHDSI WebAPI and the Pythia agent, and the Atlas3 frontend
  (built from `third_party/Atlas3`) with the Pythia panel enabled.
- **`frontend-assets/`** — prebuilt plugin artifacts mounted into the Atlas3
  frontend container.
- **`tests/`** — unit tests for the frontend plugin; agent tests and live
  evals live under `agent/`.

## Running with Docker

```bash
cp .env.example .env   # fill in AWS_BEARER_TOKEN_BEDROCK (required for Pythia to answer)
docker compose up -d
```

Then open https://localhost (self-signed cert; log in as `admin`/`admin`).
The embedded WebAPI is exposed at http://localhost:8080/WebAPI.

## Development

```bash
npm install
npm run dev        # vite dev server for the frontend plugin
npm run build      # build the system module into dist/
```
