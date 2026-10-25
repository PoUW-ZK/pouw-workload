package com.pouw.workload;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

public final class LightNodeClient {
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_2)
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private final ObjectMapper objectMapper;
    private final URI baseUri;
    private final Duration requestTimeout;
    private final int maxRetries;

    public LightNodeClient(String baseUrl) {
        this(baseUrl, new ObjectMapper(), Duration.ofSeconds(10), 2);
    }

    public LightNodeClient(String baseUrl, Duration requestTimeout) {
        this(baseUrl, new ObjectMapper(), requestTimeout, 2);
    }

    LightNodeClient(
            String baseUrl,
            ObjectMapper objectMapper,
            Duration requestTimeout,
            int maxRetries
    ) {
        if (maxRetries < 0) {
            throw new IllegalArgumentException("maxRetries must not be negative");
        }
        this.baseUri = URI.create(normalizeBaseUrl(baseUrl));
        this.objectMapper = objectMapper;
        this.requestTimeout = requestTimeout;
        this.maxRetries = maxRetries;
    }

    public CompletableFuture<ChainHead> getChainHead() {
        return get("/chain/head", ChainHead.class);
    }

    public CompletableFuture<Block> getBlock(long height) {
        return get("/block/" + height, Block.class);
    }

    public CompletableFuture<Balance> getBalance(String address) {
        return get("/account/" + encodePathSegment(address) + "/balance", Balance.class);
    }

    public CompletableFuture<Nonce> getNonce(String address) {
        return get("/account/" + encodePathSegment(address) + "/nonce", Nonce.class);
    }

    public CompletableFuture<TransactionStatus> getTransactionStatus(String transactionId) {
        return get("/tx/" + encodePathSegment(transactionId) + "/status", TransactionStatus.class);
    }

    public CompletableFuture<ProofTaskStatus> getProofTaskStatus(String taskId) {
        return get("/proof-task/" + encodePathSegment(taskId) + "/status", ProofTaskStatus.class);
    }

    private <T> CompletableFuture<T> get(String path, Class<T> responseType) {
        HttpRequest request = HttpRequest.newBuilder(baseUri.resolve(path))
                .timeout(requestTimeout)
                .header("Accept", "application/json")
                .GET()
                .build();
        return sendWithRetry(request, responseType, 0);
    }

    private <T> CompletableFuture<T> sendWithRetry(
            HttpRequest request,
            Class<T> responseType,
            int attempt
    ) {
        return HTTP_CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenCompose(response -> {
                    if (response.statusCode() >= 200 && response.statusCode() < 300) {
                        return decode(response.body(), responseType);
                    }
                    if (isRetryable(response.statusCode()) && attempt < maxRetries) {
                        return sendWithRetry(request, responseType, attempt + 1);
                    }
                    return failedFuture(new NodeApiException(
                            response.statusCode(),
                            response.body()
                    ));
                })
                .exceptionallyCompose(error -> {
                    Throwable cause = unwrap(error);
                    if (isRetryable(cause) && attempt < maxRetries) {
                        return sendWithRetry(request, responseType, attempt + 1);
                    }
                    return failedFuture(cause);
                });
    }

    private <T> CompletableFuture<T> decode(String body, Class<T> responseType) {
        try {
            return CompletableFuture.completedFuture(objectMapper.readValue(body, responseType));
        } catch (IOException error) {
            return failedFuture(error);
        }
    }

    private static boolean isRetryable(int statusCode) {
        return statusCode >= 500 && statusCode <= 599;
    }

    private static boolean isRetryable(Throwable error) {
        return error instanceof IOException
                || error instanceof java.util.concurrent.TimeoutException;
    }

    private static Throwable unwrap(Throwable error) {
        if (error instanceof CompletionException && error.getCause() != null) {
            return error.getCause();
        }
        return error;
    }

    private static <T> CompletableFuture<T> failedFuture(Throwable error) {
        return CompletableFuture.failedFuture(error);
    }

    private static String normalizeBaseUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("baseUrl must not be blank");
        }
        return baseUrl.endsWith("/") ? baseUrl : baseUrl + "/";
    }

    private static String encodePathSegment(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("path segment must not be blank");
        }
        return value;
    }

    public record ChainHead(long height, String hash) {
    }

    public record Block(
            long height,
            String hash,
            String previousHash,
            long timestamp,
            java.util.List<String> txs
    ) {
    }

    public record Balance(long balance) {
    }

    public record Nonce(long nonce) {
    }

    public record TransactionStatus(String status, Long blockHeight) {
    }

    public record ProofTaskStatus(String status, String proof) {
    }

    public static final class NodeApiException extends RuntimeException {
        private final int statusCode;

        public NodeApiException(int statusCode, String responseBody) {
            super("Light node API returned HTTP " + statusCode + ": " + responseBody);
            this.statusCode = statusCode;
        }

        public int statusCode() {
            return statusCode;
        }
    }
}
