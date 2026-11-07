package com.pouw.workload;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenLoopSchedulerTest {
    @Test
    void stopCompletesTheRunAndCancelsFutureArrivals() throws Exception {
        CountDownLatch firstArrival = new CountDownLatch(1);
        try (OpenLoopScheduler scheduler = new OpenLoopScheduler()) {
            var result = scheduler.start(
                    Duration.ofSeconds(10),
                    ArrivalModel.create(ArrivalModel.Kind.CONSTANT, 1, 20),
                    ignored -> {
                        firstArrival.countDown();
                        return true;
                    }
            );

            assertTrue(firstArrival.await(1, TimeUnit.SECONDS));
            scheduler.stop();

            assertEquals(1, result.get(1, TimeUnit.SECONDS).scheduledArrivals());
        }
    }
}
