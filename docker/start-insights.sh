#!/usr/bin/env bash
#
# Starts RedisInsight at http://localhost:5540 to browse the buckets in the local Valkey cluster.
#
# Run start-valkey-cluster.sh first: this container JOINS THAT CLUSTER'S NETWORK NAMESPACE rather than
# getting one of its own.  The cluster nodes announce themselves as 127.0.0.1:7001-7003, and a
# cluster-aware client follows those announced addresses, so RedisInsight only works if 127.0.0.1 means
# the same thing to it as it does to the nodes.  With its own namespace, 127.0.0.1 would be RedisInsight
# itself: connecting via host.docker.internal reaches one node, but every key in another node's slots
# then fails on a MOVED redirect to an unreachable 127.0.0.1.
#
# Because this container joins an existing namespace it cannot publish ports; 5540 is published by the
# namespace owner (valkey-cluster-7001) via EXTRA_PUBLISHED_PORTS in start-valkey-cluster.sh.
#
# In the RedisInsight UI, add the database as:  host 127.0.0.1, port 7001
#

set -euo pipefail

VALKEY_NAMESPACE_CONTAINER="valkey-cluster-7001"

if [[ -z "$(docker ps -q --filter "name=^${VALKEY_NAMESPACE_CONTAINER}$")" ]]; then
  echo "${VALKEY_NAMESPACE_CONTAINER} is not running -- run docker/start-valkey-cluster.sh first." >&2
  exit 1
fi

docker rm --force redisinsight >/dev/null 2>&1 || true

docker run -d \
    --name redisinsight \
    --net "container:${VALKEY_NAMESPACE_CONTAINER}" \
    -v redisinsight:/data \
    redis/redisinsight:latest >/dev/null

echo "RedisInsight starting at http://localhost:5540"
echo "Add the database with host 127.0.0.1 and port 7001."
