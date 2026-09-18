#!/usr/bin/env bash
#
# Starts a 3-node Valkey cluster using Docker containers, reachable from the host as
# 127.0.0.1:7001-7003.
#
# The three nodes SHARE ONE NETWORK NAMESPACE: the first container owns it and publishes every
# port, the other two join it with `--net container:<first>`.  That is what makes the cluster
# usable from the host.  A cluster-aware client (Lettuce, valkey-cli -c, ...) asks a seed node
# for the topology and then connects to whatever addresses that topology contains, so those
# addresses have to be reachable from the host — container IPs like 172.18.0.2 are not.  Telling
# each node to announce 127.0.0.1 fixes the client side, but announced addresses are also what
# the nodes gossip to each other, so with one namespace per container every node would look for
# its peers on its own loopback and the cluster would never form.  Sharing a namespace makes
# 127.0.0.1:7001-7003 mean the same thing inside the containers and on the host.
#

set -euo pipefail

IMAGE="valkey/valkey"
IMAGE_VERSION="7.2.6"

NETWORK_NAME="valkey-cluster-net"
CONTAINER_PREFIX="valkey-cluster"

PORTS=(7001 7002 7003)
BUS_PORT_OFFSET=10000

# RedisInsight joins this namespace (see start-insights.sh) so that the 127.0.0.1 addresses in the
# announced topology resolve for it too; a container joining a namespace cannot publish ports, so its
# UI port has to be published here.
EXTRA_PUBLISHED_PORTS=(5540)

# The container that owns the shared network namespace and all published ports.
PRIMARY_CONTAINER="${CONTAINER_PREFIX}-${PORTS[0]}"

# ---------------------------------------------------------------------------
# Clean up any previous run -- by name prefix, so nodes left over from an
# earlier version of this script (e.g. a 4th node) are removed too
# ---------------------------------------------------------------------------
LEFTOVERS=$(docker ps -aq --filter "name=^${CONTAINER_PREFIX}-" || true)
if [[ -n "${LEFTOVERS}" ]]; then
  docker rm --force ${LEFTOVERS} >/dev/null
fi
docker network rm "${NETWORK_NAME}" >/dev/null 2>&1 || true

# ---------------------------------------------------------------------------
# Create a dedicated bridge network for the namespace-owning container
# ---------------------------------------------------------------------------
docker network create "${NETWORK_NAME}" >/dev/null

# ---------------------------------------------------------------------------
# Start the nodes in cluster mode
# ---------------------------------------------------------------------------
for port in "${PORTS[@]}"; do
  container="${CONTAINER_PREFIX}-${port}"
  bus_port=$((port + BUS_PORT_OFFSET))

  # The first node owns the network namespace and publishes every node's port and bus port;
  # ports cannot be published by the containers that join an existing namespace.
  if [[ "${container}" == "${PRIMARY_CONTAINER}" ]]; then
    network_args=(--net "${NETWORK_NAME}")
    for p in "${PORTS[@]}"; do
      network_args+=(--publish "${p}:${p}" --publish "$((p + BUS_PORT_OFFSET)):$((p + BUS_PORT_OFFSET))")
    done
    for p in "${EXTRA_PUBLISHED_PORTS[@]}"; do
      network_args+=(--publish "${p}:${p}")
    done
  else
    network_args=(--net "container:${PRIMARY_CONTAINER}")
  fi

  docker run \
    --name "${container}" \
    "${network_args[@]}" \
    --detach \
    "${IMAGE}:${IMAGE_VERSION}" \
    valkey-server \
      --port "${port}" \
      --cluster-enabled yes \
      --cluster-config-file nodes.conf \
      --cluster-node-timeout 5000 \
      --cluster-announce-ip 127.0.0.1 \
      --cluster-announce-port "${port}" \
      --cluster-announce-bus-port "${bus_port}" \
      --appendonly yes \
      --bind 0.0.0.0 \
      --protected-mode no \
      --maxmemory-policy noeviction \
      --loglevel warning >/dev/null

  echo "Started ${container} (port ${port}, bus ${bus_port})"
done

# ---------------------------------------------------------------------------
# Wait for nodes to be ready
# ---------------------------------------------------------------------------
echo "Waiting for nodes to accept connections..."
for port in "${PORTS[@]}"; do
  for _ in $(seq 1 30); do
    if docker exec "${PRIMARY_CONTAINER}" valkey-cli -p "${port}" ping 2>/dev/null | grep -q PONG; then
      break
    fi
    sleep 0.5
  done
done

# ---------------------------------------------------------------------------
# Form the cluster.  Every node shares one loopback, so 127.0.0.1 addresses
# work here and are what clients on the host will be handed later.
# ---------------------------------------------------------------------------
NODE_ADDRS=""
for port in "${PORTS[@]}"; do
  NODE_ADDRS="${NODE_ADDRS} 127.0.0.1:${port}"
done

echo "Creating cluster with nodes:${NODE_ADDRS}"
docker exec "${PRIMARY_CONTAINER}" \
  valkey-cli --cluster create ${NODE_ADDRS} --cluster-yes

# ---------------------------------------------------------------------------
# Verify the topology the nodes hand out is host-reachable
# ---------------------------------------------------------------------------
echo ""
echo "Topology advertised to clients:"
docker exec "${PRIMARY_CONTAINER}" valkey-cli -p "${PORTS[0]}" cluster nodes |
  awk '{print "  " $2 "  " $3}'

echo ""
echo "Cluster is ready.  Seed URIs for a cluster-aware client:"
for port in "${PORTS[@]}"; do
  echo "  redis://127.0.0.1:${port}"
done
echo ""
echo "Verify from the host:"
echo "  valkey-cli -c -p ${PORTS[0]} cluster info"
echo "  valkey-cli -c -p ${PORTS[0]} cluster nodes"
