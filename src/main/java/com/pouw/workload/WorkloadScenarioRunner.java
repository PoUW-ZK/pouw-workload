package com.pouw.workload;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class WorkloadScenarioRunner {
    private final LightNodeClient client;
    private final FullNodeClient fullNodeClient;
    private final Duration requestTimeout;
    private final WorkloadMetrics metrics;
    private final java.util.function.Consumer<ReadOperationExecutor> executorListener;
    private final java.util.function.Consumer<TransferOperationExecutor> transferExecutorListener;
    private final Object lifecycleLock = new Object();
    private boolean stopped;
    private List<OpenLoopScheduler> activeSchedulers = List.of();
    private java.util.concurrent.CompletableFuture<LightNodeClient.ChainHead> activeHead;
    private TransferOperationExecutor activeTransferExecutor;

    public WorkloadScenarioRunner(LightNodeClient client) {
        this(client, null, Duration.ofSeconds(5), null, executor -> { }, executor -> { });
    }

    public WorkloadScenarioRunner(LightNodeClient client, WorkloadMetrics metrics,
                                  java.util.function.Consumer<ReadOperationExecutor> executorListener) {
        this(client, null, Duration.ofSeconds(5), metrics, executorListener, executor -> { });
    }

    public WorkloadScenarioRunner(
            LightNodeClient client,
            FullNodeClient fullNodeClient,
            Duration requestTimeout,
            WorkloadMetrics metrics,
            java.util.function.Consumer<ReadOperationExecutor> executorListener,
            java.util.function.Consumer<TransferOperationExecutor> transferExecutorListener
    ) {
        this.client = client;
        this.fullNodeClient = fullNodeClient;
        this.requestTimeout = requestTimeout;
        this.metrics = metrics;
        this.executorListener = executorListener;
        this.transferExecutorListener = transferExecutorListener;
    }

    public RunResult run(Path scenarioPath)
            throws IOException, InterruptedException, ExecutionException, TimeoutException {
        return run(WorkloadScenario.fromYaml(scenarioPath));
    }

    public RunResult run(WorkloadScenario scenario)
            throws InterruptedException, ExecutionException, TimeoutException {
        long initialHead = scenario.maxHeight();
        LightNodeClient.ChainHead headView = null;
        var operationMix = scenario.parsedOperationMix();
        if (operationMix.getOrDefault(ReadOperation.BLOCK_HEADER, 0d) > 0
                || operationMix.getOrDefault(ReadOperation.BLOCK, 0d) > 0
                || scenario.transferRatePerSecond() > 0) {
            java.util.concurrent.CompletableFuture<LightNodeClient.ChainHead> head;
            synchronized (lifecycleLock) {
                if (stopped) {
                    throw new java.util.concurrent.CancellationException("Scenario stopped");
                }
                activeHead = client.getChainHead();
                head = activeHead;
            }
            try {
                headView = head.get(30, TimeUnit.SECONDS);
                initialHead = headView.height();
            } catch (ExecutionException error) {
                synchronized (lifecycleLock) {
                    if (stopped && error.getCause() instanceof java.util.concurrent.CancellationException) {
                        throw new java.util.concurrent.CancellationException("Scenario stopped");
                    }
                }
                throw error;
            }
        }
        try (ReadOperationExecutor executor =
                     new ReadOperationExecutor(client, scenario, initialHead, metrics)) {
            executorListener.accept(executor);
            TransferOperationExecutor transferExecutor = null;
            List<OpenLoopScheduler> schedulers = new ArrayList<>(2);
            try {
                if (scenario.transferRatePerSecond() > 0) {
                    if (fullNodeClient == null || headView == null) {
                        throw new IllegalStateException(
                                "FULL_NODE_URLS and a light-node head are required for transfers");
                    }
                    transferExecutor = new TransferOperationExecutor(
                            fullNodeClient, client, scenario, metrics, requestTimeout);
                    synchronized (lifecycleLock) {
                        if (stopped) {
                            throw new java.util.concurrent.CancellationException("Scenario stopped");
                        }
                        activeTransferExecutor = transferExecutor;
                    }
                    transferExecutorListener.accept(transferExecutor);
                    transferExecutor.initialize(headView.chainId());
                }

                List<java.util.concurrent.CompletableFuture<OpenLoopScheduler.ScheduleResult>>
                        running = new ArrayList<>(2);
                if (scenario.ratePerSecond() > 0) {
                    OpenLoopScheduler scheduler = new OpenLoopScheduler();
                    schedulers.add(scheduler);
                    ArrivalModel arrivalModel = ArrivalModel.create(
                            scenario.arrivalModel(),
                            scenario.ratePerSecond(),
                            scenario.burstPeriodSeconds()
                    );
                    running.add(startScheduler(scheduler, scenario, arrivalModel, executor::submitNext));
                }
                if (scenario.transferRatePerSecond() > 0) {
                    OpenLoopScheduler scheduler = new OpenLoopScheduler();
                    schedulers.add(scheduler);
                    ArrivalModel arrivalModel = ArrivalModel.create(
                            scenario.arrivalModel(),
                            scenario.transferRatePerSecond(),
                            scenario.burstPeriodSeconds()
                    );
                    TransferOperationExecutor active = transferExecutor;
                    running.add(startScheduler(scheduler, scenario, arrivalModel, active::submitNext));
                }

                long scheduledArrivals = 0;
                long measuredDurationNanos = 0;
                for (var scheduled : running) {
                    OpenLoopScheduler.ScheduleResult completed =
                            scheduled.get(scenario.durationSeconds() + 5, TimeUnit.SECONDS);
                    scheduledArrivals += completed.scheduledArrivals();
                    measuredDurationNanos = Math.max(
                            measuredDurationNanos, completed.durationNanos());
                }
                executor.awaitCompletion(Duration.ofSeconds(10));
                TransferOperationExecutor.TransferMetrics transferMetrics =
                        TransferOperationExecutor.TransferMetrics.empty();
                if (transferExecutor != null) {
                    transferExecutor.awaitCompletion(transferExecutor.completionTimeout());
                    transferMetrics = transferExecutor.snapshot();
                }
                return new RunResult(
                        scheduledArrivals,
                        measuredDurationNanos,
                        executor.snapshot(),
                        transferMetrics
                );
            } finally {
                synchronized (lifecycleLock) {
                    activeSchedulers = List.of();
                    activeTransferExecutor = null;
                }
                for (OpenLoopScheduler scheduler : schedulers) {
                    scheduler.close();
                }
                if (transferExecutor != null) {
                    transferExecutor.stop();
                    transferExecutor.close();
                }
            }
        }
    }

    private java.util.concurrent.CompletableFuture<OpenLoopScheduler.ScheduleResult> startScheduler(
            OpenLoopScheduler scheduler,
            WorkloadScenario scenario,
            ArrivalModel arrivalModel,
            java.util.function.LongPredicate handler
    ) {
        synchronized (lifecycleLock) {
            if (stopped) {
                throw new java.util.concurrent.CancellationException("Scenario stopped");
            }
            List<OpenLoopScheduler> updated = new ArrayList<>(activeSchedulers);
            updated.add(scheduler);
            activeSchedulers = List.copyOf(updated);
            return scheduler.start(
                    Duration.ofSeconds(scenario.durationSeconds()), arrivalModel, handler);
        }
    }

    public void stop() {
        synchronized (lifecycleLock) {
            stopped = true;
            if (activeHead != null) {
                activeHead.cancel(true);
            }
            activeSchedulers.forEach(OpenLoopScheduler::stop);
            if (activeTransferExecutor != null) {
                activeTransferExecutor.stop();
            }
        }
    }

    public record RunResult(
            long scheduledArrivals,
            long measuredDurationNanos,
            Map<ReadOperation, ReadOperationExecutor.OperationMetrics> operations,
            TransferOperationExecutor.TransferMetrics transfers
    ) {
        public long completedRequests() {
            return operations.values().stream()
                    .mapToLong(ReadOperationExecutor.OperationMetrics::completed)
                    .sum() + transfers.completed();
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
