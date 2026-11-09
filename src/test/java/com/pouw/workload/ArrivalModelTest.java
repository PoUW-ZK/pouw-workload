package com.pouw.workload;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArrivalModelTest {
    @Test
    void constantModelUsesTheConfiguredMeanInterval() {
        ArrivalModel model = ArrivalModel.create(ArrivalModel.Kind.CONSTANT, 25, 20);

        assertEquals(40_000_000, model.nextDelayNanos(0));
        assertEquals(40_000_000, model.nextDelayNanos(100_000_000));
    }

    @Test
    void poissonModelMaintainsItsConfiguredMeanInterArrivalTime() {
        ArrivalModel model = ArrivalModel.create(ArrivalModel.Kind.POISSON, 20, 20);
        long totalNanos = 0;
        int samples = 50_000;

        for (int index = 0; index < samples; index++) {
            totalNanos += model.nextDelayNanos(0);
        }

        double averageNanos = (double) totalNanos / samples;
        assertTrue(Math.abs(averageNanos / 50_000_000d - 1) < 0.02,
                "Poisson mean interval was " + averageNanos + " ns");
    }

    @Test
    void burstModelPreservesTheConfiguredLongTermMeanRate() {
        ArrivalModel model = ArrivalModel.create(ArrivalModel.Kind.BURST, 20, 20);
        long durationNanos = 300_000_000_000L;
        long elapsedNanos = 0;
        long arrivals = 0;

        while (elapsedNanos < durationNanos) {
            elapsedNanos += model.nextDelayNanos(elapsedNanos);
            arrivals++;
        }

        double actualRate = arrivals / (durationNanos / 1_000_000_000d);
        assertTrue(Math.abs(actualRate / 20d - 1) < 0.05,
                "Burst model rate was " + actualRate + " requests/s");
    }
}
