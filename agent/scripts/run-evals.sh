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

# Route selection. The canonical /WebAPI proxy route is only used when it is
# explicitly requested, because a passing health check does NOT mean it can run
# an eval: on the currently pinned trexsql image GET /eve/v1/health returns 200
# while the streaming turn dies inside the proxy servlet (ring response copy
# throws, visible in `docker compose logs trex`). Selecting it on the strength
# of the health probe made every eval sit until its timeout with no output and
# no error — half an hour to learn nothing. The sidecar talks to the agent
# mount directly and runs the same suite in seconds, so it is the default.
#
# Set PYTHIA_EVAL_URL to force a specific route (e.g. the canonical one, to
# check whether a newer trexsql image has fixed the streaming path).
URL="${PYTHIA_EVAL_URL:-}"

if [ -n "$URL" ]; then
  if ! curl -skf --max-time 10 "$URL/eve/v1/health" >/dev/null 2>&1; then
    echo "PYTHIA_EVAL_URL=$URL is not reachable at /eve/v1/health" >&2
    exit 1
  fi
  # Caddy's dev TLS cert is self-signed (tls internal).
  cd "$EVAL_ROOT"
  NODE_TLS_REJECT_UNAUTHORIZED=0 exec npx eve eval --strict --junit .eve/junit.xml --url "$URL" "$@"
fi

# Default: local sidecar onto the agent mount, injecting the apikey header eve
# cannot send itself.

KEY="$(cd ../.. && docker compose exec -T postgres psql -U postgres -d testdb -t -A \
  -c "SELECT value #>> '{}' FROM trexdb.setting WHERE key='auth.serviceRoleKey'" 2>/dev/null | tr -d '[:space:]' || true)"

if [ -z "$KEY" ]; then
  echo "pythia agent not reachable via the local sidecar" >&2
  echo "is the stack up? -> docker compose up -d (from the repo root)" >&2
  echo "fallback also failed: could not extract auth.serviceRoleKey via docker compose exec postgres psql" >&2
  exit 1
fi

DIRECT_URL="http://localhost:8001/plugins/ohdsi/pythia"
if ! curl -sf --max-time 10 -H "apikey: $KEY" "$DIRECT_URL/eve/v1/health" >/dev/null 2>&1; then
  echo "direct mount $DIRECT_URL/eve/v1/health is unhealthy even with apikey" >&2
  exit 1
fi

EVAL_PROXY_PORT="${EVAL_PROXY_PORT:-8901}"
EVAL_PROXY_UPSTREAM="$DIRECT_URL" EVAL_PROXY_APIKEY="$KEY" EVAL_PROXY_PORT="$EVAL_PROXY_PORT" \
  node ../scripts/eval-auth-proxy.mjs &
PID=$!
trap 'kill "$PID" 2>/dev/null || true' EXIT

PROXY_URL="http://127.0.0.1:${EVAL_PROXY_PORT}"
ready=0
for _ in $(seq 1 20); do
  if curl -sf --max-time 1 "$PROXY_URL/eve/v1/health" >/dev/null 2>&1; then
    ready=1
    break
  fi
  sleep 0.5
done

if [ "$ready" -ne 1 ]; then
  echo "eval-auth-proxy sidecar did not become healthy at $PROXY_URL/eve/v1/health within 10s" >&2
  exit 1
fi

set +e
( cd "$EVAL_ROOT" && npx eve eval --strict --junit .eve/junit.xml --url "$PROXY_URL" "$@" )
status=$?
set -e
exit "$status"
