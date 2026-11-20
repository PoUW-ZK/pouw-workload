package com.pouw.workload;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReadOperationExecutorTest {
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
    void capsConcurrencyWithoutBlockingAndRecordsPlannedToResponseLatency() throws Exception {
        server.stubFor(get("/v1/light/head").willReturn(
                aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withFixedDelay(150)
                        .withBody("{\"chain_id\":4242,\"id\":\"blk:abc\",\"height\":100}")
        ));
        WorkloadScenario scenario = scenario(1);

        try (ReadOperationExecutor executor = new ReadOperationExecutor(
                new LightNodeClient(server.baseUrl(), Duration.ofSeconds(2)),
                scenario
        )) {
            long plannedSendNanos = System.nanoTime();
            assertTrue(executor.submit(ReadOperation.CHAIN_HEAD, plannedSendNanos));
            for (int request = 0; request < 20; request++) {
                assertFalse(executor.submit(ReadOperation.CHAIN_HEAD, plannedSendNanos));
            }

            executor.awaitCompletion(Duration.ofSeconds(2));
            ReadOperationExecutor.OperationMetrics metrics =
                    executor.snapshot().get(ReadOperation.CHAIN_HEAD);
            assertEquals(1, metrics.completed());
            assertEquals(0, metrics.failures());
            assertEquals(20, metrics.rejected());
            assertTrue(metrics.p50Micros() >= 100_000);
            assertTrue(metrics.p99Micros() >= metrics.p50Micros());
        }
    }

    @Test
    void runsYamlConfiguredScenarioAtTheRequestedFrequencyAgainstWireMock() throws Exception {
        server.stubFor(get("/v1/light/head").willReturn(okJson("""
                {"chain_id":4242,"id":"blk:abc","height":100}
                """)));
        WorkloadScenario scenario = scenario(10);

        WorkloadScenarioRunner.RunResult result = new WorkloadScenarioRunner(
                new LightNodeClient(server.baseUrl(), Duration.ofSeconds(2))
        ).run(scenario);

        assertTrue(Math.abs(result.scheduledArrivals() - 20) <= 1,
                "Scheduled " + result.scheduledArrivals() + " arrivals instead of 20");
        assertEquals(result.scheduledArrivals(),
                result.operations().get(ReadOperation.CHAIN_HEAD).completed());
    }

    @Test
    void recordsFailuresAndRejectedArrivalsInPrometheus() throws Exception {
        server.stubFor(get("/v1/light/head").willReturn(aResponse().withStatus(404).withFixedDelay(150)));
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        try (ReadOperationExecutor executor = new ReadOperationExecutor(
                     new LightNodeClient(server.baseUrl(), Duration.ofSeconds(2)),
                     scenario(1), 100, new WorkloadMetrics(registry))) {
            assertTrue(executor.submit(ReadOperation.CHAIN_HEAD, System.nanoTime()));
            assertFalse(executor.submit(ReadOperation.CHAIN_HEAD, System.nanoTime()));
            executor.awaitCompletion(Duration.ofSeconds(3));
            assertEquals(1, registry.get("workload.reads.sent").tag("operation", "chain_head").counter().count());
            assertEquals(1, registry.get("workload.reads.errors").tag("operation", "chain_head").counter().count());
            assertEquals(1, registry.get("workload.reads.rejected").tag("operation", "chain_head").counter().count());
            assertEquals(0, registry.get("workload.reads.inflight").gauge().value());
            assertEquals(1, registry.get("workload.reads.latency").tag("operation", "chain_head").timer().count());
        } finally {
            registry.close();
        }
    }

    @Test
    void releasesPermitAndDoesNotCountReadWhenExecutorRejectsSubmission() {
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        var executorService = Executors.newVirtualThreadPerTaskExecutor();
        executorService.shutdown();
        try (ReadOperationExecutor executor = new ReadOperationExecutor(
                new LightNodeClient(server.baseUrl(), Duration.ofSeconds(2)),
                scenario(1), 100, new WorkloadMetrics(registry), executorService)) {
            assertThrows(java.util.concurrent.RejectedExecutionException.class,
                    () -> executor.submit(ReadOperation.CHAIN_HEAD, System.nanoTime()));
            assertThrows(java.util.concurrent.RejectedExecutionException.class,
                    () -> executor.submit(ReadOperation.CHAIN_HEAD, System.nanoTime()));

            assertEquals(2, executor.snapshot().get(ReadOperation.CHAIN_HEAD).rejected());
            assertEquals(2, registry.get("workload.reads.rejected")
                    .tag("operation", "chain_head").counter().count());
            assertEquals(0, registry.get("workload.reads.sent")
                    .tag("operation", "chain_head").counter().count());
            assertEquals(0, registry.get("workload.reads.inflight").gauge().value());
        } finally {
            registry.close();
        }
    }

    private static WorkloadScenario scenario(int maxConcurrency) {
        return new WorkloadScenario(
                "wiremock",
                1,
                20,
                ArrivalModel.Kind.CONSTANT,
                20,
                maxConcurrency,
                1,
                100,
                List.of(),
                List.of(),
                List.of(),
                Map.of("chain_head", 1d)
        );
    }
}
