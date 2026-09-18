package com.redshiftsoft.example.webapp.servlets;

import com.redshiftsoft.example.webapp.AbstractServletTest;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class SlowServletTest extends AbstractServletTest {

    private static HttpResponse<String> get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .GET()
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    public void getSlowReturns200OnlyAfterTheRequestedDelay() throws Exception {
        long delayMs = 250;

        long startNanos = System.nanoTime();
        HttpResponse<String> response = get("/slow/" + delayMs);
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;

        assertEquals(200, response.statusCode());
        assertEquals("{\"timeMs\":250}", response.body());
        assertTrue(elapsedMs >= delayMs, "response arrived after " + elapsedMs + "ms, expected at least " + delayMs + "ms");
    }

    @Test
    public void getSlowWithZeroDelayReturns200Immediately() throws Exception {
        HttpResponse<String> response = get("/slow/0");

        assertEquals(200, response.statusCode());
        assertEquals("{\"timeMs\":0}", response.body());
    }

    @Test
    public void getSlowReturns400WhenTimeMsIsMissing() throws Exception {
        HttpResponse<String> response = get("/slow");

        assertEquals(400, response.statusCode());
        assertTrue(response.body().contains("usage: GET /slow/{timeMs}"));
    }

    @Test
    public void getSlowReturns400ForNonNumericTimeMs() throws Exception {
        HttpResponse<String> response = get("/slow/abc");

        assertEquals(400, response.statusCode());
        assertTrue(response.body().contains("timeMs must be a number"));
    }

    @Test
    public void getSlowReturns400WhenTimeMsExceedsTheMaximum() throws Exception {
        HttpResponse<String> response = get("/slow/" + (SlowServlet.MAX_DELAY_MS + 1));

        assertEquals(400, response.statusCode());
        assertTrue(response.body().contains("timeMs must be between 0 and " + SlowServlet.MAX_DELAY_MS));
    }

}
