package com.pouw.workload;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransferOperationExecutorTest {
    private static final String TXID = "tx:" + "aa".repeat(32);
    private WireMockServer server;

    @BeforeEach
    void setUp() {
        server = new WireMockServer(wireMockConfig().dynamicPort());
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop();
    }

    @Test
    void fundsWalletsAndResignsAfterBadNonceWhileRecordingMetrics() throws Exception {
        server.stubFor(get(urlEqualTo(
                "/v1/accounts/addr%3A8a88e3dd7409f195fd52db2d3cba5d72ca6709bf1d94121bf3748801b40f6f5c"))
                .atPriority(1)
                .willReturn(okJson("{\"address\":\"" + TransferOperationExecutor.GENESIS_ADDRESS
                        + "\",\"balance\":1000000,\"nonce\":7}")));
        server.stubFor(get(urlPathMatching("/v1/accounts/.*"))
                .willReturn(okJson("{\"address\":\"addr:ignored\",\"balance\":1000,\"nonce\":9}")));
        server.stubFor(get(urlPathMatching("/v1/txs/.*"))
                .willReturn(okJson("{\"status\":\"included\",\"height\":20}")));
        server.stubFor(post("/v1/txs")
                .inScenario("nonce-retry")
                .whenScenarioStateIs(Scenario.STARTED)
                .willSetStateTo("load-rejected")
                .willReturn(accepted()));
        server.stubFor(post("/v1/txs")
                .inScenario("nonce-retry")
                .whenScenarioStateIs("load-rejected")
                .willSetStateTo("load-retry")
                .willReturn(aResponse().withStatus(422)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"error\":{\"code\":\"bad_nonce\",\"message\":\"expected 9, got 0\"}}")));
        server.stubFor(post("/v1/txs")
                .inScenario("nonce-retry")
                .whenScenarioStateIs("load-retry")
                .willReturn(accepted()));

        WorkloadScenario scenario = transferScenario();
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        try (TransferOperationExecutor executor = new TransferOperationExecutor(
                new FullNodeClient(List.of(server.baseUrl()), Duration.ofSeconds(2)),
                new LightNodeClient(server.baseUrl(), Duration.ofSeconds(2)),
                scenario,
                new WorkloadMetrics(registry),
                Duration.ofSeconds(2),
                Duration.ofSeconds(2),
                Duration.ofMillis(5),
                java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()
        )) {
            executor.initialize(4242);
            assertTrue(executor.submitNext(System.nanoTime()));
            executor.awaitCompletion(Duration.ofSeconds(3));

            TransferOperationExecutor.TransferMetrics metrics = executor.snapshot();
            assertEquals(1, metrics.completed());
            assertEquals(1, metrics.accepted());
            assertEquals(1, metrics.included());
            assertEquals(0, metrics.failures());
            assertEquals(1, metrics.rejected().get("bad_nonce"));
            assertEquals(1, registry.get("workload.transactions.accepted").counter().count());
            assertEquals(1, registry.get("workload.transactions.included").counter().count());
            assertEquals(1, registry.get("workload.transactions.accepted.by.node")
                    .tag("node", "localhost").counter().count());
            assertEquals(1, registry.get("workload.transactions.rejected")
                    .tag("reason", "bad_nonce").counter().count());
            assertEquals(0, registry.get("workload.transactions.inflight").gauge().value());
        } finally {
            registry.close();
        }

        server.verify(3, postRequestedFor(urlEqualTo("/v1/txs")));
    }

    @Test
    void runsAReadAndTransferMix() throws Exception {
        server.stubFor(get("/v1/light/head").willReturn(
                okJson("{\"chain_id\":4242,\"id\":\"blk:abc\",\"height\":10}")));
        server.stubFor(get(urlPathMatching("/v1/accounts/.*")).willReturn(
                okJson("{\"balance\":1000000,\"nonce\":0}")));
        server.stubFor(get(urlPathMatching("/v1/txs/.*")).willReturn(
                okJson("{\"status\":\"included\",\"height\":11}")));
        server.stubFor(post("/v1/txs").willReturn(accepted()));
        WorkloadScenario scenario = new WorkloadScenario(
                "mixed",
                1,
                5,
                ArrivalModel.Kind.CONSTANT,
                20,
                2,
                0,
                100,
                List.of(),
                List.of(),
                List.of(),
                Map.of("chain_head", 1d),
                5,
                1,
                100,
                2,
                1000
        );

        WorkloadScenarioRunner.RunResult result = new WorkloadScenarioRunner(
                new LightNodeClient(server.baseUrl(), Duration.ofSeconds(2)),
                new FullNodeClient(List.of(server.baseUrl()), Duration.ofSeconds(2)),
                Duration.ofSeconds(2),
                null,
                ignored -> { },
                ignored -> { }
        ).run(scenario);

        assertTrue(result.operations().get(ReadOperation.CHAIN_HEAD).completed() > 0);
        assertTrue(result.transfers().included() > 0);
        assertEquals(0, result.transfers().failures());
    }

    @Test
    void retriesUnknownTxWhileAnAcceptedTransactionPropagates() throws Exception {
        server.stubFor(get(urlPathMatching("/v1/accounts/.*"))
                .willReturn(okJson("{\"balance\":1000000,\"nonce\":0}")));
        server.stubFor(post("/v1/txs").willReturn(accepted()));
        server.stubFor(get(urlEqualTo("/v1/txs/" + TXID))
                .inScenario("unknown-tx-propagation")
                .whenScenarioStateIs(Scenario.STARTED)
                .willSetStateTo("funded")
                .willReturn(okJson("{\"status\":\"included\",\"height\":20}")));
        server.stubFor(get(urlEqualTo("/v1/txs/" + TXID))
                .inScenario("unknown-tx-propagation")
                .whenScenarioStateIs("funded")
                .willSetStateTo("pending")
                .willReturn(aResponse().withStatus(404)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"error\":{\"code\":\"unknown_tx\","
                                + "\"message\":\"transaction has not propagated\"}}")));
        server.stubFor(get(urlEqualTo("/v1/txs/" + TXID))
                .inScenario("unknown-tx-propagation")
                .whenScenarioStateIs("pending")
                .willSetStateTo("included")
                .willReturn(okJson("{\"status\":\"pending\"}")));
        server.stubFor(get(urlEqualTo("/v1/txs/" + TXID))
                .inScenario("unknown-tx-propagation")
                .whenScenarioStateIs("included")
                .willReturn(okJson("{\"status\":\"included\",\"height\":21}")));

        try (TransferOperationExecutor executor = executor(Duration.ofSeconds(1))) {
            executor.initialize(4242);
            assertTrue(executor.submitNext(System.nanoTime()));
            executor.awaitCompletion(Duration.ofSeconds(2));

            TransferOperationExecutor.TransferMetrics metrics = executor.snapshot();
            assertEquals(1, metrics.accepted());
            assertEquals(1, metrics.included());
            assertEquals(0, metrics.failures());
            assertTrue(metrics.rejected().isEmpty());
        }

        server.verify(4, getRequestedFor(urlEqualTo("/v1/txs/" + TXID)));
    }

    @Test
    void badNonceRecoveryDoesNotMoveTheLocalNonceBackwards() throws Exception {
        server.stubFor(get(urlPathMatching("/v1/accounts/.*"))
                .willReturn(okJson("{\"balance\":1000000,\"nonce\":0}")));
        server.stubFor(get(urlPathMatching("/v1/txs/.*"))
                .willReturn(okJson("{\"status\":\"included\",\"height\":20}")));
        server.stubFor(post("/v1/txs")
                .inScenario("nonce-stays-ahead")
                .whenScenarioStateIs(Scenario.STARTED)
                .willSetStateTo("first-load")
                .willReturn(accepted()));
        server.stubFor(post("/v1/txs")
                .inScenario("nonce-stays-ahead")
                .whenScenarioStateIs("first-load")
                .willSetStateTo("second-load")
                .willReturn(accepted()));
        server.stubFor(post("/v1/txs")
                .inScenario("nonce-stays-ahead")
                .whenScenarioStateIs("second-load")
                .willSetStateTo("retry")
                .willReturn(aResponse().withStatus(422)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"error\":{\"code\":\"bad_nonce\",\"message\":\"expected 1, got 0\"}}")));
        server.stubFor(post("/v1/txs")
                .inScenario("nonce-stays-ahead")
                .whenScenarioStateIs("retry")
                .willReturn(accepted()));

        try (TransferOperationExecutor executor = executor(Duration.ofSeconds(2))) {
            executor.initialize(4242);
            assertTrue(executor.submitNext(System.nanoTime()));
            executor.awaitCompletion(Duration.ofSeconds(2));
            assertTrue(executor.submitNext(System.nanoTime()));
            executor.awaitCompletion(Duration.ofSeconds(2));

            List<byte[]> bodies = server.getAllServeEvents().stream()
                    .map(event -> event.getRequest())
                    .filter(request -> "POST".equals(request.getMethod().getName())
                            && "/v1/txs".equals(request.getUrl()))
                    .map(request -> request.getBody())
                    .toList();
            assertEquals(4, bodies.size());
            assertEquals(
                    List.of(0L, 0L, 1L, 1L),
                    bodies.stream().map(TransferOperationExecutorTest::nonce).sorted().toList()
            );
        }
    }

    @Test
    void anAmbiguousPostResponseIsConfirmedAndDoesNotAdvanceToASecondNonce() throws Exception {
        server.stubFor(get(urlPathMatching("/v1/accounts/.*"))
                .willReturn(okJson("{\"balance\":1000000,\"nonce\":0}")));
        server.stubFor(get(urlPathMatching("/v1/txs/.*"))
                .willReturn(okJson("{\"status\":\"included\",\"height\":20}")));
        server.stubFor(post("/v1/txs")
                .inScenario("ambiguous-submit")
                .whenScenarioStateIs(Scenario.STARTED)
                .willSetStateTo("load")
                .willReturn(accepted()));
        server.stubFor(post("/v1/txs")
                .inScenario("ambiguous-submit")
                .whenScenarioStateIs("load")
                .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

        try (TransferOperationExecutor executor = executor(Duration.ofSeconds(2))) {
            executor.initialize(4242);
            assertTrue(executor.submitNext(System.nanoTime()));
            executor.awaitCompletion(Duration.ofSeconds(4));

            TransferOperationExecutor.TransferMetrics metrics = executor.snapshot();
            assertEquals(1, metrics.accepted());
            assertEquals(1, metrics.included());
            assertEquals(0, metrics.failures());
            assertTrue(metrics.rejected().isEmpty());
        }
    }

    @Test
    void unresolvedAmbiguousSubmitCountsAsTransportRejection() throws Exception {
        server.stubFor(get(urlPathMatching("/v1/accounts/.*"))
                .willReturn(okJson("{\"balance\":1000000,\"nonce\":0}")));
        server.stubFor(get(urlPathMatching("/v1/txs/.*"))
                .inScenario("unknown-submit")
                .whenScenarioStateIs("funded")
                .willSetStateTo("load-ready")
                .willReturn(okJson("{\"status\":\"included\",\"height\":20}")));
        server.stubFor(post("/v1/txs")
                .inScenario("unknown-submit")
                .whenScenarioStateIs(Scenario.STARTED)
                .willSetStateTo("funded")
                .willReturn(accepted()));
        server.stubFor(post("/v1/txs")
                .inScenario("unknown-submit")
                .whenScenarioStateIs("load-ready")
                .willSetStateTo("confirm")
                .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));
        server.stubFor(get(urlPathMatching("/v1/txs/.*"))
                .inScenario("unknown-submit")
                .whenScenarioStateIs("confirm")
                .willReturn(aResponse().withStatus(404)));
        server.stubFor(post("/v1/txs")
                .inScenario("unknown-submit")
                .whenScenarioStateIs("confirm")
                .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

        try (TransferOperationExecutor executor = executor(Duration.ofSeconds(2))) {
            executor.initialize(4242);
            assertTrue(executor.submitNext(System.nanoTime()));
            executor.awaitCompletion(Duration.ofSeconds(4));

            TransferOperationExecutor.TransferMetrics metrics = executor.snapshot();
            assertEquals(0, metrics.accepted());
            assertEquals(0, metrics.included());
            assertEquals(1, metrics.failures());
            assertEquals(1, metrics.rejected().get("transport"));
        }
    }

    @Test
    void completionTimeoutCoversTheInclusionWindowAndFinalPoll() throws Exception {
        try (TransferOperationExecutor executor = executor(Duration.ofSeconds(2))) {
            assertTrue(executor.completionTimeout().compareTo(Duration.ofSeconds(2)) > 0);
        }
    }

    private static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder accepted() {
        return aResponse().withStatus(202)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"txid\":\"" + TXID + "\"}");
    }

    private static WorkloadScenario transferScenario() {
        return new WorkloadScenario(
                "transfers",
                10,
                0,
                ArrivalModel.Kind.CONSTANT,
                20,
                2,
                0,
                100,
                List.of(),
                List.of(),
                List.of(),
                Map.of(),
                1,
                1,
                100,
                2,
                1000
        );
    }

    private TransferOperationExecutor executor(Duration requestTimeout) {
        return new TransferOperationExecutor(
                new FullNodeClient(List.of(server.baseUrl()), requestTimeout),
                new LightNodeClient(server.baseUrl(), requestTimeout),
                transferScenario(),
                null,
                requestTimeout,
                Duration.ofSeconds(2),
                Duration.ofMillis(5),
                java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()
        );
    }

    private static long nonce(byte[] body) {
        byte[] nonceBytes = new byte[8];
        System.arraycopy(body, 36, nonceBytes, 0, nonceBytes.length);
        long littleEndian = 0;
        for (int index = 0; index < nonceBytes.length; index++) {
            littleEndian |= (nonceBytes[index] & 0xffL) << (8 * index);
        }
        return littleEndian;
    }
}
