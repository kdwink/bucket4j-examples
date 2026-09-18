package com.redshiftsoft.example.webapp.limit;

import com.redshiftsoft.example.webapp.AbstractServletTest;
import com.redshiftsoft.example.webapp.TestUtils;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class RateLimiting_User_Test extends AbstractServletTest {

    @Test
    public void loggedInUserHitsPerUserRateLimit() throws Exception {
        // given
        HttpClient client = TestUtils.newSessionClient();
        HttpResponse<String> loginResponse = TestUtils.login(client, port, "test-user-02", "abcd1234");
        assertEquals(200, loginResponse.statusCode());
        URI uri = URI.create("http://localhost:" + port + "/hello");
        long howMany = RateLimitPolicies.DEFAULT_POLICY.userBucket().capacity() + 10;

        //
        // when
        //
        int userLimitCount = 0;
        int overallLimitCount = 0;
        for (int i = 0; i < howMany; i++) {
            HttpRequest request = HttpRequest.newBuilder().uri(uri).GET().build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 429) {
                if (response.body().contains("Too many requests for user test-user-02")) {
                    userLimitCount++;
                } else {
                    overallLimitCount++;
                }
            } else {
                assertEquals(200, response.statusCode());
            }
        }

        //
        // then
        //
        assertTrue(userLimitCount > 0, "should have hit the per-user rate limit");
        assertEquals(0, overallLimitCount, "should not have hit the overall rate limit");
    }

}
