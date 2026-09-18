package com.redshiftsoft.example.webapp.limit;

import io.github.bucket4j.Bucket;
import io.github.bucket4j.distributed.ExpirationAfterWriteStrategy;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.bucket4j.redis.lettuce.Bucket4jLettuce;
import io.lettuce.core.RedisURI;
import io.lettuce.core.cluster.ClusterClientOptions;
import io.lettuce.core.cluster.ClusterTopologyRefreshOptions;
import io.lettuce.core.cluster.RedisClusterClient;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * Keeps buckets in a Valkey cluster, reached with Lettuce, so limits are shared by every app server talking
 * to the same cluster. Valkey is wire-compatible with Redis, so the Redis client and the {@code redis://}
 * URI scheme are correct here.
 *
 * <p>Every {@code tryConsume} is a network round trip. {@link ThrottlingFilter} keeps concurrency permits in
 * a bucket too, which costs a second round trip to hand the permit back when the request finishes.
 *
 * <p>Create one per application and {@link #close()} it on shutdown. See {@code docker/valkey-cluster.md}
 * for a local cluster.
 */
public class ValkeyBucketRegistry implements BucketRegistry, AutoCloseable {

    /** Keys are dropped once a bucket has sat untouched long enough to have refilled to capacity. */
    private static final Duration DEFAULT_IDLE_EXPIRATION = Duration.ofMinutes(10);

    /** The ports the cluster from {@code docker/start-valkey-cluster.sh} listens on. */
    private static final List<Integer> DEFAULT_PORTS = List.of(7001, 7002, 7003);

    /** Namespace for this application's keys, so they are easy to pick out in {@code valkey-cli}. */
    public static final String DEFAULT_KEY_PREFIX = "bucket4j";

    private final RedisClusterClient client;
    private final ProxyManager<byte[]> proxyManager;
    private final String keyPrefix;

    /**
     * The cluster to use when none is named: {@link #DEFAULT_PORTS} on {@code VALKEY_HOST}, or on loopback
     * when that variable is unset. CI sets it to the hostname of its Valkey service container; the local
     * cluster from {@code docker/valkey-cluster.md} runs on loopback.
     */
    public static List<String> defaultSeedUris() {
        String host = System.getenv().getOrDefault("VALKEY_HOST", "127.0.0.1");
        return DEFAULT_PORTS.stream().map(port -> "redis://" + host + ":" + port).toList();
    }

    /** Connects to {@link #defaultSeedUris()} under {@link #DEFAULT_KEY_PREFIX}. */
    public static ValkeyBucketRegistry connectDefault() {
        return connect(defaultSeedUris(), DEFAULT_KEY_PREFIX);
    }

    /**
     * @param seedUris  any subset of the cluster's nodes, e.g. {@code redis://127.0.0.1:7001}; the rest are
     *                  discovered from the cluster topology
     * @param keyPrefix namespace for this application's keys within the cluster
     */
    public static ValkeyBucketRegistry connect(List<String> seedUris, String keyPrefix) {
        return connect(seedUris, keyPrefix, DEFAULT_IDLE_EXPIRATION);
    }

    public static ValkeyBucketRegistry connect(List<String> seedUris, String keyPrefix, Duration idleExpiration) {
        if (seedUris.isEmpty()) {
            throw new IllegalArgumentException("at least one seed URI is required");
        }
        RedisClusterClient client = RedisClusterClient.create(seedUris.stream().map(RedisURI::create).toList());
        // Without topology refresh the client keeps using the node list it saw at startup, and stops finding
        // slots that have moved after a failover or a resharding.
        client.setOptions(ClusterClientOptions.builder()
                .topologyRefreshOptions(ClusterTopologyRefreshOptions.builder()
                        .enablePeriodicRefresh(Duration.ofSeconds(30))
                        .enableAllAdaptiveRefreshTriggers()
                        .build())
                .build());
        return new ValkeyBucketRegistry(client, keyPrefix, idleExpiration);
    }

    private ValkeyBucketRegistry(RedisClusterClient client, String keyPrefix, Duration idleExpiration) {
        this.client = client;
        this.keyPrefix = Objects.requireNonNull(keyPrefix, "keyPrefix is required");
        this.proxyManager = Bucket4jLettuce.casBasedBuilder(client)
                .expirationAfterWrite(ExpirationAfterWriteStrategy.basedOnTimeForRefillingBucketUpToMax(idleExpiration))
                .build();
    }

    @Override
    public Bucket bucket(String key, BucketConfig config) {
        // Cheap: the proxy holds no connection state of its own, it just wraps the key and configuration.
        byte[] redisKey = (keyPrefix + ":" + key).getBytes(StandardCharsets.UTF_8);
        return proxyManager.getProxy(redisKey, config::toBucketConfiguration);
    }

    @Override
    public void close() {
        client.shutdown();
    }

}
