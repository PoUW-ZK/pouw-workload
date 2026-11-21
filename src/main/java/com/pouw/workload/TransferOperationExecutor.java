package com.pouw.workload;

import org.HdrHistogram.ConcurrentHistogram;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

final class TransferOperationExecutor implements AutoCloseable {
    static final String GENESIS_ADDRESS =
            "addr:8a88e3dd7409f195fd52db2d3cba5d72ca6709bf1d94121bf3748801b40f6f5c";
    private static final Logger LOGGER = LoggerFactory.getLogger(TransferOperationExecutor.class);
    private static final long MAX_TRACKED_LATENCY_MICROS = TimeUnit.DAYS.toMicros(30);
    private static final Duration DEFAULT_INCLUSION_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration DEFAULT_POLL_INTERVAL = Duration.ofMillis(1_000);
    private static final Duration MAX_POLL_INTERVAL = Duration.ofSeconds(5);
    private static final int HISTOGRAM_SIGNIFICANT_DIGITS = 3;

    private final FullNodeClient fullNodeClient;
    private final LightNodeClient lightNodeClient;
    private final WorkloadScenario scenario;
    private final WorkloadMetrics metrics;
    private final ExecutorService virtualThreads;
    private final Semaphore permits;
    private final Duration requestTimeout;
    private final Duration inclusionTimeout;
    private final Duration pollInterval;
    private final AtomicInteger nextWallet = new AtomicInteger();
    private final AtomicLong completed = new AtomicLong();
    private final AtomicLong accepted = new AtomicLong();
    private final AtomicLong included = new AtomicLong();
    private final AtomicLong failures = new AtomicLong();
    private final AtomicInteger pendingInclusions = new AtomicInteger();
    private final Map<String, AtomicLong> rejected = new ConcurrentHashMap<>();
    private final ConcurrentHistogram latencies = new ConcurrentHistogram(
            1, MAX_TRACKED_LATENCY_MICROS, HISTOGRAM_SIGNIFICANT_DIGITS);
    private List<WalletState> wallets = List.of();
    private volatile boolean stopped;
    private volatile boolean closed;
    private long chainId;

    TransferOperationExecutor(
            FullNodeClient fullNodeClient,
            LightNodeClient lightNodeClient,
            WorkloadScenario scenario,
            WorkloadMetrics metrics,
            Duration requestTimeout
    ) {
        this(fullNodeClient, lightNodeClient, scenario, metrics, requestTimeout,
                DEFAULT_INCLUSION_TIMEOUT, DEFAULT_POLL_INTERVAL,
                Executors.newVirtualThreadPerTaskExecutor());
    }

    TransferOperationExecutor(
            FullNodeClient fullNodeClient,
            LightNodeClient lightNodeClient,
            WorkloadScenario scenario,
            WorkloadMetrics metrics,
            Duration requestTimeout,
            Duration inclusionTimeout,
            Duration pollInterval,
            ExecutorService virtualThreads
    ) {
        if (requestTimeout == null || requestTimeout.isNegative() || requestTimeout.isZero()
                || inclusionTimeout == null || inclusionTimeout.isNegative() || inclusionTimeout.isZero()
                || pollInterval == null || pollInterval.isNegative() || pollInterval.isZero()) {
            throw new IllegalArgumentException("operation timeouts must be positive");
        }
        this.fullNodeClient = fullNodeClient;
        this.lightNodeClient = lightNodeClient;
        this.scenario = scenario;
        this.metrics = metrics;
        this.requestTimeout = requestTimeout;
        this.inclusionTimeout = inclusionTimeout;
        this.pollInterval = pollInterval;
        this.virtualThreads = virtualThreads;
        this.permits = new Semaphore(scenario.maxConcurrency());
    }

    void initialize(long chainId) throws InterruptedException, ExecutionException, TimeoutException {
        if (chainId < 0 || chainId > 0xffff_ffffL) {
            throw new IllegalArgumentException("chainId must fit in u32");
        }
        this.chainId = chainId;
        WalletState fundingWallet = new WalletState(
                Wallet.fromSeed(filledSeed(), GENESIS_ADDRESS),
                lightNodeClient.getAccount(GENESIS_ADDRESS).get(
                        requestTimeout.toNanos(), TimeUnit.NANOSECONDS).nonce()
        );

        List<WalletState> generatedWallets = new ArrayList<>(scenario.walletCount());
        for (int wallet = 0; wallet < scenario.walletCount(); wallet++) {
            checkRunning();
            Wallet generatedWallet = Wallet.generate();
            sendAndWait(
                    fundingWallet,
                    generatedWallet.address(),
                    scenario.walletFundingAmount(),
                    scenario.transferFee(),
                    false
            );
            long nonce = lightNodeClient.getAccount(generatedWallet.address()).get(
                    requestTimeout.toNanos(), TimeUnit.NANOSECONDS).nonce();
            generatedWallets.add(new WalletState(generatedWallet, nonce));
        }
        wallets = List.copyOf(generatedWallets);
    }

    boolean submitNext(long plannedSendNanos) {
        if (closed) {
            throw new IllegalStateException("TransferOperationExecutor is closed");
        }
        if (wallets.isEmpty()) {
            throw new IllegalStateException("TransferOperationExecutor is not initialized");
        }
        if (!permits.tryAcquire()) {
            rejected("concurrency");
            return false;
        }
        try {
            virtualThreads.execute(() -> executeTransfer(plannedSendNanos));
            return true;
        } catch (RuntimeException | Error error) {
            permits.release();
            rejected("executor");
            if (!closed || !(error instanceof RejectedExecutionException)) {
                throw error;
            }
            return false;
        }
    }

    TransferMetrics snapshot() {
        return new TransferMetrics(
                completed.get(),
                accepted.get(),
                included.get(),
                failures.get(),
                rejected.entrySet().stream()
                        .collect(java.util.stream.Collectors.toUnmodifiableMap(
                                Map.Entry::getKey,
                                entry -> entry.getValue().get()
                        )),
                latencies.getValueAtPercentile(50),
                latencies.getValueAtPercentile(95),
                latencies.getValueAtPercentile(99)
        );
    }

    void awaitCompletion(Duration timeout) throws InterruptedException, TimeoutException {
        if (timeout == null || timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        long deadline = System.nanoTime() + timeout.toNanos();
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0 || !permits.tryAcquire(
                scenario.maxConcurrency(), remaining, TimeUnit.NANOSECONDS)) {
            throw new TimeoutException("Timed out waiting for in-flight transfers");
        }
        permits.release(scenario.maxConcurrency());
        while (pendingInclusions.get() > 0 && System.nanoTime() < deadline) {
            Thread.sleep(Math.min(10, Math.max(1, (deadline - System.nanoTime()) / 1_000_000)));
        }
        if (pendingInclusions.get() > 0) {
            throw new TimeoutException("Timed out waiting for transfer inclusion tracking");
        }
    }

    Duration completionTimeout() {
        return inclusionTimeout.plus(requestTimeout).plus(MAX_POLL_INTERVAL);
    }

    void stop() {
        stopped = true;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            awaitCompletion(completionTimeout());
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            virtualThreads.shutdownNow();
            throw new IllegalStateException("Interrupted while waiting for transfers", error);
        } catch (TimeoutException error) {
            virtualThreads.shutdownNow();
            throw new IllegalStateException(error.getMessage(), error);
        }
        virtualThreads.shutdown();
        try {
            if (!virtualThreads.awaitTermination(10, TimeUnit.SECONDS)) {
                virtualThreads.shutdownNow();
                throw new IllegalStateException("Timed out shutting down transfer threads");
            }
        } catch (InterruptedException error) {
            virtualThreads.shutdownNow();
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while shutting down transfer threads", error);
        }
    }

    private void executeTransfer(long plannedSendNanos) {
        if (metrics != null) {
            metrics.transactionStarted();
        }
        try {
            int index = nextWallet.getAndUpdate(current -> (current + 1) % wallets.size());
            WalletState sender = wallets.get(index);
            WalletState recipient = wallets.get((index + 1) % wallets.size());
            AcceptedTransfer acceptedTransfer = submit(
                    sender,
                    recipient.wallet().address(),
                    scenario.transferAmount(),
                    scenario.transferFee(),
                    true
            );
            trackInclusion(acceptedTransfer);
        } catch (CancellationException error) {
            // A requested stop is represented by the scenario lifecycle, not a transfer failure.
        } catch (Exception error) {
            failures.incrementAndGet();
            if (metrics != null) {
                metrics.transactionFailed();
            }
            LOGGER.warn("Transfer failed", error);
        } finally {
            completed.incrementAndGet();
            if (metrics != null) {
                metrics.transactionCompleted();
            }
            permits.release();
        }
    }

    private void sendAndWait(
            WalletState sender,
            String recipient,
            long amount,
            long fee,
            boolean recordMetrics
    ) throws InterruptedException, ExecutionException, TimeoutException {
        AcceptedTransfer acceptedTransfer = submit(sender, recipient, amount, fee, recordMetrics);
        awaitInclusion(acceptedTransfer.txid());
    }

    private AcceptedTransfer submit(
            WalletState sender,
            String recipient,
            long amount,
            long fee,
            boolean recordMetrics
    ) throws InterruptedException, ExecutionException, TimeoutException {
        sender.lock.lockInterruptibly();
        try {
            for (int attempt = 0; attempt < 2; attempt++) {
                checkRunning();
                long nonce = sender.nonce;
                TransactionCodec.SignedTransfer transaction = TransactionCodec.signTransfer(
                        chainId, sender.wallet(), nonce, fee, recipient, amount);
                if (recordMetrics && metrics != null) {
                    metrics.transactionSubmitted();
                }
                long sentAtNanos = System.nanoTime();
                try {
                    FullNodeClient.Submission submission = fullNodeClient.submit(transaction)
                            .get(requestTimeout.toNanos(), TimeUnit.NANOSECONDS);
                    return accepted(sender, nonce, submission.txid(), sentAtNanos, recordMetrics);
                } catch (ExecutionException error) {
                    Throwable cause = unwrap(error);
                    if (cause instanceof LightNodeClient.NodeApiException nodeError
                            && "bad_nonce".equals(nodeError.errorCode())
                            && attempt == 0) {
                        if (transaction.txid().equals(sender.uncertainTxid)
                                && confirmAccepted(transaction.txid())) {
                            return accepted(sender, nonce, transaction.txid(), sentAtNanos, recordMetrics);
                        }
                        if (recordMetrics) {
                            rejected("bad_nonce");
                        }
                        long confirmedNonce = lightNodeClient.getAccount(sender.wallet().address()).get(
                                requestTimeout.toNanos(), TimeUnit.NANOSECONDS).nonce();
                        sender.nonce = Math.max(sender.nonce, confirmedNonce);
                        continue;
                    }
                    if (recordMetrics) {
                        if (cause instanceof LightNodeClient.NodeApiException nodeError) {
                            rejected(nodeError.errorCode());
                        } else {
                            rejected("transport");
                        }
                    }
                    throw error;
                } catch (TimeoutException error) {
                    if (confirmAccepted(transaction.txid())) {
                        return accepted(sender, nonce, transaction.txid(), sentAtNanos, recordMetrics);
                    }
                    if (attempt == 0) {
                        if (recordMetrics && metrics != null) {
                            metrics.transactionSubmitted();
                        }
                        try {
                            FullNodeClient.Submission submission = fullNodeClient.submit(transaction)
                                    .get(requestTimeout.toNanos(), TimeUnit.NANOSECONDS);
                            return accepted(
                                    sender, nonce, submission.txid(), sentAtNanos, recordMetrics);
                        } catch (ExecutionException retryError) {
                            Throwable retryCause = unwrap(retryError);
                            if (retryCause instanceof LightNodeClient.NodeApiException nodeError
                                    && "bad_nonce".equals(nodeError.errorCode())
                                    && confirmAccepted(transaction.txid())) {
                                return accepted(
                                        sender, nonce, transaction.txid(), sentAtNanos, recordMetrics);
                            }
                            if (recordMetrics) {
                                rejected(rejectionReason(retryCause));
                            }
                            sender.uncertainTxid = transaction.txid();
                            throw retryError;
                        } catch (TimeoutException retryTimeout) {
                            if (confirmAccepted(transaction.txid())) {
                                return accepted(
                                        sender, nonce, transaction.txid(), sentAtNanos, recordMetrics);
                            }
                            if (recordMetrics) {
                                rejected("transport");
                            }
                            sender.uncertainTxid = transaction.txid();
                            throw retryTimeout;
                        }
                    }
                    if (recordMetrics) {
                        rejected("transport");
                    }
                    sender.uncertainTxid = transaction.txid();
                    throw error;
                }
            }
        } finally {
            sender.lock.unlock();
        }
        throw new IllegalStateException("Transfer nonce retry did not complete");
    }

    private AcceptedTransfer accepted(
            WalletState sender,
            long nonce,
            String txid,
            long sentAtNanos,
            boolean recordMetrics
    ) {
        sender.nonce = Math.addExact(nonce, 1);
        sender.uncertainTxid = null;
        if (recordMetrics) {
            accepted.incrementAndGet();
            if (metrics != null) {
                metrics.transactionAccepted();
            }
        }
        return new AcceptedTransfer(txid, sentAtNanos);
    }

    private boolean confirmAccepted(String txid)
            throws InterruptedException {
        try {
            String status = lightNodeClient.getTransactionStatus(txid)
                    .get(requestTimeout.toNanos(), TimeUnit.NANOSECONDS).status();
            return "pending".equals(status) || "included".equals(status);
        } catch (ExecutionException error) {
            Throwable cause = unwrap(error);
            if (cause instanceof LightNodeClient.NodeApiException nodeError
                    && nodeError.statusCode() == 404) {
                return false;
            }
            LOGGER.warn("Unable to confirm ambiguous transaction " + txid, cause);
            return false;
        } catch (TimeoutException error) {
            LOGGER.warn("Timed out confirming ambiguous transaction " + txid);
            return false;
        }
    }

    private static String rejectionReason(Throwable error) {
        return error instanceof LightNodeClient.NodeApiException nodeError
                ? nodeError.errorCode()
                : "transport";
    }

    private void trackInclusion(AcceptedTransfer transfer) {
        pendingInclusions.incrementAndGet();
        try {
            virtualThreads.execute(() -> {
                try {
                    awaitInclusion(transfer.txid());
                    included.incrementAndGet();
                    long elapsedNanos = Math.max(0, System.nanoTime() - transfer.sentAtNanos());
                    long elapsedMicros = Math.max(1, TimeUnit.NANOSECONDS.toMicros(elapsedNanos));
                    if (elapsedMicros <= MAX_TRACKED_LATENCY_MICROS) {
                        latencies.recordValue(elapsedMicros);
                    } else {
                        failures.incrementAndGet();
                        LOGGER.warn("Discarding out-of-range transfer latency: {} microseconds",
                                elapsedMicros);
                    }
                    if (metrics != null) {
                        metrics.transactionIncluded(elapsedNanos);
                    }
                } catch (CancellationException error) {
                    // A requested stop is represented by the scenario lifecycle.
                } catch (Exception error) {
                    failures.incrementAndGet();
                    if (metrics != null) {
                        metrics.transactionFailed();
                    }
                    LOGGER.warn("Transfer inclusion tracking failed for " + transfer.txid(), error);
                } finally {
                    pendingInclusions.decrementAndGet();
                }
            });
        } catch (RuntimeException | Error error) {
            pendingInclusions.decrementAndGet();
            throw error;
        }
    }

    private void awaitInclusion(String txid)
            throws InterruptedException, ExecutionException, TimeoutException {
        long deadline = System.nanoTime() + inclusionTimeout.toNanos();
        long acceptedAt = System.nanoTime();
        Duration nextPoll = pollInterval;
        while (System.nanoTime() < deadline) {
            checkRunning();
            LightNodeClient.TransactionStatus status = lightNodeClient.getTransactionStatus(txid)
                    .get(requestTimeout.toNanos(), TimeUnit.NANOSECONDS);
            if ("included".equals(status.status())) {
                return;
            }
            if ("failed".equals(status.status())) {
                throw new IllegalStateException("Transaction " + txid + " failed before inclusion");
            }
            Thread.sleep(nextPoll);
            nextPoll = Duration.ofNanos(Math.min(
                    Math.multiplyExact(nextPoll.toNanos(), 2),
                    MAX_POLL_INTERVAL.toNanos()
            ));
        }
        throw new TimeoutException("Timed out waiting for " + txid + " after "
                + Duration.ofNanos(System.nanoTime() - acceptedAt) + " for inclusion");
    }

    private void rejected(String reason) {
        String normalized = reason == null || reason.isBlank() ? "unknown" : reason;
        rejected.computeIfAbsent(normalized, ignored -> new AtomicLong()).incrementAndGet();
        if (metrics != null) {
            metrics.transactionRejected(normalized);
        }
    }

    private void checkRunning() {
        if (stopped || closed) {
            throw new CancellationException("Transfer scenario stopped");
        }
    }

    private static byte[] filledSeed() {
        byte[] seed = new byte[32];
        Arrays.fill(seed, (byte) 1);
        return seed;
    }

    private static Throwable unwrap(ExecutionException error) {
        Throwable cause = error.getCause();
        if (cause instanceof CompletionException && cause.getCause() != null) {
            return cause.getCause();
        }
        return cause == null ? error : cause;
    }

    record TransferMetrics(
            long completed,
            long accepted,
            long included,
            long failures,
            Map<String, Long> rejected,
            long p50Micros,
            long p95Micros,
            long p99Micros
    ) {
        static TransferMetrics empty() {
            return new TransferMetrics(0, 0, 0, 0, Map.of(), 0, 0, 0);
        }
    }

    private record AcceptedTransfer(String txid, long sentAtNanos) {
    }

    private static final class WalletState {
        private final Wallet wallet;
        private final ReentrantLock lock = new ReentrantLock();
        private long nonce;
        private String uncertainTxid;

        private WalletState(Wallet wallet, long nonce) {
            this.wallet = wallet;
            this.nonce = nonce;
        }

        private Wallet wallet() {
            return wallet;
        }
    }
}
