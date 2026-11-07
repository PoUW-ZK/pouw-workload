package com.pouw.workload;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
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
        return get("/v1/blocks/head", ChainHead.class);
    }

    public CompletableFuture<ChainHead> getBlockHeader(long height) {
        return get("/v1/blocks/" + height + "/header", ChainHead.class);
    }

    public CompletableFuture<Block> getBlock(long height) {
        return get("/v1/blocks/" + height, Block.class);
    }

    public CompletableFuture<Balance> getBalance(String address) {
        return getAccount(address).thenApply(account -> new Balance(account.balance()));
    }

    public CompletableFuture<Nonce> getNonce(String address) {
        return getAccount(address).thenApply(account -> new Nonce(account.nonce()));
    }

    public CompletableFuture<TransactionStatus> getTransactionStatus(String transactionId) {
        return get("/v1/txs/" + encodePathSegment(transactionId), TransactionStatus.class);
    }

    public CompletableFuture<ProofTaskStatus> getProofTaskStatus(String transactionId) {
        return get("/v1/txs/" + encodePathSegment(transactionId), ProofTaskStatus.class);
    }

    private CompletableFuture<AccountView> getAccount(String address) {
        return get("/v1/accounts/" + encodePathSegment(address), AccountView.class);
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
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ChainHead(String id, BlockHeader header) {
        public long height() {
            return header.height();
        }

        public String hash() {
            return id;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record BlockHeader(long height) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Block(
            String id,
            BlockHeader header,
            List<JsonNode> txs,
            @JsonProperty("proof_txs") List<JsonNode> proofTxs
    ) {
        public long height() {
            return header.height();
        }
    }

    public record Balance(long balance) {
    }

    public record Nonce(long nonce) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AccountView(String address, long balance, long nonce) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TransactionStatus(
            String status,
            @JsonProperty("height") Long blockHeight
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ProofTaskStatus(
            String status,
            @JsonProperty("height") Long blockHeight,
            String proof
    ) {
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
