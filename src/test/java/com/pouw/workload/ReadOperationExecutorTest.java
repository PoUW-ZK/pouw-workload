package com.pouw.workload;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        server.stubFor(get("/v1/blocks/head").willReturn(
                aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withFixedDelay(150)
                        .withBody("{\"id\":\"blk:abc\",\"header\":{\"height\":100}}")
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
        server.stubFor(get("/v1/blocks/head").willReturn(okJson("""
                {"id":"blk:abc","header":{"height":100}}
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
