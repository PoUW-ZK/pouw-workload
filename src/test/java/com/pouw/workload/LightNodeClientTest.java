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
        server.stubFor(get("/v1/light/head").willReturn(okJson("""
                {"chain_id":4242,"id":"blk:abc","height":100}
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
        server.stubFor(get("/v1/light/proof-jobs/task-1").willReturn(okJson("""
                {"txid":"task-1","status":"included","height":100,
                 "proof_hash":"proof:123","block":"blk:abc"}
                """)));

        assertEquals(100, client.getChainHead().get(2, TimeUnit.SECONDS).height());
        assertEquals(4242, client.getChainHead().get(2, TimeUnit.SECONDS).chainId());
        assertEquals(100, client.getBlockHeader(100).get(2, TimeUnit.SECONDS).height());
        assertEquals(0, client.getBlock(100).get(2, TimeUnit.SECONDS).txs().size());
        assertEquals(5000, client.getBalance("alice").get(2, TimeUnit.SECONDS).balance());
        assertEquals(3, client.getNonce("alice").get(2, TimeUnit.SECONDS).nonce());
        assertEquals("included",
                client.getTransactionStatus("tx-1").get(2, TimeUnit.SECONDS).status());
        assertEquals("proof:123",
                client.getProofTaskStatus("task-1").get(2, TimeUnit.SECONDS).proofHash());
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
    void keepsColonsInTypedNodeIds() throws Exception {
        String address = "addr:" + "11".repeat(32);
        String txid = "tx:" + "22".repeat(32);
        server.stubFor(get("/v1/accounts/" + address).willReturn(okJson("""
                {"address":"addr:1111111111111111111111111111111111111111111111111111111111111111",
                 "balance":5000,"nonce":3}
                """)));
        server.stubFor(get("/v1/txs/" + txid).willReturn(okJson("""
                {"status":"included","height":100}
                """)));

        assertEquals(3, client.getNonce(address).get(2, TimeUnit.SECONDS).nonce());
        assertEquals("included",
                client.getTransactionStatus(txid).get(2, TimeUnit.SECONDS).status());
    }

    @Test
    void sendAsyncDoesNotBlockCallerDuringDelayedResponse() throws Exception {
        server.stubFor(get("/v1/light/head").willReturn(
                aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withFixedDelay(300)
                        .withBody("{\"chain_id\":4242,\"id\":\"blk:abc\",\"height\":100}")
        ));

        long start = System.nanoTime();
        CompletableFuture<LightNodeClient.ChainHead> result = client.getChainHead();
        long elapsedMillis = Duration.ofNanos(System.nanoTime() - start).toMillis();

        assertFalse(result.isDone());
        assertTrue(elapsedMillis < 200, "sendAsync call blocked for " + elapsedMillis + " ms");
        assertEquals(100, result.get(2, TimeUnit.SECONDS).height());
    }
}
