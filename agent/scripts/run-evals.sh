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

# Judge credentials for the eve CLI process (agent creds are compose's job).
set -a
[ -f ../../.env ] && . ../../.env
set +a

URL="${PYTHIA_EVAL_URL:-https://localhost/WebAPI/trex/pythia}"

if curl -skf --max-time 10 "$URL/eve/v1/health" >/dev/null 2>&1; then
  # Caddy's dev TLS cert is self-signed (tls internal).
  NODE_TLS_REJECT_UNAUTHORIZED=0 exec npx eve eval --strict --junit .eve/junit.xml --url "$URL" "$@"
fi

# Canonical route unhealthy: known trex agent-proxy bug where bodyless GETs
# (/eve/v1/health, /eve/v1/info) 500 through the bao proxy in the currently
# pinned trexsql image. Fixed upstream on trex@fix/agent-proxy-get-body but
# not yet in a released image. Fall back to a local sidecar that talks to
# the agent mount directly and injects the apikey header eve can't send.
echo "notice: $URL/eve/v1/health unreachable — proxy route unhealthy, falling back to local auth-injecting sidecar (trex agent-proxy GET bug; fixed in trex@fix/agent-proxy-get-body)" >&2

KEY="$(cd ../.. && docker compose exec -T postgres psql -U postgres -d testdb -t -A \
  -c "SELECT value #>> '{}' FROM trexdb.setting WHERE key='auth.serviceRoleKey'" 2>/dev/null | tr -d '[:space:]' || true)"

if [ -z "$KEY" ]; then
  echo "pythia agent not reachable at $URL/eve/v1/health" >&2
  echo "is the stack up? -> docker compose up -d (from the repo root)" >&2
  echo "fallback also failed: could not extract auth.serviceRoleKey via docker compose exec postgres psql" >&2
  exit 1
fi

DIRECT_URL="http://localhost:8001/plugins/ohdsi/pythia"
if ! curl -sf --max-time 10 -H "apikey: $KEY" "$DIRECT_URL/eve/v1/health" >/dev/null 2>&1; then
  echo "pythia agent not reachable at $URL/eve/v1/health" >&2
  echo "fallback also failed: direct mount $DIRECT_URL/eve/v1/health unhealthy even with apikey" >&2
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
npx eve eval --strict --junit .eve/junit.xml --url "$PROXY_URL" "$@"
status=$?
set -e
exit "$status"
