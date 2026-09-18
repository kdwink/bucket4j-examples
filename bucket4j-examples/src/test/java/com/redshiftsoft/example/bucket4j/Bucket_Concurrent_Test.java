package com.redshiftsoft.example.bucket4j;

import io.github.bucket4j.Bucket;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class Bucket_Concurrent_Test {


    @Test
    public void concurrentConsumptionIsLimitedToCapacity() throws InterruptedException {
        final int CAPACITY = 20;
        Bucket bucket = Bucket
                .builder()
                .addLimit(limit ->
                        limit.capacity(CAPACITY).refillGreedy(10, Duration.ofSeconds(1))
                )
                .build();
        int threadCount = 8;
        int attemptsPerThread = 10;

        AtomicInteger totalConsumed = new AtomicInteger();
        AtomicInteger totalRejected = new AtomicInteger();
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            Thread t = new Thread(() -> {
                try {
                    startLatch.await();
                    for (int j = 0; j < attemptsPerThread; j++) {
                        if (bucket.tryConsume(1)) {
                            totalConsumed.incrementAndGet();
                        } else {
                            totalRejected.incrementAndGet();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
            t.start();
        }

        startLatch.countDown();
        doneLatch.await();

        int totalAttempts = threadCount * attemptsPerThread;
        assertEquals(totalAttempts, totalConsumed.get() + totalRejected.get());
        assertEquals(CAPACITY, totalConsumed.get(),
                "exactly " + CAPACITY + " tokens should be consumed across all threads");
    }

}
