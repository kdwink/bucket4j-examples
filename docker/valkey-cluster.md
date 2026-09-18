# Valkey Cluster (3 nodes)

Starts a 3-node Valkey cluster reachable from the host as `127.0.0.1:7001`, `:7002`, `:7003` —
the addresses a cluster-aware client such as Lettuce can actually connect to.

## Start

```bash
./start-valkey-cluster.sh
```

## Stop

```bash
./stop-valkey-cluster.sh
```

## Clear all buckets

```bash
./flush-valkey-cluster.sh
```

`FLUSHALL` only clears the node it is sent to, and bucket keys are spread across all three nodes
by hash slot, so this sends it to each node in turn. Handy between rate-limit experiments.

## CLI

Connect in cluster mode (the `-c` flag follows redirects across nodes):

```bash
valkey-cli -c -p 7001
```

Cluster info:

```bash
valkey-cli -c -p 7001 cluster info
valkey-cli -c -p 7001 cluster nodes
```

## Connecting from Java (Lettuce)

Any of the three ports works as a seed; the client discovers the rest from the topology.

```java
List<RedisURI> seeds = List.of(
        RedisURI.create("redis://127.0.0.1:7001"),
        RedisURI.create("redis://127.0.0.1:7002"),
        RedisURI.create("redis://127.0.0.1:7003"));

RedisClusterClient client = RedisClusterClient.create(seeds);
client.setOptions(ClusterClientOptions.builder()
        .topologyRefreshOptions(ClusterTopologyRefreshOptions.builder()
                .enablePeriodicRefresh(Duration.ofSeconds(30))
                .enableAllAdaptiveRefreshTriggers()
                .build())
        .build());

ProxyManager<byte[]> proxyManager = Bucket4jLettuce.casBasedBuilder(client)
        .expirationAfterWrite(ExpirationAfterWriteStrategy
                .basedOnTimeForRefillingBucketUpToMax(Duration.ofMinutes(5)))
        .build();
```

The URI scheme is `redis://` even against Valkey — Valkey 7.2 is wire-compatible with Redis 7.2.
Set an expiration strategy or idle per-user bucket keys accumulate forever.

`ValkeyBucketRegistry` in `bucket4j-webapp` does exactly the above; `ValkeyBucketRegistry.connectDefault()`
points at these three ports on `VALKEY_HOST` (default `127.0.0.1`) under the `bucket4j:` key prefix, so
application code and tests agree on where the cluster is.

## Clearing buckets from Java

`flush-valkey-cluster.sh` above flushes each node in turn because `FLUSHALL` only clears the node it is sent
to. From Lettuce, `flushall()` on a cluster connection does that fan-out for you — it runs against every
upstream node — which is how `ValkeyTestCluster.flush()` gives each test class empty buckets:

```java
try (StatefulRedisClusterConnection<String, String> connection = client.connect()) {
    connection.sync().flushall();
}
```

## Why the nodes share one network namespace

A cluster-aware client asks a seed node for the cluster topology and then connects to whatever
addresses that topology contains. By default a node advertises its container IP (`172.18.0.2`
and friends), which is not routable from the host on macOS — so the client connects to the seed,
learns three unreachable addresses, and fails.

`--cluster-announce-ip 127.0.0.1` (plus `--cluster-announce-port` / `--cluster-announce-bus-port`)
fixes what clients are told, but announced addresses are also what the nodes gossip to each
other. With one network namespace per container, every node would then look for its peers on its
own loopback, find nothing, and the cluster would never form.

So the first container owns the network namespace and publishes all six ports; the other two
join it with `--net container:valkey-cluster-7001`. One loopback, shared by all three nodes,
where `127.0.0.1:7001-7003` means the same thing inside the containers and on the host.

Consequences worth knowing:

* Ports are published only by `valkey-cluster-7001`, because a container joining an existing
  namespace cannot publish ports. Stop that container and the other two lose their networking —
  which is why `stop-valkey-cluster.sh` removes them in reverse order.
* The containers do not use `--restart unless-stopped`. After a Docker restart the namespace
  owner may come up after its dependents, which cannot then start. Re-run the start script.
* Any *other* container that needs to talk to the cluster has to join the same namespace, for the
  same reason — see RedisInsight below.

## Browsing buckets with RedisInsight

```bash
docker/start-valkey-cluster.sh
docker/start-insights.sh          # requires the cluster to already be running
```

Then open <http://localhost:5540> and add the database as **host `127.0.0.1`, port `7001`**.

RedisInsight joins `valkey-cluster-7001`'s network namespace, so `127.0.0.1:7001-7003` means the same
thing to it as it does to the nodes. Giving it a namespace of its own does not work: `127.0.0.1` would
be RedisInsight itself, and while `host.docker.internal:7001` does reach the seed node, the topology and
every `MOVED` redirect point back at `127.0.0.1`, so only keys in the seed's own slots (0-5460) are
reachable. Because a container joining a namespace cannot publish ports, 5540 is published by the
namespace owner via `EXTRA_PUBLISHED_PORTS` in `start-valkey-cluster.sh`.

Restarting the cluster orphans RedisInsight's networking, so re-run `start-insights.sh` afterwards.

## In CI

`.gitlab-ci.yml` starts the same kind of cluster as a GitLab `services:` entry: one `valkey/valkey` container
running all three nodes, which share that container's loopback so the cluster forms exactly as it does here.
The scripts in this directory are not used — a service container cannot mount the repository — so the node
arguments are inlined in the CI file and must be kept in step with `start-valkey-cluster.sh`.

Differences from local, each of them load-bearing:

* Each node announces the service container's own IP rather than `127.0.0.1`. The job runs in a separate
  container, where `127.0.0.1` is its own loopback; container IPs, unreachable from a macOS host, are routable
  between containers on the runner.
* That IP is read with `getent ahostsv4`, not `hostname -i`. On a dual-stack runner `hostname -i` lists the
  IPv6 address first, and `valkey-cli --cluster create` does not bracket an IPv6 address — it read
  `fd00:dddd::7:7001` as host `fd00:dddd::7:7001`, and the cluster never formed.
* Nodes get per-port `nodes-<port>.conf` files and `--appendonly no`, because one container means one working
  directory and the nodes would otherwise fight over `nodes.conf` and the append-only dir.
* `HEALTHCHECK_TCP_PORT: "7001"` on the service. Before the job starts, the runner waits for a TCP connection
  to the ports the service image exposes — `6379` for this image, where nothing listens — and declares the
  service dead after 30s.
* `FF_NETWORK_PER_BUILD: "true"` on the job. Without it the job and services share the default bridge and the
  runner takes the health check target from Docker's legacy link variables, which are built from the image's
  `EXPOSE` and ignore `HEALTHCHECK_TCP_PORT` entirely.

The job sets `VALKEY_HOST=valkey`, which both points `RateLimiting_Valkey_Test` at the service and tells it
the cluster is required, so a service that failed to start fails the build instead of quietly skipping.

## Notes

* This is a minimal 3-primary cluster with no replicas -- suitable for development and testing,
  not production.
* The 16384 hash slots are divided evenly across the three nodes.
* Ports 17001-17003 are used for the cluster bus (inter-node communication).
* `--maxmemory-policy noeviction` is set deliberately: if Valkey evicted bucket keys under
  memory pressure, rate limits would silently reset.

## Reference

* Valkey cluster tutorial: https://valkey.io/topics/cluster-tutorial/
* bucket4j Redis integrations: https://bucket4j.com/8.19.0/toc.html#redis-integrations
