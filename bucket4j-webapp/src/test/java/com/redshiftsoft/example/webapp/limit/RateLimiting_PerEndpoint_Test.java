package com.redshiftsoft.example.webapp.limit;

import com.redshiftsoft.example.webapp.TestUtils;
import com.redshiftsoft.example.webapp.ValkeyTestCluster;
import com.redshiftsoft.example.webapp.servlets.HelloServlet;
import com.redshiftsoft.example.webapp.servlets.LoginServlet;
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
import java.util.EnumSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class RateLimiting_PerEndpoint_Test {

    static final long LOGIN_USER_CAPACITY = RateLimitPolicies.LOGIN_POLICY.userBucket().capacity();
    static final long HELLO_USER_CAPACITY = RateLimitPolicies.HELLO_POLICY.userBucket().capacity();
    static final long USERS_USER_CAPACITY = RateLimitPolicies.USERS_POLICY.userBucket().capacity();

    private static Server server;
    protected static int port;

    @BeforeAll
    public static void startServer() throws Exception {
        ValkeyTestCluster.assumeAvailable();
        ValkeyTestCluster.flush();

        List<Endpoint> endpoints = List.of(
                Endpoint.builder().method("POST").path("/login").policy(RateLimitPolicies.LOGIN_POLICY).build(),
                Endpoint.builder().path("/hello").policy(RateLimitPolicies.HELLO_POLICY).build(),
                // prefix endpoint: covers /users and every /users/<userId>
                Endpoint.builder().path("/users").prefix(true).policy(RateLimitPolicies.USERS_POLICY).build(),
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
    public void exhaustingLoginLimitDoesNotAffectHello() throws Exception {
        HttpClient client = TestUtils.newSessionClient();
        TestUtils.login(client, port, "test-user-10", "abcd1234");

        URI loginUri = URI.create("http://localhost:" + port + "/login");
        HttpRequest.BodyPublisher loginBody = HttpRequest.BodyPublishers.ofString("username=test-user-10&password=abcd1234");
        for (int i = 0; i < LOGIN_USER_CAPACITY + 5; i++) {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(loginUri)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(loginBody)
                    .build();
            client.send(request, HttpResponse.BodyHandlers.ofString());
        }

        URI helloUri = URI.create("http://localhost:" + port + "/hello");
        int helloSuccessCount = 0;
        for (int i = 0; i < 5; i++) {
            HttpRequest request = HttpRequest.newBuilder().uri(helloUri).GET().build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                helloSuccessCount++;
            }
        }

        assertEquals(5, helloSuccessCount, "/hello should still succeed after /login limit is exhausted");
    }

    @Test
    public void exhaustingHelloLimitDoesNotAffectLogin() throws Exception {
        HttpClient client = TestUtils.newSessionClient();
        TestUtils.login(client, port, "test-user-11", "abcd1234");

        URI helloUri = URI.create("http://localhost:" + port + "/hello");
        for (int i = 0; i < HELLO_USER_CAPACITY + 5; i++) {
            HttpRequest request = HttpRequest.newBuilder().uri(helloUri).GET().build();
            client.send(request, HttpResponse.BodyHandlers.ofString());
        }

        URI loginUri = URI.create("http://localhost:" + port + "/login");
        HttpRequest.BodyPublisher loginBody = HttpRequest.BodyPublishers.ofString("username=test-user-11&password=abcd1234");
        int loginSuccessCount = 0;
        for (int i = 0; i < 5; i++) {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(loginUri)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(loginBody)
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                loginSuccessCount++;
            }
        }

        assertEquals(5, loginSuccessCount, "/login should still succeed after /hello limit is exhausted");
    }

    @Test
    public void eachEndpointReturns429IndependentlyWhenExhausted() throws Exception {
        HttpClient client = TestUtils.newSessionClient();
        TestUtils.login(client, port, "test-user-12", "abcd1234");

        URI loginUri = URI.create("http://localhost:" + port + "/login");
        HttpRequest.BodyPublisher loginBody = HttpRequest.BodyPublishers.ofString("username=test-user-12&password=abcd1234");
        int login429Count = 0;
        for (int i = 0; i < LOGIN_USER_CAPACITY + 5; i++) {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(loginUri)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(loginBody)
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 429 && response.body().contains("Too many requests for user")) {
                login429Count++;
            }
        }

        URI helloUri = URI.create("http://localhost:" + port + "/hello");
        int hello429Count = 0;
        for (int i = 0; i < HELLO_USER_CAPACITY + 5; i++) {
            HttpRequest request = HttpRequest.newBuilder().uri(helloUri).GET().build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 429 && response.body().contains("Too many requests for user")) {
                hello429Count++;
            }
        }

        assertTrue(login429Count > 0, "/login should return 429 when its per-user limit is exceeded");
        assertTrue(hello429Count > 0, "/hello should return 429 when its per-user limit is exceeded");
    }

    @Test
    public void postLoginLimitDoesNotAffectGetLogin() throws Exception {
        HttpClient client = TestUtils.newSessionClient();
        TestUtils.login(client, port, "test-user-13", "abcd1234");

        // Exhaust the POST /login bucket
        URI loginUri = URI.create("http://localhost:" + port + "/login");
        HttpRequest.BodyPublisher loginBody = HttpRequest.BodyPublishers.ofString("username=test-user-13&password=abcd1234");
        int post429Count = 0;
        for (int i = 0; i < LOGIN_USER_CAPACITY + 5; i++) {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(loginUri)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(loginBody)
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 429) {
                post429Count++;
            }
        }
        assertTrue(post429Count > 0, "POST /login should have been rate limited");

        // GET /login should NOT match the POST /login rule — falls through to the default bucket
        int get429Count = 0;
        for (int i = 0; i < 5; i++) {
            HttpRequest request = HttpRequest.newBuilder().uri(loginUri).GET().build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 429) {
                get429Count++;
            }
        }
        assertEquals(0, get429Count, "GET /login should not be rate limited by the POST /login bucket");
    }

    @Test
    public void anyMethodEndpointSharesBucketAcrossHttpMethods() throws Exception {
        HttpClient client = TestUtils.newSessionClient();
        TestUtils.login(client, port, "test-user-14", "abcd1234");

        // /hello is configured with method=ANY, so GET and POST share one bucket.
        // Send a mix of GETs and POSTs to consume the entire HELLO_USER_CAPACITY.
        URI helloUri = URI.create("http://localhost:" + port + "/hello");
        long half = HELLO_USER_CAPACITY / 2;
        for (int i = 0; i < half; i++) {
            HttpRequest request = HttpRequest.newBuilder().uri(helloUri).GET().build();
            client.send(request, HttpResponse.BodyHandlers.ofString());
        }
        for (int i = 0; i < half; i++) {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(helloUri)
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();
            client.send(request, HttpResponse.BodyHandlers.ofString());
        }

        // The bucket should now be exhausted — the next GET should be 429
        int status429Count = 0;
        for (int i = 0; i < 5; i++) {
            HttpRequest request = HttpRequest.newBuilder().uri(helloUri).GET().build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 429) {
                status429Count++;
            }
        }
        assertTrue(status429Count > 0, "GET and POST should share the same bucket when method is ANY");
    }

    @Test
    public void prefixLimitAppliesToUserIdPaths() throws Exception {
        HttpClient client = TestUtils.newSessionClient();
        TestUtils.login(client, port, "test-user-15", "abcd1234");

        // /users is configured with prefix=true, so /users/<userId> is covered by it. Exhausting the bucket
        // after exactly USERS_USER_CAPACITY requests proves the /users limit — not the larger catch-all
        // default — was applied.
        URI userUri = URI.create("http://localhost:" + port + "/users/7");
        int successCount = 0;
        int status429Count = 0;
        for (int i = 0; i < USERS_USER_CAPACITY + 5; i++) {
            HttpRequest request = HttpRequest.newBuilder().uri(userUri).GET().build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                successCount++;
            } else if (response.statusCode() == 429) {
                status429Count++;
            }
        }

        assertEquals(USERS_USER_CAPACITY, successCount, "/users/<userId> should be limited by the /users prefix limit");
        assertEquals(5, status429Count, "requests beyond the /users capacity should be 429");
        assertTrue(RateLimitPolicies.DEFAULT_POLICY.userBucket().capacity() > USERS_USER_CAPACITY,
                "the catch-all default must be larger than USERS_USER_CAPACITY for this test to be meaningful");
    }

    @Test
    public void prefixLimitSharesOneBucketAcrossPathsUnderThePrefix() throws Exception {
        HttpClient client = TestUtils.newSessionClient();
        TestUtils.login(client, port, "test-user-16", "abcd1234");

        // Spend the whole /users bucket across a mix of paths under the prefix.
        for (int i = 0; i < USERS_USER_CAPACITY; i++) {
            String path = (i % 2 == 0) ? "/users" : "/users/" + i;
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create("http://localhost:" + port + path)).GET().build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode(), path + " should succeed while the bucket has tokens");
        }

        // Both the collection and a single-user path should now be exhausted.
        for (String path : List.of("/users", "/users/7")) {
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create("http://localhost:" + port + path)).GET().build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(429, response.statusCode(), path + " should share the exhausted /users bucket");
        }
    }
}
