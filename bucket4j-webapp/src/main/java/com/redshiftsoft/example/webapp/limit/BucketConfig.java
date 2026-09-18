package com.redshiftsoft.example.webapp.limit;

import io.github.bucket4j.Bucket;
import io.github.bucket4j.BucketConfiguration;

import java.time.Duration;

public record BucketConfig(long capacity, long refillTokens, Duration refillPeriod) {

    public Bucket createBucket() {
        return Bucket.builder()
                .addLimit(limit -> limit.capacity(capacity).refillGreedy(refillTokens, refillPeriod))
                .build();
    }

    /** The same limit expressed for a distributed bucket, which is configured remotely. */
    public BucketConfiguration toBucketConfiguration() {
        return BucketConfiguration.builder()
                .addLimit(limit -> limit.capacity(capacity).refillGreedy(refillTokens, refillPeriod))
                .build();
    }

}
