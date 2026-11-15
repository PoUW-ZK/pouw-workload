package com.pouw.workload;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class WorkloadScenarioRunner {
    private final LightNodeClient client;
    private final WorkloadMetrics metrics;
    private final java.util.function.Consumer<ReadOperationExecutor> executorListener;
    private final Object lifecycleLock = new Object();
    private boolean stopped;
    private OpenLoopScheduler activeScheduler;
    private java.util.concurrent.CompletableFuture<LightNodeClient.ChainHead> activeHead;

    public WorkloadScenarioRunner(LightNodeClient client) {
        this(client, null, executor -> { });
    }

    public WorkloadScenarioRunner(LightNodeClient client, WorkloadMetrics metrics,
                                  java.util.function.Consumer<ReadOperationExecutor> executorListener) {
        this.client = client;
        this.metrics = metrics;
        this.executorListener = executorListener;
    }

    public RunResult run(Path scenarioPath)
            throws IOException, InterruptedException, ExecutionException, TimeoutException {
        return run(WorkloadScenario.fromYaml(scenarioPath));
    }

    public RunResult run(WorkloadScenario scenario)
            throws InterruptedException, ExecutionException, TimeoutException {
        long initialHead = scenario.maxHeight();
        var operationMix = scenario.parsedOperationMix();
        if (operationMix.getOrDefault(ReadOperation.BLOCK_HEADER, 0d) > 0
                || operationMix.getOrDefault(ReadOperation.BLOCK, 0d) > 0) {
            java.util.concurrent.CompletableFuture<LightNodeClient.ChainHead> head;
            synchronized (lifecycleLock) {
                if (stopped) {
                    throw new java.util.concurrent.CancellationException("Scenario stopped");
                }
                activeHead = client.getChainHead();
                head = activeHead;
            }
            try {
                initialHead = head.get(30, TimeUnit.SECONDS).height();
            } catch (ExecutionException error) {
                synchronized (lifecycleLock) {
                    if (stopped && error.getCause() instanceof java.util.concurrent.CancellationException) {
                        throw new java.util.concurrent.CancellationException("Scenario stopped");
                    }
                }
                throw error;
            }
        }
        try (ReadOperationExecutor executor = new ReadOperationExecutor(client, scenario, initialHead, metrics);
             OpenLoopScheduler scheduler = new OpenLoopScheduler()) {
            executorListener.accept(executor);
            ArrivalModel arrivalModel = ArrivalModel.create(
                    scenario.arrivalModel(),
                    scenario.ratePerSecond(),
                    scenario.burstPeriodSeconds()
            );
            java.util.concurrent.CompletableFuture<OpenLoopScheduler.ScheduleResult> running;
            synchronized (lifecycleLock) {
                if (stopped) {
                    throw new java.util.concurrent.CancellationException("Scenario stopped");
                }
                activeScheduler = scheduler;
                running = scheduler.start(Duration.ofSeconds(scenario.durationSeconds()),
                        arrivalModel, executor::submitNext);
            }
            OpenLoopScheduler.ScheduleResult scheduled =
                    running.get(scenario.durationSeconds() + 5, TimeUnit.SECONDS);
            executor.awaitCompletion(Duration.ofSeconds(10));
            return new RunResult(
                    scheduled.scheduledArrivals(),
                    scheduled.durationNanos(),
                    executor.snapshot()
            );
        }
    }

    public void stop() {
        synchronized (lifecycleLock) {
            stopped = true;
            if (activeHead != null) {
                activeHead.cancel(true);
            }
            if (activeScheduler != null) {
                activeScheduler.stop();
            }
        }
    }

    public record RunResult(
            long scheduledArrivals,
            long measuredDurationNanos,
            Map<ReadOperation, ReadOperationExecutor.OperationMetrics> operations
    ) {
        public long completedRequests() {
            return operations.values().stream()
                    .mapToLong(ReadOperationExecutor.OperationMetrics::completed)
                    .sum();
        }

        public double offeredRequestsPerSecond() {
            return requestsPerSecond(scheduledArrivals);
        }

        public double completedRequestsPerSecond() {
            return requestsPerSecond(completedRequests());
        }

        private double requestsPerSecond(long requests) {
            return requests * 1_000_000_000d / measuredDurationNanos;
        }
    }
}
