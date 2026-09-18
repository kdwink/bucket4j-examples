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

public class HelloServletTest extends AbstractServletTest {

    @Test
    public void helloEndpointReturnsHtmlPage() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/hello"))
                .GET()
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("Hello, World!"));
    }

    @Test
    public void helloEndpointGreetsLoggedInUser() throws Exception {
        HttpClient client = TestUtils.newSessionClient();
        TestUtils.login(client, port, "test-user-01", "abcd1234");

        HttpRequest hello = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/hello"))
                .GET()
                .build();
        HttpResponse<String> response = client.send(hello, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("Hello, test-user-01!"));
    }

}
