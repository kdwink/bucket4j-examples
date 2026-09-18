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
public class RateLimiting_Org_Test extends AbstractServletTest {

    @Test
    public void multipleUsersInSameOrgHitOrgRateLimit() throws Exception {
        //
        // given
        //
        int userCount = 5;
        int requestsPerUser = 45;
        assertTrue(userCount * requestsPerUser > RateLimitPolicies.DEFAULT_POLICY.orgBucket().capacity());

        List<HttpClient> clients = new ArrayList<>();
        for (int u = 1; u <= userCount; u++) {
            HttpClient client = TestUtils.newSessionClient();
            String username = String.format("test-user-%02d", u);
            HttpResponse<String> loginResponse = TestUtils.login(client, port, username, "abcd1234");
            assertEquals(200, loginResponse.statusCode());
            clients.add(client);
        }
        URI uri = URI.create("http://localhost:" + port + "/hello");

        //
        // when
        //
        int orgLimitCount = 0;
        int userLimitCount = 0;
        for (HttpClient client : clients) {
            for (int i = 0; i < requestsPerUser; i++) {
                HttpRequest request = HttpRequest.newBuilder().uri(uri).GET().build();
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 429) {
                    if (response.body().contains("Too many requests for organization")) {
                        orgLimitCount++;
                    } else if (response.body().contains("Too many requests for user")) {
                        userLimitCount++;
                    }
                } else {
                    assertEquals(200, response.statusCode());
                }
            }
        }

        //
        // then
        //
        assertTrue(orgLimitCount > 0, "should have hit the per-organization rate limit");
        assertEquals(0, userLimitCount, "should not have hit any per-user rate limit");
    }

    @Test
    public void usersFromDifferentOrgsAreNotRateLimitedByEachOther() throws Exception {
        //
        // given — one user from each of 5 different orgs (orgIds 0–4)
        //
        String[] usernames = {"test-user-50", "test-user-60", "test-user-70", "test-user-80", "test-user-90"};
        int requestsPerUser = 45;
        int totalRequests = usernames.length * requestsPerUser;
        assertTrue(totalRequests > RateLimitPolicies.DEFAULT_POLICY.orgBucket().capacity(), "total requests should exceed a single org's capacity");
        assertTrue(requestsPerUser <= RateLimitPolicies.DEFAULT_POLICY.orgBucket().capacity(), "per-user requests should stay within any single org's capacity");
        assertTrue(requestsPerUser <= RateLimitPolicies.DEFAULT_POLICY.userBucket().capacity(), "per-user requests should stay within user capacity");

        List<HttpClient> clients = new ArrayList<>();
        for (String username : usernames) {
            HttpClient client = TestUtils.newSessionClient();
            HttpResponse<String> loginResponse = TestUtils.login(client, port, username, "abcd1234");
            assertEquals(200, loginResponse.statusCode());
            clients.add(client);
        }
        URI uri = URI.create("http://localhost:" + port + "/hello");

        //
        // when
        //
        int rateLimitCount = 0;
        for (HttpClient client : clients) {
            for (int i = 0; i < requestsPerUser; i++) {
                HttpRequest request = HttpRequest.newBuilder().uri(uri).GET().build();
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 429) {
                    rateLimitCount++;
                } else {
                    assertEquals(200, response.statusCode());
                }
            }
        }

        //
        // then
        //
        assertEquals(0, rateLimitCount, "no user should be rate limited — each org's requests are within its own capacity");
    }

}
