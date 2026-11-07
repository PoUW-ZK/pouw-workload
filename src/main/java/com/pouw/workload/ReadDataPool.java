package com.pouw.workload;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

final class ReadDataPool {
    private final WorkloadScenario scenario;
    private final AtomicLong observedHead;

    ReadDataPool(WorkloadScenario scenario, long initialHead) {
        this.scenario = scenario;
        if (hasHeightReads(scenario) && initialHead < scenario.minHeight()) {
            throw new IllegalArgumentException(
                    "Node head " + initialHead + " is below the scenario's minimum height " + scenario.minHeight()
            );
        }
        observedHead = new AtomicLong(Math.min(scenario.maxHeight(), initialHead));
    }

    void observeHead(long height) {
        if (!hasHeightReads(scenario)) {
            return;
        }
        if (height < scenario.minHeight()) {
            throw new IllegalStateException(
                    "Observed node head " + height + " is below the scenario's minimum height "
                            + scenario.minHeight()
            );
        }
        observedHead.set(Math.min(scenario.maxHeight(), height));
    }

    long nextHeight() {
        long highestAvailableHeight = observedHead.get();
        if (highestAvailableHeight < scenario.minHeight()) {
            throw new IllegalStateException("No available block height in the configured scenario range");
        }
        return ThreadLocalRandom.current().nextLong(scenario.minHeight(), highestAvailableHeight + 1);
    }

    String nextAddress() {
        return randomValue(scenario.addresses());
    }

    String nextTransactionId() {
        return randomValue(scenario.transactionIds());
    }

    String nextProofTaskId() {
        return randomValue(scenario.proofTaskIds());
    }

    private static boolean hasHeightReads(WorkloadScenario scenario) {
        var mix = scenario.parsedOperationMix();
        return mix.getOrDefault(ReadOperation.BLOCK_HEADER, 0d) > 0
                || mix.getOrDefault(ReadOperation.BLOCK, 0d) > 0;
    }

    private static String randomValue(List<String> values) {
        return values.get(ThreadLocalRandom.current().nextInt(values.size()));
    }
}
