package com.pouw.workload;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongPredicate;

public final class OpenLoopScheduler implements AutoCloseable {
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "workload-open-loop-scheduler");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicLong scheduledArrivals = new AtomicLong();
    private final AtomicReference<ScheduledFuture<?>> pending = new AtomicReference<>();
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean stopped = new AtomicBoolean();
    private final Object lifecycleLock = new Object();
    private volatile CompletableFuture<ScheduleResult> runResult;
    private volatile long runStartNanos;
    private volatile long runDurationNanos;

    public CompletableFuture<ScheduleResult> start(
            Duration duration,
            ArrivalModel arrivalModel,
            LongPredicate dispatcher
    ) {
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException("duration must be positive");
        }
        if (arrivalModel == null || dispatcher == null) {
            throw new IllegalArgumentException("arrivalModel and dispatcher are required");
        }
        if (stopped.get() || !started.compareAndSet(false, true)) {
            throw new IllegalStateException("OpenLoopScheduler can run only once");
        }

        long durationNanos = duration.toNanos();
        long startNanos = System.nanoTime();
        long endNanos = Math.addExact(startNanos, durationNanos);
        CompletableFuture<ScheduleResult> result = new CompletableFuture<>();
        runResult = result;
        runStartNanos = startNanos;
        runDurationNanos = durationNanos;
        scheduleAt(startNanos, startNanos, endNanos, durationNanos, arrivalModel, dispatcher, result);
        return result;
    }

    public void stop() {
        synchronized (lifecycleLock) {
            if (!stopped.compareAndSet(false, true)) {
                return;
            }
            ScheduledFuture<?> scheduled = pending.getAndSet(null);
            if (scheduled != null) {
                scheduled.cancel(false);
            }
            scheduler.shutdownNow();
            CompletableFuture<ScheduleResult> result = runResult;
            if (result != null) {
                long elapsedNanos = Math.max(0, System.nanoTime() - runStartNanos);
                result.complete(new ScheduleResult(scheduledArrivals.get(),
                        Math.min(elapsedNanos, runDurationNanos)));
            }
        }
    }

    @Override
    public void close() {
        stop();
    }

    private void scheduleAt(
            long plannedNanos,
            long startNanos,
            long endNanos,
            long durationNanos,
            ArrivalModel arrivalModel,
            LongPredicate dispatcher,
            CompletableFuture<ScheduleResult> result
    ) {
        if (stopped.get()) {
            result.complete(new ScheduleResult(scheduledArrivals.get(), durationNanos));
            return;
        }
        long targetNanos = Math.min(plannedNanos, endNanos);
        long delayNanos = Math.max(0, targetNanos - System.nanoTime());
        ScheduledFuture<?> scheduled = scheduler.schedule(() -> {
            try {
                synchronized (lifecycleLock) {
                    if (stopped.get()) {
                        result.complete(new ScheduleResult(scheduledArrivals.get(), durationNanos));
                        return;
                    }
                    if (plannedNanos >= endNanos) {
                        result.complete(new ScheduleResult(scheduledArrivals.get(), durationNanos));
                        return;
                    }
                    scheduledArrivals.incrementAndGet();
                    dispatcher.test(plannedNanos);
                }
                long nextPlanned = Math.addExact(
                        plannedNanos,
                        arrivalModel.nextDelayNanos(plannedNanos - startNanos)
                );
                scheduleAt(nextPlanned, startNanos, endNanos, durationNanos, arrivalModel, dispatcher, result);
            } catch (RuntimeException error) {
                result.completeExceptionally(error);
                stop();
            }
        }, delayNanos, TimeUnit.NANOSECONDS);
        pending.set(scheduled);
    }

    public record ScheduleResult(long scheduledArrivals, long durationNanos) {
    }
}
