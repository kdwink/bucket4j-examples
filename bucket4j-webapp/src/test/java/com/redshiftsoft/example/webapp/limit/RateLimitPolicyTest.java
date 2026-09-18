package com.redshiftsoft.example.webapp.limit;

import io.github.bucket4j.Bucket;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class RateLimitPolicyTest {

    private static final BucketConfig CONFIG = new BucketConfig(10, 1, Duration.ofSeconds(1));

    private static RateLimitPolicy.Builder builder() {
        return RateLimitPolicy.builder().userBucket(CONFIG).orgBucket(CONFIG);
    }

    @Test
    public void maxConcurrentRequestsIsUnsetByDefault() {
        RateLimitPolicy policy = builder().build();

        assertNull(policy.maxConcurrentRequests());
        assertFalse(policy.hasConcurrencyLimit());
    }

    @Test
    public void maxConcurrentRequestsIsKeptWhenSet() {
        RateLimitPolicy policy = builder().maxConcurrentRequests(4).build();

        assertEquals(4, policy.maxConcurrentRequests());
        assertTrue(policy.hasConcurrencyLimit());
    }

    @Test
    public void maxConcurrentRequestsMustBePositive() {
        assertThrows(IllegalArgumentException.class, () -> builder().maxConcurrentRequests(0).build());
        assertThrows(IllegalArgumentException.class, () -> builder().maxConcurrentRequests(-1).build());
    }

    @Test
    public void permitsRefillOncePerLeaseSoLostPermitsComeBack() {
        RateLimitPolicy policy = builder().maxConcurrentRequests(4).build();

        assertEquals(4, policy.concurrentBucket().capacity());
        assertEquals(1, policy.concurrentBucket().refillTokens());
        assertEquals(RateLimitPolicy.DEFAULT_PERMIT_LEASE, policy.concurrentBucket().refillPeriod());
    }

    @Test
    public void permitLeaseCanBeSetInEitherOrder() {
        Duration lease = Duration.ofSeconds(5);

        RateLimitPolicy leaseFirst = builder().permitLease(lease).maxConcurrentRequests(4).build();
        RateLimitPolicy leaseLast = builder().maxConcurrentRequests(4).permitLease(lease).build();

        assertEquals(lease, leaseFirst.concurrentBucket().refillPeriod());
        assertEquals(lease, leaseLast.concurrentBucket().refillPeriod());
    }

    @Test
    public void aPermitNeverReleasedIsRecoveredAfterTheLease() throws Exception {
        Duration lease = Duration.ofSeconds(1);
        Bucket permits = builder().maxConcurrentRequests(1).permitLease(lease).build()
                .concurrentBucket()
                .createBucket();

        // Take the only permit and never hand it back, as a server dying mid-request would.
        assertTrue(permits.tryConsume(1));
        assertFalse(permits.tryConsume(1), "the permit is held, so there should be none left");

        Thread.sleep(lease.toMillis() + 200);

        assertTrue(permits.tryConsume(1), "the leaked permit should have been refilled after one lease");
    }

    @Test
    public void bucketsAreRequired() {
        assertThrows(NullPointerException.class, () -> RateLimitPolicy.builder().orgBucket(CONFIG).build());
        assertThrows(NullPointerException.class, () -> RateLimitPolicy.builder().userBucket(CONFIG).build());
    }

    @Test
    public void modeDefaultsToBlock() {
        assertEquals(EnforcementMode.BLOCK, builder().build().mode());
        assertEquals(EnforcementMode.BLOCK, builder().maxConcurrentRequests(4).build().mode());
    }

    @Test
    public void modeIsKeptWhenSet() {
        assertEquals(EnforcementMode.RECORD, builder().mode(EnforcementMode.RECORD).build().mode());
        assertEquals(EnforcementMode.DISABLED, builder().mode(EnforcementMode.DISABLED).build().mode());
    }

    @Test
    public void modeIsRequired() {
        assertThrows(NullPointerException.class, () -> builder().mode(null).build());
    }

}
