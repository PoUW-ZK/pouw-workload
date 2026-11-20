package com.pouw.workload;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

final class FullNodeClient {
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final List<URI> baseUris;
    private final Duration requestTimeout;
    private final AtomicInteger nextNode = new AtomicInteger();

    FullNodeClient(List<String> baseUrls, Duration requestTimeout) {
        this(HttpClient.newHttpClient(), new ObjectMapper(), baseUrls, requestTimeout);
    }

    FullNodeClient(
            HttpClient httpClient,
            ObjectMapper objectMapper,
            List<String> baseUrls,
            Duration requestTimeout
    ) {
        if (baseUrls == null || baseUrls.isEmpty()) {
            throw new IllegalArgumentException("At least one full-node URL is required");
        }
        if (baseUrls.stream().anyMatch(url -> url == null || url.isBlank())) {
            throw new IllegalArgumentException("Full-node URLs must not be blank");
        }
        if (requestTimeout == null || requestTimeout.isNegative() || requestTimeout.isZero()) {
            throw new IllegalArgumentException("requestTimeout must be positive");
        }
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.baseUris = baseUrls.stream()
                .map(FullNodeClient::normalizeBaseUrl)
                .map(URI::create)
                .toList();
        this.requestTimeout = requestTimeout;
    }

    CompletableFuture<Submission> submit(TransactionCodec.SignedTransfer transaction) {
        URI baseUri = baseUris.get(nextNode.getAndUpdate(
                current -> (current + 1) % baseUris.size()));
        HttpRequest request = HttpRequest.newBuilder(baseUri.resolve("v1/txs"))
                .timeout(requestTimeout)
                .header("Content-Type", "application/octet-stream")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(transaction.bytes()))
                .build();
        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenCompose(response -> {
                    if (response.statusCode() == 202) {
                        try {
                            Submission submission = objectMapper.readValue(response.body(), Submission.class);
                            if (submission.txid() == null || submission.txid().isBlank()) {
                                return CompletableFuture.failedFuture(
                                        new IOException("Full-node response did not contain a txid"));
                            }
                            return CompletableFuture.completedFuture(submission);
                        } catch (IOException error) {
                            return CompletableFuture.failedFuture(error);
                        }
                    }
                    return CompletableFuture.failedFuture(new LightNodeClient.NodeApiException(
                            response.statusCode(), response.body(), errorCode(response.body())));
                });
    }

    private String errorCode(String responseBody) {
        try {
            return objectMapper.readTree(responseBody).path("error").path("code").asText(null);
        } catch (IOException error) {
            return null;
        }
    }

    private static String normalizeBaseUrl(String baseUrl) {
        return baseUrl.endsWith("/") ? baseUrl : baseUrl + "/";
    }

    record Submission(String txid) {
    }
}
