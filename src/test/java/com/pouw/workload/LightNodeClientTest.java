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
        server.stubFor(get("/chain/head").willReturn(okJson("""
                {"height":100,"hash":"0xabc..."}
                """)));
        server.stubFor(get("/block/100").willReturn(okJson("""
                {"height":100,"hash":"0xabc...","previousHash":"0xdef...",
                 "timestamp":1700000000,"txs":[]}
                """)));
        server.stubFor(get("/account/alice/balance").willReturn(okJson("""
                {"balance":5000}
                """)));
        server.stubFor(get("/account/alice/nonce").willReturn(okJson("""
                {"nonce":3}
                """)));
        server.stubFor(get("/tx/tx-1/status").willReturn(okJson("""
                {"status":"CONFIRMED","blockHeight":100}
                """)));
        server.stubFor(get("/proof-task/task-1/status").willReturn(okJson("""
                {"status":"COMPLETED","proof":"0x123..."}
                """)));

        assertEquals(100, client.getChainHead().get(2, TimeUnit.SECONDS).height());
        assertEquals(0, client.getBlock(100).get(2, TimeUnit.SECONDS).txs().size());
        assertEquals(5000, client.getBalance("alice").get(2, TimeUnit.SECONDS).balance());
        assertEquals(3, client.getNonce("alice").get(2, TimeUnit.SECONDS).nonce());
        assertEquals("CONFIRMED",
                client.getTransactionStatus("tx-1").get(2, TimeUnit.SECONDS).status());
        assertEquals("0x123...",
                client.getProofTaskStatus("task-1").get(2, TimeUnit.SECONDS).proof());
    }

    @Test
    void reportsNotFoundResponse() {
        server.stubFor(get("/block/999").willReturn(notFound()));

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
        server.stubFor(get("/chain/head").willReturn(
                aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withFixedDelay(300)
                        .withBody("{\"height\":100,\"hash\":\"0xabc...\"}")
        ));

        long start = System.nanoTime();
        CompletableFuture<LightNodeClient.ChainHead> result = client.getChainHead();
        long elapsedMillis = Duration.ofNanos(System.nanoTime() - start).toMillis();

        assertFalse(result.isDone());
        assertTrue(elapsedMillis < 200, "sendAsync call blocked for " + elapsedMillis + " ms");
        assertEquals(100, result.get(2, TimeUnit.SECONDS).height());
    }
}
