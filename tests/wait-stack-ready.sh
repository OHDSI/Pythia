#!/usr/bin/env bash
# Gate recordings on the stack actually being ready. A fixed sleep after a trex
# restart is not enough: the container reports healthy before the agent runtime
# will answer, and the recording then dies on "Failed to fetch" at the first
# agent call, wasting a five-minute take.
set -u
for i in $(seq 1 60); do
  health=$(docker inspect atlas3-trex-trex-1 --format '{{.State.Health.Status}}' 2>/dev/null)
  webapi=$(curl -s -o /dev/null -w '%{http_code}' http://localhost:8080/WebAPI/info 2>/dev/null)
  agent=$(docker exec atlas3-trex-trex-1 sh -c 'curl -s -o /dev/null -w "%{http_code}" http://localhost:8001/trex/api/ready' 2>/dev/null)
  if [ "$health" = "healthy" ] && [ "$webapi" = "200" ] && [ "$agent" = "200" ]; then
    echo "ready (health=$health webapi=$webapi agent=$agent) after ${i}0s"
    sleep 5   # small settle margin
    exit 0
  fi
  sleep 10
done
echo "NOT READY (health=${health:-?} webapi=${webapi:-?} agent=${agent:-?})" >&2
exit 1
