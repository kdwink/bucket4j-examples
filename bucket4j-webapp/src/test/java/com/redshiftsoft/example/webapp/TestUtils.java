package com.redshiftsoft.example.webapp;

import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

public class TestUtils {

    public static HttpClient newSessionClient() {
        return HttpClient.newBuilder()
                .cookieHandler(new CookieManager())
                .build();
    }

    public static HttpResponse<String> login(HttpClient client, int port, String username, String password) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/login"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("username=" + username + "&password=" + password))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

}
