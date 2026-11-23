package com.pouw.workload;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletionException;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.binaryEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FullNodeClientTest {
    private WireMockServer firstNode;
    private WireMockServer secondNode;

    @BeforeEach
    void setUp() {
        firstNode = new WireMockServer(wireMockConfig().dynamicPort());
        secondNode = new WireMockServer(wireMockConfig().dynamicPort());
        firstNode.start();
        secondNode.start();
    }

    @AfterEach
    void tearDown() {
        firstNode.stop();
        secondNode.stop();
    }

    @Test
    void submitsCanonicalBytesToFullNodesRoundRobin() throws Exception {
        String txid = "tx:" + "11".repeat(32);
        firstNode.stubFor(post("/v1/txs").willReturn(aResponse().withStatus(202)
                .withHeader("Content-Type", "application/json").withBody("{\"txid\":\"" + txid + "\"}")));
        secondNode.stubFor(post("/v1/txs").willReturn(aResponse().withStatus(202)
                .withHeader("Content-Type", "application/json").withBody("{\"txid\":\"" + txid + "\"}")));
        FullNodeClient client = new FullNodeClient(
                List.of(firstNode.baseUrl(), secondNode.baseUrl()), Duration.ofSeconds(2));
        TransactionCodec.SignedTransfer transaction = transaction();

        assertEquals(new FullNodeClient.Submission(txid, "localhost"),
                client.submit(transaction).get());
        assertEquals(new FullNodeClient.Submission(txid, "localhost"),
                client.submit(transaction).get());

        firstNode.verify(postRequestedFor(urlEqualTo("/v1/txs"))
                .withRequestBody(binaryEqualTo(transaction.bytes())));
        secondNode.verify(postRequestedFor(urlEqualTo("/v1/txs"))
                .withRequestBody(binaryEqualTo(transaction.bytes())));
    }

    @Test
    void exposesTheNodeRejectionReason() {
        firstNode.stubFor(post("/v1/txs").willReturn(aResponse().withStatus(422)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"error\":{\"code\":\"bad_nonce\",\"message\":\"expected 1, got 0\"}}")));
        FullNodeClient client =
                new FullNodeClient(List.of(firstNode.baseUrl()), Duration.ofSeconds(2));

        CompletionException failure = assertThrows(
                CompletionException.class, () -> client.submit(transaction()).join());
        LightNodeClient.NodeApiException nodeError = assertInstanceOf(
                LightNodeClient.NodeApiException.class, failure.getCause());
        assertEquals(422, nodeError.statusCode());
        assertEquals("bad_nonce", nodeError.errorCode());
    }

    private static TransactionCodec.SignedTransfer transaction() {
        byte[] seed = new byte[32];
        Arrays.fill(seed, (byte) 1);
        return TransactionCodec.signTransfer(
                1,
                Wallet.fromSeed(seed, TransferOperationExecutor.GENESIS_ADDRESS),
                0,
                10,
                "addr:" + "22".repeat(32),
                1000);
    }
}
