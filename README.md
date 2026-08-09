<p align="center">
  <img src="figures/pythia-logo.png" alt="Pythia" width="200">
</p>

<h1 align="center">Pythia</h1>

<p align="center">
  The AI-assisted cohort design advisor for ATLAS v3.0.
</p>

Pythia sits inside ATLAS as a chat panel and proposes the cohort you describe:
it searches the vocabulary for real concept ids, drafts entry events, inclusion
rules and exclusions, and hands each one to you as a card to accept or reject.
Nothing reaches the editor without your approval, and every concept comes from a
search against the connected data source rather than from model recall.

## Demos

Both walkthroughs are unedited recordings against the OHDSI Eunomia demo
database. Click a still to download the video from the release.

### Designing a phenotype

<a href="https://github.com/OHDSI/Pythia/releases/download/v0.1.0/pythia-cohort.mp4">
  <img src="figures/phenotype-poster.png" alt="Pythia builds a phenotype in ATLAS" width="860">
</a>

Adults aged 40 and over with osteoarthritis starting ibuprofen — new users only,
365 days of prior observation, excluding anyone with a previous gastrointestinal
bleed or peptic ulcer. Pythia proposes each criterion for approval, has one
proposal rejected and adapts, saves the cohort, generates it (341 people from a
base of 1,440), and then reads its own attrition back: osteoarthritis removes
almost nobody, the GI exclusion 15.6%, the age restriction 53.3%. **3:36**

### Building and running an analysis

<a href="https://github.com/OHDSI/Pythia/releases/download/v0.1.0/pythia-pathways.mp4">
  <img src="figures/demo-poster.png" alt="Pythia builds and runs a pathway analysis" width="860">
</a>

A treatment pathway analysis end to end: proposing criteria, saving three
cohorts, assembling the pathway analysis, running it, and reading the result —
2,040 of 2,689 sinusitis patients (75.9%) had a recorded antibiotic pathway,
amoxicillin-only dominating at 70%. **4:08**

## Running it

You need Docker and a Bedrock bearer token. The submodule is **not** required —
ATLAS is pulled as a published image.

```bash
git clone https://github.com/OHDSI/Pythia.git
cd Pythia
cp .env.example .env     # set AWS_BEARER_TOKEN_BEDROCK; Pythia cannot answer without it
docker compose up -d
```

Then open <https://localhost> and sign in as `admin` / `admin`. The certificate
is self-signed, so your browser will warn once. The embedded OHDSI WebAPI is at
<http://localhost:8080/WebAPI>.

First start pulls several GB of images and initialises the demo database, so
give it a few minutes. `docker compose logs -f trex` shows progress.

### Choosing the model

Pythia runs against whatever the trex agent runtime is pointed at:

```bash
BAO_AGENT_MODEL=us.anthropic.claude-sonnet-4-6      # a Bedrock model id
BAO_AGENT_MODEL_SPEC=openai/openai.gpt-5.6-terra    # or a full provider/model spec
```

`BAO_AGENT_MODEL_SPEC` wins when both are set. An `openai/…` spec is served
through the OpenAI-compatible Bedrock endpoint; override `OPENAI_BASE_URL` to
point somewhere else.

### Pinning or overriding images

`ATLAS_IMAGE` and `TREX_IMAGE` override the pinned defaults, which are a
published ATLAS build and a trexsql digest.

To change ATLAS itself rather than consume it, check out the submodule and build
locally — that shadows the pinned image:

```bash
git submodule update --init third_party/Atlas3
docker compose build atlas3-frontend && docker compose up -d atlas3-frontend
```

## What's in here

- **`src/`** — the Pythia frontend plugin (`@ohdsi/pythia-plugin`): a Vue 3
  single-spa micro-frontend that mounts inside the ATLAS host as the chat panel.
  `npm run build` emits a SystemJS module (`dist/index.system.js` +
  `dist/style.css`) that the host loads as a plugin.
- **`agent/`** — the Pythia agent (`@ohdsi/pythia-agent`): prompt, resources and
  tools authored in ClojureScript, compiled to a trex agents plugin that trex's
  shared agent runtime serves. See `agent/README.md`.
- **`docker-compose.yml`** — the runnable stack: Postgres, the trex node with
  embedded OHDSI WebAPI and the Pythia agent, and the ATLAS frontend with the
  Pythia panel enabled.
- **`frontend-assets/`** — prebuilt plugin artifacts mounted into the ATLAS
  container.
- **`trexsql-cache/`** — the TrexSQL per-source cache, bind-mounted so it
  survives container recreation (it is slow to rebuild and gitignored).
- **`tests/`** — unit tests for the frontend plugin, plus the Playwright probes
  and recorder used to verify the stack end to end.

## Development

The plugin:

```bash
npm install
npm run dev        # vite dev server
npm run build      # SystemJS module into dist/
npm test           # unit tests
```

The agent:

```bash
cd agent
npm test           # ClojureScript unit tests
npm run dist       # compile + regenerate tool wrappers and instructions.md
npm run evals      # live evals against the running stack
```

`npm run dist` regenerates `plugin/agent/instructions.md` from
`src/pythia/prompt.cljc` — edit the prompt there, never the generated file. The
evals need the stack up and judge credentials in `.env`.
