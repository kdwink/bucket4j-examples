#!/usr/bin/env bash
#
# Clears every bucket from the cluster, so rate-limit experiments start from full buckets.
#
# FLUSHALL only clears the node it is sent to, and bucket keys are spread across all three
# nodes by hash slot, so each node has to be flushed individually.
#

set -euo pipefail

CONTAINER_PREFIX="valkey-cluster"
PORTS=(7001 7002 7003)

for port in "${PORTS[@]}"; do
  docker exec "${CONTAINER_PREFIX}-${PORTS[0]}" valkey-cli -p "${port}" flushall
done

echo "All buckets cleared."
