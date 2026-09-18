package com.redshiftsoft.example.webapp.limit;

import com.redshiftsoft.example.webapp.TestUtils;
import com.redshiftsoft.example.webapp.ValkeyTestCluster;
import com.redshiftsoft.example.webapp.servlets.HelloServlet;
import com.redshiftsoft.example.webapp.servlets.LoginServlet;
import jakarta.servlet.DispatcherType;
import org.eclipse.jetty.ee10.servlet.FilterHolder;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.SessionHandler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Limits shared between two app servers, which is the whole point of putting buckets in the cluster.
 *
 * <p>Every test here runs against Valkey — that is now the only place buckets live. What this class adds is a
 * <em>second</em> server: two Jetty instances, each with its own {@link ThrottlingFilter} and its own
 * {@link ValkeyBucketRegistry}, standing in for two app servers behind a load balancer.
 */
public class RateLimiting_Valkey_Test {

    static final long CAPACITY = 3;

    private static final BucketConfig USER_CONFIG = new BucketConfig(CAPACITY, 1, Duration.ofMinutes(1));

    /**
     * Roomy on purpose: every test user here belongs to organization 6, so a tight per-organization bucket
     * would be shared between the tests and whichever ran first would starve the other.
     */
    private static final BucketConfig ORG_CONFIG = new BucketConfig(1_000, 1, Duration.ofMinutes(1));

    private static final List<Server> servers = new ArrayList<>();
    private static final List<ValkeyBucketRegistry> registries = new ArrayList<>();
    private static final List<Integer> ports = new ArrayList<>();

    @BeforeAll
    public static void startServers() throws Exception {
        ValkeyTestCluster.assumeAvailable();
        ValkeyTestCluster.flush();

        // Two registries on the same cluster and the same key prefix: separate clients, shared buckets.
        for (int i = 0; i < 2; i++) {
            ValkeyBucketRegistry registry = ValkeyBucketRegistry.connectDefault();
            registries.add(registry);
            startServer(registry);
        }
    }

    private static void startServer(BucketRegistry registry) throws Exception {
        List<Endpoint> endpoints = List.of(
                Endpoint.builder()
                        .path("/hello")
                        .policy(RateLimitPolicy.builder().userBucket(USER_CONFIG).orgBucket(ORG_CONFIG).build())
                        .build(),
                // catch-all default, must come last
                Endpoint.builder().path("/").prefix(true).policy(RateLimitPolicies.DEFAULT_POLICY).build()
        );

        Server server = new Server(0);
        ServletContextHandler context = new ServletContextHandler("/");
        context.setSessionHandler(new SessionHandler());
        context.addFilter(new FilterHolder(new ThrottlingFilter(endpoints, registry)), "/*", EnumSet.of(DispatcherType.REQUEST));
        context.addServlet(HelloServlet.class, "/hello");
        context.addServlet(LoginServlet.class, "/login");
        server.setHandler(context);

        server.start();
        servers.add(server);
        ports.add(((ServerConnector) server.getConnectors()[0]).getLocalPort());
    }

    @AfterAll
    public static void stopServers() throws Exception {
        for (Server server : servers) {
            server.stop();
        }
        servers.clear();
        ports.clear();
        for (ValkeyBucketRegistry registry : registries) {
            registry.close();
        }
        registries.clear();
    }

    @Test
    public void limitIsSharedAcrossServers() throws Exception {
        // The same user, with a separate session on each server — the bucket key is the same either way.
        HttpClient clientOnA = TestUtils.newSessionClient();
        TestUtils.login(clientOnA, ports.get(0), "test-user-60", "abcd1234");
        HttpClient clientOnB = TestUtils.newSessionClient();
        TestUtils.login(clientOnB, ports.get(1), "test-user-60", "abcd1234");

        // Spend the whole bucket on server A.
        for (int i = 0; i < CAPACITY; i++) {
            assertEquals(200, hello(clientOnA, ports.get(0)).statusCode(), "request " + i + " to server A");
        }

        // Server B never served a request, but its buckets live in the same cluster.
        HttpResponse<String> onB = hello(clientOnB, ports.get(1));

        assertEquals(429, onB.statusCode(), "server B should see the limit spent on server A");
        assertTrue(onB.body().contains("Too many requests for user test-user-60"), "unexpected body: " + onB.body());
    }

    @Test
    public void limitsAreSpentAcrossBothServersTogether() throws Exception {
        HttpClient clientOnA = TestUtils.newSessionClient();
        TestUtils.login(clientOnA, ports.get(0), "test-user-61", "abcd1234");
        HttpClient clientOnB = TestUtils.newSessionClient();
        TestUtils.login(clientOnB, ports.get(1), "test-user-61", "abcd1234");

        // Alternate servers; the shared bucket should still allow exactly CAPACITY requests in total.
        int successCount = 0;
        for (int i = 0; i < CAPACITY + 3; i++) {
            boolean useA = (i % 2 == 0);
            HttpResponse<String> response = useA
                    ? hello(clientOnA, ports.get(0))
                    : hello(clientOnB, ports.get(1));
            if (response.statusCode() == 200) {
                successCount++;
            }
        }

        assertEquals(CAPACITY, successCount, "the two servers should share one quota, not one each");
    }

    private static HttpResponse<String> hello(HttpClient client, int port) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/hello"))
                .GET()
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

}
