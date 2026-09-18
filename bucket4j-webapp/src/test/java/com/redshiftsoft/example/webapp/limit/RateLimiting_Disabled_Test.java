package com.redshiftsoft.example.webapp.limit;

import com.redshiftsoft.example.webapp.TestUtils;
import com.redshiftsoft.example.webapp.ValkeyTestCluster;
import com.redshiftsoft.example.webapp.servlets.HelloServlet;
import com.redshiftsoft.example.webapp.servlets.LoginServlet;
import com.redshiftsoft.example.webapp.servlets.SlowServlet;
import com.redshiftsoft.example.webapp.servlets.UsersServlet;
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
import java.util.EnumSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Two endpoints share the same tiny bucket config; only the mode differs. /hello is DISABLED and /users is
 * BLOCK, so the same traffic is throttled on one and untouched on the other.
 */
public class RateLimiting_Disabled_Test {

    static final long CAPACITY = 2;
    static final int REQUEST_COUNT = 6;

    private static final BucketConfig TINY_CONFIG = new BucketConfig(CAPACITY, 1, Duration.ofMinutes(1));

    private static Server server;
    protected static int port;

    @BeforeAll
    public static void startServer() throws Exception {
        ValkeyTestCluster.assumeAvailable();
        ValkeyTestCluster.flush();

        List<Endpoint> endpoints = List.of(
                Endpoint.builder().path("/hello").policy(tinyPolicy(EnforcementMode.DISABLED)).build(),
                Endpoint.builder().path("/users").prefix(true).policy(tinyPolicy(EnforcementMode.BLOCK)).build(),
                // blocking policy, but this endpoint overrides it to DISABLED
                Endpoint.builder()
                        .path("/slow")
                        .prefix(true)
                        .policy(tinyPolicy(EnforcementMode.BLOCK))
                        .mode(EnforcementMode.DISABLED)
                        .build(),
                // catch-all default, must come last
                Endpoint.builder().path("/").prefix(true).policy(RateLimitPolicies.DEFAULT_POLICY).build()
        );

        server = new Server(0);
        ServletContextHandler context = new ServletContextHandler("/");
        context.setSessionHandler(new SessionHandler());
        context.addFilter(new FilterHolder(new ThrottlingFilter(endpoints)), "/*", EnumSet.of(DispatcherType.REQUEST));
        context.addServlet(HelloServlet.class, "/hello");
        context.addServlet(LoginServlet.class, "/login");
        context.addServlet(UsersServlet.class, "/users/*");
        context.addServlet(SlowServlet.class, "/slow/*");
        server.setHandler(context);

        server.start();
        port = ((ServerConnector) server.getConnectors()[0]).getLocalPort();
    }

    private static RateLimitPolicy tinyPolicy(EnforcementMode mode) {
        return RateLimitPolicy.builder()
                .userBucket(TINY_CONFIG)
                .orgBucket(TINY_CONFIG)
                .mode(mode)
                .build();
    }

    @AfterAll
    public static void stopServer() throws Exception {
        if (server != null) {
            server.stop();
        }
    }

    @Test
    public void disabledPolicyIsNotEnforced() throws Exception {
        HttpClient client = TestUtils.newSessionClient();
        TestUtils.login(client, port, "test-user-40", "abcd1234");

        int successCount = countSuccesses(client, "/hello");

        assertEquals(REQUEST_COUNT, successCount, "a DISABLED policy should not throttle anything");
    }

    @SuppressWarnings("ConstantValue")
    @Test
    public void blockingPolicyWithTheSameConfigIsEnforced() throws Exception {
        HttpClient client = TestUtils.newSessionClient();
        TestUtils.login(client, port, "test-user-41", "abcd1234");

        int successCount = countSuccesses(client, "/users");

        assertEquals(CAPACITY, successCount, "the same config in BLOCK mode should throttle at CAPACITY");
        assertTrue(REQUEST_COUNT > CAPACITY, "the request count must exceed the capacity for this test to mean anything");
    }

    @Test
    public void endpointModeOverridesABlockingPolicy() throws Exception {
        HttpClient client = TestUtils.newSessionClient();
        TestUtils.login(client, port, "test-user-42", "abcd1234");

        int successCount = countSuccesses(client, "/slow/0");

        assertEquals(REQUEST_COUNT, successCount, "the endpoint's DISABLED mode should override the policy's BLOCK");
    }

    private static int countSuccesses(HttpClient client, String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .GET()
                .build();

        int successCount = 0;
        for (int i = 0; i < REQUEST_COUNT; i++) {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                successCount++;
            }
        }
        return successCount;
    }

}
