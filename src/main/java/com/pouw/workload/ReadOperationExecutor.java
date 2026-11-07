package com.pouw.workload;

import org.HdrHistogram.ConcurrentHistogram;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

public final class ReadOperationExecutor implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(ReadOperationExecutor.class);
    private static final long MAX_TRACKED_LATENCY_MICROS = TimeUnit.DAYS.toMicros(30);
    private static final int HISTOGRAM_SIGNIFICANT_DIGITS = 3;
    private static final Duration DEFAULT_SHUTDOWN_TIMEOUT = Duration.ofSeconds(10);

    private final LightNodeClient client;
    private final ReadDataPool dataPool;
    private final ExecutorService virtualThreads = Executors.newVirtualThreadPerTaskExecutor();
    private final Semaphore permits;
    private final int maxConcurrency;
    private final EnumMap<ReadOperation, Double> operationWeights;
    private final double totalWeight;
    private final EnumMap<ReadOperation, Counters> counters = new EnumMap<>(ReadOperation.class);
    private final EnumMap<ReadOperation, ConcurrentHistogram> latencies = new EnumMap<>(ReadOperation.class);
    private volatile boolean closed;

    public ReadOperationExecutor(LightNodeClient client, WorkloadScenario scenario) {
        this(client, scenario, scenario.maxHeight());
    }

    public ReadOperationExecutor(LightNodeClient client, WorkloadScenario scenario, long initialHead) {
        this.client = client;
        dataPool = new ReadDataPool(scenario, initialHead);
        maxConcurrency = scenario.maxConcurrency();
        permits = new Semaphore(maxConcurrency);
        operationWeights = new EnumMap<>(ReadOperation.class);
        operationWeights.putAll(scenario.parsedOperationMix());
        totalWeight = operationWeights.values().stream().mapToDouble(Double::doubleValue).sum();
        for (ReadOperation operation : ReadOperation.values()) {
            counters.put(operation, new Counters());
            latencies.put(operation, new ConcurrentHistogram(
                    1,
                    MAX_TRACKED_LATENCY_MICROS,
                    HISTOGRAM_SIGNIFICANT_DIGITS
            ));
        }
    }

    public boolean submitNext(long plannedSendNanos) {
        double choice = ThreadLocalRandom.current().nextDouble(totalWeight);
        double accumulated = 0;
        for (ReadOperation operation : ReadOperation.values()) {
            double weight = operationWeights.getOrDefault(operation, 0d);
            accumulated += weight;
            if (weight > 0 && choice < accumulated) {
                return submit(operation, plannedSendNanos);
            }
        }
        throw new IllegalStateException("Could not select an operation from the configured mix");
    }

    public boolean submit(ReadOperation operation, long plannedSendNanos) {
        if (closed) {
            throw new IllegalStateException("ReadOperationExecutor is closed");
        }
        if (operationWeights.getOrDefault(operation, 0d) <= 0) {
            throw new IllegalArgumentException("Operation is not enabled by this scenario: " + operation);
        }
        Counters operationCounters = counters.get(operation);
        if (!permits.tryAcquire()) {
            operationCounters.rejected.incrementAndGet();
            return false;
        }

        try {
            virtualThreads.execute(() -> startRequest(operation, plannedSendNanos, operationCounters));
            return true;
        } catch (RejectedExecutionException error) {
            permits.release();
            operationCounters.rejected.incrementAndGet();
            if (!closed) {
                throw error;
            }
            return false;
        }
    }

    public Map<ReadOperation, OperationMetrics> snapshot() {
        EnumMap<ReadOperation, OperationMetrics> result = new EnumMap<>(ReadOperation.class);
        for (ReadOperation operation : ReadOperation.values()) {
            Counters operationCounters = counters.get(operation);
            ConcurrentHistogram histogram = latencies.get(operation);
            result.put(operation, new OperationMetrics(
                    operationCounters.completed.get(),
                    operationCounters.failures.get(),
                    operationCounters.rejected.get(),
                    histogram.getValueAtPercentile(50),
                    histogram.getValueAtPercentile(95),
                    histogram.getValueAtPercentile(99)
            ));
        }
        return Map.copyOf(result);
    }

    public void awaitCompletion(Duration timeout) throws InterruptedException, TimeoutException {
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        if (!permits.tryAcquire(maxConcurrency, timeout.toNanos(), TimeUnit.NANOSECONDS)) {
            throw new TimeoutException("Timed out waiting for in-flight read operations");
        }
        permits.release(maxConcurrency);
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            awaitCompletion(DEFAULT_SHUTDOWN_TIMEOUT);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            virtualThreads.shutdownNow();
            throw new IllegalStateException("Interrupted while waiting for read operations to finish", error);
        } catch (TimeoutException error) {
            virtualThreads.shutdownNow();
            throw new IllegalStateException(error.getMessage(), error);
        }
        virtualThreads.shutdown();
        try {
            if (!virtualThreads.awaitTermination(DEFAULT_SHUTDOWN_TIMEOUT.toNanos(), TimeUnit.NANOSECONDS)) {
                virtualThreads.shutdownNow();
                throw new IllegalStateException("Timed out shutting down virtual read-operation threads");
            }
        } catch (InterruptedException error) {
            virtualThreads.shutdownNow();
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while shutting down virtual read-operation threads", error);
        }
    }

    private void startRequest(ReadOperation operation, long plannedSendNanos, Counters operationCounters) {
        CompletableFuture<?> response;
        try {
            response = invoke(operation);
        } catch (RuntimeException error) {
            complete(operation, plannedSendNanos, operationCounters, error);
            return;
        }
        response.whenComplete((ignored, error) ->
                complete(operation, plannedSendNanos, operationCounters, error));
    }

    private void complete(
            ReadOperation operation,
            long plannedSendNanos,
            Counters operationCounters,
            Throwable error
    ) {
        operationCounters.completed.incrementAndGet();
        long elapsedNanos = Math.max(0, System.nanoTime() - plannedSendNanos);
        long elapsedMicros = Math.max(1, TimeUnit.NANOSECONDS.toMicros(elapsedNanos));
        if (elapsedMicros <= MAX_TRACKED_LATENCY_MICROS) {
            latencies.get(operation).recordValue(elapsedMicros);
        } else {
            operationCounters.failures.incrementAndGet();
            LOGGER.warn("Discarding out-of-range latency for {}: {} microseconds", operation, elapsedMicros);
        }
        if (error != null) {
            operationCounters.failures.incrementAndGet();
            LOGGER.warn("Light-node read operation {} failed", operation, error);
        }
        permits.release();
    }

    private CompletableFuture<?> invoke(ReadOperation operation) {
        return switch (operation) {
            case CHAIN_HEAD -> client.getChainHead().thenApply(head -> {
                dataPool.observeHead(head.height());
                return head;
            });
            case BLOCK_HEADER -> client.getBlockHeader(dataPool.nextHeight());
            case BLOCK -> client.getBlock(dataPool.nextHeight());
            case BALANCE -> client.getBalance(dataPool.nextAddress());
            case NONCE -> client.getNonce(dataPool.nextAddress());
            case TRANSACTION_STATUS -> client.getTransactionStatus(dataPool.nextTransactionId());
            case PROOF_TASK_STATUS -> client.getProofTaskStatus(dataPool.nextProofTaskId());
        };
    }

    private static final class Counters {
        private final AtomicLong completed = new AtomicLong();
        private final AtomicLong failures = new AtomicLong();
        private final AtomicLong rejected = new AtomicLong();
    }

    public record OperationMetrics(
            long completed,
            long failures,
            long rejected,
            long p50Micros,
            long p95Micros,
            long p99Micros
    ) {
    }
}
