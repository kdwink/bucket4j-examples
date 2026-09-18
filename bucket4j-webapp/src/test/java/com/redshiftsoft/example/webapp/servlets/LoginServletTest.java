package com.redshiftsoft.example.webapp.servlets;

import com.redshiftsoft.example.webapp.AbstractServletTest;
import com.redshiftsoft.example.webapp.TestUtils;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class LoginServletTest extends AbstractServletTest {

    @Test
    public void loginReturnsUsernameOnSuccess() throws Exception {
        HttpClient client = TestUtils.newSessionClient();

        HttpResponse<String> response = TestUtils.login(client, port, "test-user-01", "abcd1234");

        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("test-user-01"));
    }

    @Test
    public void loginReturns401ForInvalidCredentials() throws Exception {
        HttpClient client = HttpClient.newHttpClient();

        HttpResponse<String> response = TestUtils.login(client, port, "test-user-01", "wrong");

        assertEquals(401, response.statusCode());
        assertTrue(response.body().contains("invalid credentials"));
    }

    @Test
    public void loginReturns400WhenUsernameMissing() throws Exception {
        HttpClient client = HttpClient.newHttpClient();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/login"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("password=secret"))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(400, response.statusCode());
        assertTrue(response.body().contains("username and password are required"));
    }

    @Test
    public void loginReturns400WhenPasswordMissing() throws Exception {
        HttpClient client = HttpClient.newHttpClient();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/login"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("username=alice"))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(400, response.statusCode());
        assertTrue(response.body().contains("username and password are required"));
    }

}
