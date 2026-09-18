package com.redshiftsoft.example.webapp.limit;

import io.github.bucket4j.Bucket;

/**
 * Where {@link ThrottlingFilter} gets its buckets from — {@link ValkeyBucketRegistry}, which keeps them in a
 * cluster so every app server shares one set of limits. This is the seam the filter is built against, so a
 * test can hand it a registry pointed at a cluster of its own.
 */
public interface BucketRegistry {

    /**
     * The bucket for {@code key}, created with {@code config} if it does not exist yet. Callers hit this on
     * every request, so implementations must be cheap and thread-safe.
     */
    Bucket bucket(String key, BucketConfig config);

}
