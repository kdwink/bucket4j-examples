package com.redshiftsoft.example.webapp.limit;

import com.redshiftsoft.example.webapp.TestUtils;
import com.redshiftsoft.example.webapp.ValkeyTestCluster;
import com.redshiftsoft.example.webapp.servlets.HelloServlet;
import com.redshiftsoft.example.webapp.servlets.LoginServlet;
import com.redshiftsoft.example.webapp.servlets.SlowServlet;
import jakarta.servlet.DispatcherType;
import org.eclipse.jetty.ee10.servlet.FilterHolder;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.SessionHandler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.EnumSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A RECORD policy evaluates its limits and logs breaches, but serves every request. slf4j-simple writes to
 * System.err, so the tests capture that stream to see what was logged.
 */
public class RateLimiting_Record_Test {

    static final long CAPACITY = 2;
    static final int REQUEST_COUNT = 6;
    static final int MAX_CONCURRENT = 1;

    private static final BucketConfig TINY_CONFIG = new BucketConfig(CAPACITY, 1, Duration.ofMinutes(1));

    private static Server server;
    protected static int port;

    @BeforeAll
    public static void startServer() throws Exception {
        ValkeyTestCluster.assumeAvailable();
        ValkeyTestCluster.flush();

        List<Endpoint> endpoints = List.of(
                Endpoint.builder()
                        .path("/hello")
                        .policy(RateLimitPolicy.builder()
                                .userBucket(TINY_CONFIG)
                                .orgBucket(TINY_CONFIG)
                                .mode(EnforcementMode.RECORD)
                                .build())
                        .build(),
                Endpoint.builder()
                        .path("/slow")
                        .prefix(true)
                        .policy(RateLimitPolicy.builder()
                                .userBucket(TINY_CONFIG)
                                .orgBucket(TINY_CONFIG)
                                .maxConcurrentRequests(MAX_CONCURRENT)
                                .mode(EnforcementMode.RECORD)
                                .build())
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
        context.addServlet(SlowServlet.class, "/slow/*");
        server.setHandler(context);

        server.start();
        port = ((ServerConnector) server.getConnectors()[0]).getLocalPort();
    }

    @AfterAll
    public static void stopServer() throws Exception {
        if (server != null) {
            server.stop();
        }
    }

    @Test
    public void exceededLimitsAreLoggedButRequestsStillSucceed() throws Exception {
        HttpClient client = TestUtils.newSessionClient();
        TestUtils.login(client, port, "test-user-50", "abcd1234");

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/hello"))
                .GET()
                .build();

        int successCount = 0;
        String logged;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        PrintStream originalErr = System.err;
        System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
        try {
            for (int i = 0; i < REQUEST_COUNT; i++) {
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    successCount++;
                }
            }
        } finally {
            System.setErr(originalErr);
            logged = captured.toString(StandardCharsets.UTF_8);
        }

        assertEquals(REQUEST_COUNT, successCount, "RECORD mode should not block any request");
        assertTrue(REQUEST_COUNT > CAPACITY, "the request count must exceed the capacity for this test to mean anything");
        assertTrue(logged.contains("Rate limit exceeded on endpoint [ANY /hello]"), "nothing logged: " + logged);
        assertTrue(logged.contains("Too many requests for user test-user-50"), "unexpected log output: " + logged);
        assertTrue(logged.contains("request allowed"), "unexpected log output: " + logged);
    }

    @Test
    public void exceededConcurrencyLimitIsLoggedButRequestsStillSucceed() throws Exception {
        // Hold the single permit with a slow request, then fire a second one while it is in flight.
        HttpClient holdingClient = TestUtils.newSessionClient();
        TestUtils.login(holdingClient, port, "test-user-51", "abcd1234");
        HttpClient secondClient = TestUtils.newSessionClient();
        TestUtils.login(secondClient, port, "test-user-52", "abcd1234");

        HttpRequest slowRequest = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/slow/1000"))
                .GET()
                .build();
        HttpRequest quickRequest = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/slow/0"))
                .GET()
                .build();

        String logged;
        HttpResponse<String> secondResponse;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        PrintStream originalErr = System.err;
        System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
        try {
            var inFlight = holdingClient.sendAsync(slowRequest, HttpResponse.BodyHandlers.ofString());
            Thread.sleep(200); // let the slow request reach the filter and take the only permit
            secondResponse = secondClient.send(quickRequest, HttpResponse.BodyHandlers.ofString());
            inFlight.get();
        } finally {
            System.setErr(originalErr);
            logged = captured.toString(StandardCharsets.UTF_8);
        }

        assertEquals(200, secondResponse.statusCode(), "RECORD mode should not block on the concurrency limit");
        assertTrue(logged.contains("Too many concurrent requests"), "unexpected log output: " + logged);
    }

}
