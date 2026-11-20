package com.pouw.workload;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.MeterRegistry;

import java.util.EnumMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class WorkloadMetrics {
    private final EnumMap<ReadOperation, Counter> sent = new EnumMap<>(ReadOperation.class);
    private final EnumMap<ReadOperation, Counter> successful = new EnumMap<>(ReadOperation.class);
    private final EnumMap<ReadOperation, Counter> errors = new EnumMap<>(ReadOperation.class);
    private final EnumMap<ReadOperation, Counter> rejected = new EnumMap<>(ReadOperation.class);
    private final EnumMap<ReadOperation, Timer> latency = new EnumMap<>(ReadOperation.class);
    private final AtomicInteger inFlight = new AtomicInteger();
    private final MeterRegistry registry;
    private final Counter transactionSubmitted;
    private final Counter transactionAccepted;
    private final Counter transactionIncluded;
    private final Counter transactionErrors;
    private final Timer transactionInclusionLatency;
    private final AtomicInteger transactionInFlight = new AtomicInteger();
    private final ConcurrentHashMap<String, Counter> transactionRejected = new ConcurrentHashMap<>();

    public WorkloadMetrics(MeterRegistry registry) {
        this.registry = registry;
        Gauge.builder("workload.reads.inflight", inFlight, AtomicInteger::get).register(registry);
        for (ReadOperation operation : ReadOperation.values()) {
            String tag = operation.name().toLowerCase(java.util.Locale.ROOT);
            sent.put(operation, Counter.builder("workload.reads.sent").tag("operation", tag).register(registry));
            successful.put(operation, Counter.builder("workload.reads.successful").tag("operation", tag).register(registry));
            errors.put(operation, Counter.builder("workload.reads.errors").tag("operation", tag).register(registry));
            rejected.put(operation, Counter.builder("workload.reads.rejected").tag("operation", tag).register(registry));
            latency.put(operation, Timer.builder("workload.reads.latency")
                    .tag("operation", tag).publishPercentileHistogram().register(registry));
        }
        transactionSubmitted = Counter.builder("workload.transactions.submitted").register(registry);
        transactionAccepted = Counter.builder("workload.transactions.accepted").register(registry);
        transactionIncluded = Counter.builder("workload.transactions.included").register(registry);
        transactionErrors = Counter.builder("workload.transactions.errors").register(registry);
        transactionInclusionLatency = Timer.builder("workload.transactions.inclusion.latency")
                .publishPercentileHistogram().register(registry);
        Gauge.builder("workload.transactions.inflight", transactionInFlight, AtomicInteger::get)
                .register(registry);
    }

    void sent(ReadOperation operation) {
        sent.get(operation).increment();
        inFlight.incrementAndGet();
    }

    void rejected(ReadOperation operation) {
        rejected.get(operation).increment();
    }

    void completed(ReadOperation operation, long nanos, boolean failed) {
        (failed ? errors : successful).get(operation).increment();
        latency.get(operation).record(nanos, TimeUnit.NANOSECONDS);
        inFlight.decrementAndGet();
    }

    void transactionStarted() {
        transactionInFlight.incrementAndGet();
    }

    void transactionSubmitted() {
        transactionSubmitted.increment();
    }

    void transactionAccepted() {
        transactionAccepted.increment();
    }

    void transactionIncluded(long nanos) {
        transactionIncluded.increment();
        transactionInclusionLatency.record(nanos, TimeUnit.NANOSECONDS);
    }

    void transactionRejected(String reason) {
        String tag = reason == null || reason.isBlank() ? "unknown" : reason;
        transactionRejected.computeIfAbsent(tag,
                ignored -> Counter.builder("workload.transactions.rejected")
                        .tag("reason", tag).register(registry)).increment();
    }

    void transactionFailed() {
        transactionErrors.increment();
    }

    void transactionCompleted() {
        transactionInFlight.decrementAndGet();
    }
}
