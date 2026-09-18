package com.redshiftsoft.example.webapp.servlets;

import com.redshiftsoft.example.webapp.AbstractServletTest;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class UsersServletTest extends AbstractServletTest {

    private static HttpResponse<String> get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .GET()
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    public void getUsersReturnsAllUsersAsJson() throws Exception {
        HttpResponse<String> response = get("/users");

        assertEquals(200, response.statusCode());
        assertEquals("application/json", response.headers().firstValue("Content-Type").orElseThrow());
        String body = response.body();
        assertTrue(body.startsWith("[") && body.endsWith("]"));
        assertTrue(body.contains("\"username\":\"test-user-00\""));
        assertTrue(body.contains("\"username\":\"test-user-99\""));
        assertEquals(100, body.split("\"userId\":", -1).length - 1, "should contain all 100 users");
    }

    @Test
    public void getUsersDoesNotExposePasswordHash() throws Exception {
        assertFalse(get("/users").body().contains("passwordHash"));
        assertFalse(get("/users/7").body().contains("passwordHash"));
    }

    @Test
    public void getUserByIdReturnsSingleUserAsJson() throws Exception {
        HttpResponse<String> response = get("/users/7");

        assertEquals(200, response.statusCode());
        assertEquals("{\"userId\":7,\"username\":\"test-user-07\",\"firstName\":\"Test\",\"lastName\":\"User 7\",\"organizationId\":0}",
                response.body());
    }

    @Test
    public void getUserByIdReturns404ForUnknownUser() throws Exception {
        HttpResponse<String> response = get("/users/12345");

        assertEquals(404, response.statusCode());
        assertTrue(response.body().contains("no user with userId 12345"));
    }

    @Test
    public void getUserByIdReturns400ForNonNumericUserId() throws Exception {
        HttpResponse<String> response = get("/users/abc");

        assertEquals(400, response.statusCode());
        assertTrue(response.body().contains("userId must be a number"));
    }

}
