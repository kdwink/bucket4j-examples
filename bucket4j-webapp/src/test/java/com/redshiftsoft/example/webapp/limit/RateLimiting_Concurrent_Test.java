package com.redshiftsoft.example.webapp.limit;

import com.redshiftsoft.example.webapp.TestUtils;
import com.redshiftsoft.example.webapp.ValkeyTestCluster;
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

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@link RateLimitPolicy#maxConcurrentRequests()} against {@code GET /slow/{timeMs}}, which holds a
 * request open for as long as we ask it to.
 */
public class RateLimiting_Concurrent_Test {

    /** Concurrency permits are keyed per organization, so all clients in a burst log in to the same org. */
    static final int MAX_CONCURRENT = RateLimitPolicies.SLOW_POLICY.maxConcurrentRequests();
    static final int EXTRA_REQUESTS = 3;
    static final long DELAY_MS = 1_000;
    static final int USERS_PER_ORG = 10;

    private static Server server;
    protected static int port;

    @BeforeAll
    public static void startServer() throws Exception {
        ValkeyTestCluster.assumeAvailable();
        ValkeyTestCluster.flush();

        List<Endpoint> endpoints = List.of(
                Endpoint.builder().path("/slow").prefix(true).policy(RateLimitPolicies.SLOW_POLICY).build(),
                // catch-all default, must come last
                Endpoint.builder().path("/").prefix(true).policy(RateLimitPolicies.DEFAULT_POLICY).build()
        );

        server = new Server(0);
        ServletContextHandler context = new ServletContextHandler("/");
        context.setSessionHandler(new SessionHandler());
        context.addFilter(new FilterHolder(new ThrottlingFilter(endpoints)), "/*", EnumSet.of(DispatcherType.REQUEST));
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
    public void concurrentRequestsBeyondTheLimitAreRejected() throws Exception {
        int requestCount = MAX_CONCURRENT + EXTRA_REQUESTS;

        List<HttpResponse<String>> responses = burst(requestCount, 2, DELAY_MS);

        long okCount = responses.stream().filter(r -> r.statusCode() == 200).count();
        List<HttpResponse<String>> rejected = responses.stream().filter(r -> r.statusCode() == 429).toList();

        assertEquals(MAX_CONCURRENT, okCount, "only maxConcurrentRequests requests should be let through");
        assertEquals(EXTRA_REQUESTS, rejected.size(), "every request beyond the limit should be rejected");
        for (HttpResponse<String> response : rejected) {
            assertTrue(response.body().contains("Too many concurrent requests"), "unexpected body: " + response.body());
        }
    }

    @Test
    public void permitsAreReleasedWhenRequestsComplete() throws Exception {
        // Fill every permit, then wait for the burst to finish.
        List<HttpResponse<String>> firstBurst = burst(MAX_CONCURRENT, 3, DELAY_MS);
        assertEquals(MAX_CONCURRENT, firstBurst.stream().filter(r -> r.statusCode() == 200).count());

        // The permits should be back, so a second burst of the same size is let through as well.
        List<HttpResponse<String>> secondBurst = burst(MAX_CONCURRENT, 3, DELAY_MS);
        assertEquals(MAX_CONCURRENT, secondBurst.stream().filter(r -> r.statusCode() == 200).count(),
                "permits should be released once the in-flight requests complete");
    }

    /**
     * Logs in {@code requestCount} sessions as users of the given organization, then releases one
     * {@code GET /slow/{delayMs}} per session at the same moment and waits for all of them.
     */
    private static List<HttpResponse<String>> burst(int requestCount, int organizationId, long delayMs) throws Exception {
        List<HttpClient> clients = new ArrayList<>();
        for (int i = 0; i < requestCount; i++) {
            HttpClient client = TestUtils.newSessionClient();
            // Each organization owns 10 users — test-user-20 .. test-user-29 belong to organization 2 — so
            // bursts larger than 10 open a second session for a user. Permits are per organization either way.
            String username = String.format("test-user-%02d", organizationId * 10 + (i % USERS_PER_ORG));
            TestUtils.login(client, port, username, "abcd1234");
            clients.add(client);
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/slow/" + delayMs))
                .GET()
                .build();

        CountDownLatch startGate = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(requestCount);
        try {
            List<Future<HttpResponse<String>>> futures = new ArrayList<>();
            for (HttpClient client : clients) {
                futures.add(pool.submit(() -> {
                    startGate.await();
                    return client.send(request, HttpResponse.BodyHandlers.ofString());
                }));
            }

            startGate.countDown();

            List<HttpResponse<String>> responses = new ArrayList<>();
            for (Future<HttpResponse<String>> future : futures) {
                responses.add(future.get());
            }
            return responses;
        } finally {
            pool.shutdown();
        }
    }

}
