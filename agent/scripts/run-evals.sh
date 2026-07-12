#!/usr/bin/env bash
# Run the pythia eve evals against the live docker-compose stack.
#
# Two model paths, configured separately:
#   - agent under eval: BAO_AGENT_MODEL (compose -> TREX_AGENTS_DEFAULT_MODEL);
#     set BAO_AGENT_MODEL=us.anthropic.claude-sonnet-4-6 in .env before
#     `docker compose up` for a Sonnet 4.6 eval run.
#   - judge (this process): AWS_BEARER_TOKEN_BEDROCK + AWS_REGION, loaded from
#     the repo-root .env below.
set -euo pipefail
cd "$(dirname "$0")/../plugin"

# eve's eval CLI verifies that the target agent's /eve/v1/info name matches the
# name of the directory it's invoked from (its package.json `name`, scope-
# stripped, else the dir basename — see eve/dist/src/evals/{target,cli/eval}.js).
# Our published plugin package is @ohdsi/pythia-agent (-> "pythia-agent") but the
# agent mounts/report its name as "pythia" (scope + trex.agents[0].name, hard-
# wired into trex's /WebAPI/trex/pythia proxy). Those two names are both fixed
# and legitimately differ, so we run eve from a tiny eval-project root named
# "pythia" (eval-root/package.json) that symlinks the shared evals/ + .eve/.
# This keeps the eval sources at plugin/evals and artifacts at plugin/.eve
# (what CI uploads) untouched.
EVAL_ROOT="eval-root"
mkdir -p .eve

# Judge credentials for the eve CLI process (agent creds are compose's job).
set -a
[ -f ../../.env ] && . ../../.env
set +a

URL="${PYTHIA_EVAL_URL:-https://localhost/WebAPI/trex/pythia}"

if ! curl -skf --max-time 10 "$URL/eve/v1/health" >/dev/null 2>&1; then
  echo "pythia agent not reachable at $URL/eve/v1/health" >&2
  echo "is the stack up? -> docker compose up -d (from the repo root)" >&2
  exit 1
fi

# Caddy's dev TLS cert is self-signed (tls internal).
cd "$EVAL_ROOT"
NODE_TLS_REJECT_UNAUTHORIZED=0 exec npx eve eval --strict --junit .eve/junit.xml --url "$URL" "$@"
