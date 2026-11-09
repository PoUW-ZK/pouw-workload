package com.pouw.workload;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.junit.jupiter.api.Assertions.*;

class LightNodeClientTest {
    private WireMockServer server;
    private LightNodeClient client;

    @BeforeEach
    void setUp() {
        server = new WireMockServer(wireMockConfig().dynamicPort());
        server.start();
        client = new LightNodeClient(
                server.baseUrl(),
                new com.fasterxml.jackson.databind.ObjectMapper(),
                Duration.ofSeconds(2),
                0
        );
    }

    @AfterEach
    void tearDown() {
        server.stop();
    }

    @Test
    void readsAllSupportedEndpoints() throws Exception {
        server.stubFor(get("/v1/blocks/head").willReturn(okJson("""
                {"id":"blk:abc","header":{"height":100}}
                """)));
        server.stubFor(get("/v1/blocks/100/header").willReturn(okJson("""
                {"id":"blk:abc","header":{"height":100}}
                """)));
        server.stubFor(get("/v1/blocks/100").willReturn(okJson("""
                {"id":"blk:abc","header":{"height":100},"txs":[],"proof_txs":[]}
                """)));
        server.stubFor(get("/v1/accounts/alice").willReturn(okJson("""
                {"address":"alice","balance":5000,"nonce":3}
                """)));
        server.stubFor(get("/v1/txs/tx-1").willReturn(okJson("""
                {"status":"included","height":100}
                """)));
        server.stubFor(get("/v1/txs/task-1").willReturn(okJson("""
                {"status":"included","height":100,"proof":"MTIz"}
                """)));

        assertEquals(100, client.getChainHead().get(2, TimeUnit.SECONDS).height());
        assertEquals(100, client.getBlockHeader(100).get(2, TimeUnit.SECONDS).height());
        assertEquals(0, client.getBlock(100).get(2, TimeUnit.SECONDS).txs().size());
        assertEquals(5000, client.getBalance("alice").get(2, TimeUnit.SECONDS).balance());
        assertEquals(3, client.getNonce("alice").get(2, TimeUnit.SECONDS).nonce());
        assertEquals("included",
                client.getTransactionStatus("tx-1").get(2, TimeUnit.SECONDS).status());
        assertEquals("MTIz",
                client.getProofTaskStatus("task-1").get(2, TimeUnit.SECONDS).proof());
    }

    @Test
    void reportsNotFoundResponse() {
        server.stubFor(get("/v1/blocks/999").willReturn(notFound()));

        CompletableFuture<LightNodeClient.Block> result = client.getBlock(999);

        CompletionException completionException = assertThrows(
                CompletionException.class,
                () -> result.join()
        );
        LightNodeClient.NodeApiException error =
                assertInstanceOf(LightNodeClient.NodeApiException.class, completionException.getCause());
        assertEquals(404, error.statusCode());
    }

    @Test
    void sendAsyncDoesNotBlockCallerDuringDelayedResponse() throws Exception {
        server.stubFor(get("/v1/blocks/head").willReturn(
                aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withFixedDelay(300)
                        .withBody("{\"id\":\"blk:abc\",\"header\":{\"height\":100}}")
        ));

        long start = System.nanoTime();
        CompletableFuture<LightNodeClient.ChainHead> result = client.getChainHead();
        long elapsedMillis = Duration.ofNanos(System.nanoTime() - start).toMillis();

        assertFalse(result.isDone());
        assertTrue(elapsedMillis < 200, "sendAsync call blocked for " + elapsedMillis + " ms");
        assertEquals(100, result.get(2, TimeUnit.SECONDS).height());
    }
}
