package com.redshiftsoft.example.webapp.limit;

import com.redshiftsoft.example.webapp.AbstractServletTest;
import com.redshiftsoft.example.webapp.TestUtils;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SuppressWarnings("ConstantValue")
public class RateLimiting_User_MultiSession_Test extends AbstractServletTest {

    @Test
    public void multipleSessionsSameUserHitsPerUserRateLimit() throws Exception {
        String username = "test-user-03";
        int sessionCount = 5;
        int requestsPerSession = 12;
        assertTrue(sessionCount * requestsPerSession > RateLimitPolicies.DEFAULT_POLICY.userBucket().capacity());

        List<HttpClient> sessions = new ArrayList<>();
        for (int s = 0; s < sessionCount; s++) {
            HttpClient client = TestUtils.newSessionClient();
            HttpResponse<String> loginResponse = TestUtils.login(client, port, username, "abcd1234");
            assertEquals(200, loginResponse.statusCode());
            sessions.add(client);
        }
        URI uri = URI.create("http://localhost:" + port + "/hello");

        //
        // when
        //
        int userLimitCount = 0;
        int overallLimitCount = 0;
        for (HttpClient client : sessions) {
            for (int i = 0; i < requestsPerSession; i++) {
                HttpRequest request = HttpRequest.newBuilder().uri(uri).GET().build();
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 429) {
                    if (response.body().contains("Too many requests for user " + username)) {
                        userLimitCount++;
                    } else {
                        overallLimitCount++;
                    }
                } else {
                    assertEquals(200, response.statusCode());
                }
            }
        }

        //
        // then
        //
        assertTrue(userLimitCount > 0, "should have hit the per-user rate limit across multiple sessions");
        assertEquals(0, overallLimitCount, "should not have hit the overall rate limit");
    }

    @Test
    public void multipleSessionsSameUserStaysUnderRateLimit() throws Exception {
        String username = "test-user-04";
        int sessionCount = 5;
        int requestsPerSession = 8;
        assertTrue(sessionCount * requestsPerSession < RateLimitPolicies.DEFAULT_POLICY.userBucket().capacity());

        List<HttpClient> sessions = new ArrayList<>();
        for (int s = 0; s < sessionCount; s++) {
            HttpClient client = TestUtils.newSessionClient();
            HttpResponse<String> loginResponse = TestUtils.login(client, port, username, "abcd1234");
            assertEquals(200, loginResponse.statusCode());
            sessions.add(client);
        }

        URI uri = URI.create("http://localhost:" + port + "/hello");

        for (HttpClient client : sessions) {
            for (int i = 0; i < requestsPerSession; i++) {
                HttpRequest request = HttpRequest.newBuilder().uri(uri).GET().build();
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                assertEquals(200, response.statusCode(), "all requests should succeed when staying under the rate limit");
            }
        }
    }

}
