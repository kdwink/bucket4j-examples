package com.redshiftsoft.example.webapp.limit;

import java.time.Duration;
import java.util.Objects;

/**
 * The buckets applied to an {@link Endpoint}: one per user, one per organization, and optionally one holding
 * concurrency permits. Build one with {@link #builder()}:
 *
 * <pre>
 *   RateLimitPolicy.builder()
 *           .userBucket(new BucketConfig(50, 20, Duration.ofSeconds(1)))
 *           .orgBucket(new BucketConfig(200, 40, Duration.ofSeconds(1)))
 *           .maxConcurrentRequests(10)
 *           .mode(RateLimitPolicyMode.RECORD)
 *           .build();
 * </pre>
 *
 * @param userBucket       per-user bucket config
 * @param orgBucket        per-organization bucket config
 * @param concurrentBucket permits for in-flight requests, or {@code null} for no concurrency limit. Its
 *                         capacity is the maximum number of concurrent requests, and permits are normally
 *                         handed back as requests finish. It also refills one permit per lease period, so a
 *                         permit lost for good — a server that died mid-request never runs its release, and
 *                         with a distributed bucket the loss outlives the process — comes back by itself.
 *                         See {@link Builder#permitLease(Duration)}.
 * @param mode             how strictly the limits are applied
 */
public record RateLimitPolicy(BucketConfig userBucket,
                              BucketConfig orgBucket,
                              BucketConfig concurrentBucket,
                              EnforcementMode mode) {

    /**
     * How long a permit may be held before the bucket assumes it was lost and refills it. Well above any
     * realistic request duration, so recovering a leaked permit does not race with a request still using it.
     */
    public static final Duration DEFAULT_PERMIT_LEASE = Duration.ofSeconds(60);

    public RateLimitPolicy {
        Objects.requireNonNull(userBucket, "userBucket is required");
        Objects.requireNonNull(orgBucket, "orgBucket is required");
        Objects.requireNonNull(mode, "mode is required");
        if (concurrentBucket != null && concurrentBucket.capacity() < 1) {
            throw new IllegalArgumentException("maxConcurrentRequests must be at least 1, or unset for no limit");
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public boolean hasConcurrencyLimit() {
        return concurrentBucket != null;
    }

    /** Most requests allowed in flight at once, or {@code null} for no limit. */
    public Integer maxConcurrentRequests() {
        return concurrentBucket == null ? null : (int) concurrentBucket.capacity();
    }

    public static final class Builder {

        private BucketConfig userBucket;
        private BucketConfig orgBucket;
        private Integer maxConcurrentRequests;
        private Duration permitLease = DEFAULT_PERMIT_LEASE;
        private EnforcementMode mode = EnforcementMode.BLOCK;

        private Builder() {
        }

        public Builder userBucket(BucketConfig userBucket) {
            this.userBucket = userBucket;
            return this;
        }

        public Builder orgBucket(BucketConfig orgBucket) {
            this.orgBucket = orgBucket;
            return this;
        }

        /** Leave unset for no concurrency limit. */
        public Builder maxConcurrentRequests(int maxConcurrentRequests) {
            this.maxConcurrentRequests = maxConcurrentRequests;
            return this;
        }

        /**
         * How long a permit may be held before it is assumed lost and refilled; defaults to
         * {@link #DEFAULT_PERMIT_LEASE}. Only relevant alongside {@link #maxConcurrentRequests(int)}.
         *
         * <p>The bucket refills one permit per lease period. While requests release their permits normally
         * the bucket sits at capacity and that refill does nothing, since tokens never exceed capacity. It
         * matters in two cases: a permit that was never released is recovered after one lease, and a fully
         * saturated endpoint admits one extra concurrent request per lease. Keep the lease well above the
         * slowest request the endpoint serves so the second case stays negligible.
         */
        public Builder permitLease(Duration permitLease) {
            this.permitLease = permitLease;
            return this;
        }

        /** Defaults to {@link EnforcementMode#BLOCK}. */
        public Builder mode(EnforcementMode mode) {
            this.mode = mode;
            return this;
        }

        public RateLimitPolicy build() {
            // Built here rather than in maxConcurrentRequests(), so the two setters can be called in any order.
            BucketConfig concurrentBucket = null;
            if (maxConcurrentRequests != null) {
                Objects.requireNonNull(permitLease, "permitLease is required");
                concurrentBucket = new BucketConfig(maxConcurrentRequests, 1, permitLease);
            }
            return new RateLimitPolicy(userBucket, orgBucket, concurrentBucket, mode);
        }

    }

}
