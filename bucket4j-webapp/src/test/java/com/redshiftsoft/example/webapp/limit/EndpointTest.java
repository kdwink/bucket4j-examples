package com.redshiftsoft.example.webapp.limit;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class EndpointTest {

    private static final BucketConfig CONFIG = new BucketConfig(10, 1, Duration.ofSeconds(1));
    private static final RateLimitPolicy POLICY = RateLimitPolicy.builder()
            .userBucket(CONFIG)
            .orgBucket(CONFIG)
            .build();

    private static Endpoint endpoint(String method, String path, boolean prefix) {
        return Endpoint.builder().method(method).path(path).prefix(prefix).policy(POLICY).build();
    }

    @Test
    public void prefixTrueMatchesPathsStartingWithPath() {
        Endpoint endpoint = endpoint("GET", "/api", true);

        assertTrue(endpoint.matches("GET", "/api"));
        assertTrue(endpoint.matches("GET", "/api/users"));
        assertFalse(endpoint.matches("GET", "/other"));
    }

    @Test
    public void prefixFalseMatchesExactPathOnly() {
        Endpoint endpoint = endpoint("GET", "/api", false);

        assertTrue(endpoint.matches("GET", "/api"));
        assertFalse(endpoint.matches("GET", "/api/users"));
    }

    @Test
    public void methodIsMatchedRegardlessOfPrefix() {
        assertFalse(endpoint("POST", "/api", true).matches("GET", "/api/users"));
        assertTrue(endpoint(Endpoint.ANY, "/api", true).matches("GET", "/api/users"));
        assertTrue(endpoint(Endpoint.ANY, "/api", false).matches("DELETE", "/api"));
    }

    @Test
    public void keyDistinguishesPrefixFromExactMatch() {
        assertEquals("GET /api*", endpoint("GET", "/api", true).key());
        assertEquals("GET /api", endpoint("GET", "/api", false).key());
    }

    @Test
    public void methodIsRequired() {
        assertThrows(NullPointerException.class, () -> endpoint(null, "/api", true));
    }

    @Test
    public void effectiveModeFallsBackToThePolicyMode() {
        Endpoint endpoint = endpoint("GET", "/api", true);

        assertNull(endpoint.mode());
        assertEquals(POLICY.mode(), endpoint.effectiveMode());
    }

    @Test
    public void effectiveModeUsesTheEndpointOverrideWhenSet() {
        Endpoint endpoint = Endpoint.builder()
                .method("GET")
                .path("/api")
                .prefix(true)
                .policy(POLICY)
                .mode(EnforcementMode.DISABLED)
                .build();

        assertEquals(EnforcementMode.BLOCK, POLICY.mode(), "the policy's own mode should differ from the override");
        assertEquals(EnforcementMode.DISABLED, endpoint.effectiveMode());
    }

    @Test
    public void methodDefaultsToAny() {
        Endpoint endpoint = Endpoint.builder().path("/api").policy(POLICY).build();

        assertEquals(Endpoint.ANY, endpoint.method());
        assertTrue(endpoint.matches("DELETE", "/api"));
    }

    @Test
    public void pathIsRequired() {
        assertThrows(NullPointerException.class, () -> Endpoint.builder().policy(POLICY).build());
    }

    @Test
    public void policyIsRequired() {
        assertThrows(NullPointerException.class, () -> Endpoint.builder().path("/api").build());
    }
}
