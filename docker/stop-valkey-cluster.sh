#!/usr/bin/env bash
#
# Stops and removes the 3-node Valkey cluster created by start-valkey-cluster.sh.
#

set -euo pipefail

CONTAINER_PREFIX="valkey-cluster"
NETWORK_NAME="valkey-cluster-net"

PORTS=(7001 7002 7003)

# RedisInsight joins the same namespace (start-insights.sh), so it would be left running with dead
# networking once the owner goes away.
docker rm --force redisinsight >/dev/null 2>&1 || true

# Remove in reverse order: the nodes on 7002/7003 share the network namespace of the node on
# 7001, and Docker refuses to remove a container others still depend on for networking.
for ((i = ${#PORTS[@]} - 1; i >= 0; i--)); do
  docker rm --force "${CONTAINER_PREFIX}-${PORTS[i]}" >/dev/null 2>&1 || true
done

# Catch any node left over from an earlier version of this script.
LEFTOVERS=$(docker ps -aq --filter "name=^${CONTAINER_PREFIX}-" || true)
if [[ -n "${LEFTOVERS}" ]]; then
  docker rm --force ${LEFTOVERS} >/dev/null
fi

docker network rm "${NETWORK_NAME}" >/dev/null 2>&1 || true

echo "Valkey cluster stopped and removed."
