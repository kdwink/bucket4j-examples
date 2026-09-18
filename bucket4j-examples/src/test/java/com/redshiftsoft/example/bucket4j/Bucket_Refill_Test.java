package com.redshiftsoft.example.bucket4j;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class Bucket_Refill_Test {

    @Test
    public void refillGreedy_longDuration() {
        // given
        final int CAPACITY = 20;
        Bandwidth b = Bandwidth.builder().capacity(CAPACITY).refillGreedy(CAPACITY, Duration.ofDays(1000)).build();
        Bucket bucket = Bucket.builder().addLimit(b).build();

        // when
        consumeAndRefill(bucket, CAPACITY, 5);
    }

    @Test
    public void refillGreedy_shortDuration() {
        // given
        final int CAPACITY = 20;
        Bandwidth b = Bandwidth.builder().capacity(CAPACITY).refillGreedy(CAPACITY, Duration.ofSeconds(1)).build();
        Bucket bucket = Bucket.builder().addLimit(b).build();

        // when
        consumeAndRefill(bucket, CAPACITY, CAPACITY);
        consumeAndRefill(bucket, CAPACITY, CAPACITY - 1);
        consumeAndRefill(bucket, CAPACITY, CAPACITY - 2);
    }

    @Test
    public void refillIntervally() {
        final int CAPACITY = 30;
        Bandwidth b = Bandwidth.builder().capacity(CAPACITY).refillIntervally(CAPACITY, Duration.ofDays(1000)).build();
        Bucket bucket = Bucket.builder().addLimit(b).build();

        // when
        consumeAndRefill(bucket, CAPACITY, 6);

    }

    private static void consumeAndRefill(Bucket bucket, int capacity, int howMany) {
        try {
            for (int i = 1; i <= howMany; i++) {
                assertTrue(bucket.tryConsume(1));
            }
            long availableTokens = bucket.getAvailableTokens();
            assertEquals(capacity - howMany, availableTokens);
        } finally {
            bucket.addTokens(howMany);
        }
        assertEquals(capacity, bucket.getAvailableTokens());
    }

}
