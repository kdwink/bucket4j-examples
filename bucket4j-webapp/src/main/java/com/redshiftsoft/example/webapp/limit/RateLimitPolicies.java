package com.redshiftsoft.example.webapp.limit;

import java.time.Duration;

public class RateLimitPolicies {

    private static final BucketConfig DEFAULT_USER_CONFIG = new BucketConfig(50, 20, Duration.ofSeconds(1));
    private static final BucketConfig DEFAULT_ORG_CONFIG = new BucketConfig(200, 40, Duration.ofSeconds(1));

    public static final RateLimitPolicy DEFAULT_POLICY = RateLimitPolicy.builder()
            .userBucket(DEFAULT_USER_CONFIG)
            .orgBucket(DEFAULT_ORG_CONFIG)
            .build();

    /** POST /login — deliberately tight, and slow to refill, to blunt credential guessing. */
    public static final RateLimitPolicy LOGIN_POLICY = RateLimitPolicy.builder()
            .userBucket(new BucketConfig(10, 5, Duration.ofMinutes(1)))
            .orgBucket(DEFAULT_ORG_CONFIG)
            .build();

    /** /hello — cheap to serve, so the per-user allowance matches the default. */
    public static final RateLimitPolicy HELLO_POLICY = RateLimitPolicy.builder()
            .userBucket(new BucketConfig(50, 20, Duration.ofSeconds(1)))
            .orgBucket(DEFAULT_ORG_CONFIG)
            .build();

    /** /users and /users/{userId} — a slow refill, so exhaustion is easy to observe in tests. */
    public static final RateLimitPolicy USERS_POLICY = RateLimitPolicy.builder()
            .userBucket(new BucketConfig(15, 5, Duration.ofMinutes(1)))
            .orgBucket(DEFAULT_ORG_CONFIG)
            .build();

    /**
     * /slow/{timeMs} — each request occupies a thread for its whole duration, so the token buckets are
     * generous but only ten requests may be in flight at once.
     */
    public static final RateLimitPolicy SLOW_POLICY = RateLimitPolicy.builder()
            .userBucket(DEFAULT_USER_CONFIG)
            .orgBucket(DEFAULT_ORG_CONFIG)
            .maxConcurrentRequests(10)
            .build();


}
