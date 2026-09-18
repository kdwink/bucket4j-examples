package com.redshiftsoft.example.webapp;

import com.redshiftsoft.example.webapp.limit.ValkeyBucketRegistry;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.cluster.RedisClusterClient;
import io.lettuce.core.cluster.api.StatefulRedisClusterConnection;

import java.io.IOException;
import java.net.Socket;
import java.net.URI;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The Valkey cluster every test that starts a server needs, since all buckets now live in it.
 *
 * <p>Call {@link #assumeAvailable()} then {@link #flush()} at the top of {@code @BeforeAll}: the first skips
 * the class when no cluster is around, the second empties the cluster so the class starts from full buckets.
 *
 * <p>Flushing rather than namespacing keys means test classes must not run in parallel — they would clear
 * each other's buckets mid-run. Surefire runs them one at a time by default.
 *
 * <p>See {@code docker/valkey-cluster.md} for the local cluster.
 */
public final class ValkeyTestCluster {

    /** How long to give a cluster that is listening but has not finished forming — CI starts it as we boot. */
    private static final Duration READY_TIMEOUT = Duration.ofSeconds(60);

    private ValkeyTestCluster() {
    }

    /**
     * Skips the calling test when no cluster is listening, so {@code mvn test} still passes without Docker.
     * Setting {@code VALKEY_HOST} — as CI does, pointing at its Valkey service container — declares that a
     * cluster is expected, and then an unreachable cluster fails the test instead of skipping it.
     */
    public static void assumeAvailable() throws InterruptedException {
        if (System.getenv("VALKEY_HOST") == null) {
            assumeTrue(isListening(), "no Valkey cluster on " + seedUri()
                    + " — run docker/start-valkey-cluster.sh");
        }
        awaitReady();
    }

    /**
     * Clears every bucket left behind by an earlier test class or an earlier run. Lettuce sends
     * {@code FLUSHALL} to every upstream node, which is what this needs: bucket keys are spread over all
     * three nodes by hash slot, and a flush of one node would leave the other two untouched.
     */
    public static void flush() {
        RedisClusterClient client = RedisClusterClient.create(uris());
        try (StatefulRedisClusterConnection<String, String> connection = client.connect()) {
            connection.sync().flushall();
        } finally {
            client.shutdown();
        }
    }

    private static List<RedisURI> uris() {
        return ValkeyBucketRegistry.defaultSeedUris().stream().map(RedisURI::create).toList();
    }

    private static String seedUri() {
        return ValkeyBucketRegistry.defaultSeedUris().get(0);
    }

    private static boolean isListening() {
        URI uri = URI.create(seedUri());
        try (Socket socket = new Socket(uri.getHost(), uri.getPort())) {
            return socket.isConnected();
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Waits for the seed node to report {@code cluster_state:ok}. A node accepts connections before the
     * cluster has slots assigned, and commands against it fail until it does; CI starts the cluster and this
     * build at the same time, so we can easily arrive first.
     */
    private static void awaitReady() throws InterruptedException {
        RedisClient client = RedisClient.create(seedUri());
        try {
            long deadline = System.nanoTime() + READY_TIMEOUT.toNanos();
            String lastState = "no response";
            while (System.nanoTime() < deadline) {
                try (StatefulRedisConnection<String, String> connection = client.connect()) {
                    lastState = connection.sync().clusterInfo();
                    if (lastState.contains("cluster_state:ok")) {
                        return;
                    }
                } catch (RuntimeException e) {
                    lastState = e.toString();
                }
                Thread.sleep(500);
            }
            throw new IllegalStateException("Valkey cluster at " + seedUri() + " was not ready within "
                    + READY_TIMEOUT + "; last state: " + lastState);
        } finally {
            client.shutdown();
        }
    }

}
