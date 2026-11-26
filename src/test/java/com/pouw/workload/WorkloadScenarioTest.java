package com.pouw.workload;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkloadScenarioTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void readsAndValidatesYamlScenario() throws Exception {
        Path scenarioFile = temporaryDirectory.resolve("scenario.yaml");
        Files.writeString(scenarioFile, """
                name: smoke
                duration_seconds: 10
                rate_per_second: 5
                arrival_model: burst
                burst_period_seconds: 20
                max_concurrency: 40
                min_height: 1
                max_height: 100
                addresses:
                  - alice
                transaction_ids:
                  - tx-1
                proof_task_ids:
                  - proof-request-1
                operation_mix:
                  chain_head: 2
                  block: 1
                  balance: 1
                  transaction_status: 1
                  proof_task_status: 1
                """);

        WorkloadScenario scenario = WorkloadScenario.fromYaml(scenarioFile);

        assertEquals("smoke", scenario.name());
        assertEquals(ArrivalModel.Kind.BURST, scenario.arrivalModel());
        assertEquals(5, scenario.ratePerSecond());
        assertEquals(2d, scenario.parsedOperationMix().get(ReadOperation.CHAIN_HEAD));
    }

    @Test
    void rejectsEnabledOperationsWithoutTheirFixturePool() {
        assertThrows(IllegalArgumentException.class, () -> new WorkloadScenario(
                "invalid",
                10,
                5,
                ArrivalModel.Kind.CONSTANT,
                20,
                4,
                1,
                100,
                null,
                null,
                null,
                java.util.Map.of("balance", 1d)
        ));
    }

    @Test
    void rejectsNegativeDurationAndUnknownArrivalModel() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> new WorkloadScenario(
                "negative-duration",
                -1,
                5,
                ArrivalModel.Kind.CONSTANT,
                20,
                4,
                1,
                100,
                null,
                null,
                null,
                java.util.Map.of("chain_head", 1d)
        ));

        Path scenarioFile = temporaryDirectory.resolve("unknown-arrival-model.yaml");
        Files.writeString(scenarioFile, """
                name: unknown-arrival-model
                duration_seconds: 10
                rate_per_second: 5
                arrival_model: random
                max_concurrency: 4
                min_height: 1
                max_height: 100
                operation_mix:
                  chain_head: 1
                """);
        assertThrows(IOException.class, () -> WorkloadScenario.fromYaml(scenarioFile));
    }

    @Test
    void heightPoolStartsAtObservedHeadAndExpandsAsTheChainGrows() {
        WorkloadScenario scenario = new WorkloadScenario(
                "blocks",
                10,
                5,
                ArrivalModel.Kind.CONSTANT,
                20,
                4,
                1,
                100,
                null,
                null,
                null,
                java.util.Map.of("block", 1d)
        );
        ReadDataPool pool = new ReadDataPool(scenario, 5);

        for (int attempt = 0; attempt < 100; attempt++) {
            assertTrue(pool.nextHeight() <= 5);
        }
        pool.observeHead(20);
        for (int attempt = 0; attempt < 100; attempt++) {
            assertTrue(pool.nextHeight() <= 20);
        }
    }

    @Test
    void validatesTransferOnlyAndMixedScenarios() {
        WorkloadScenario transferOnly = new WorkloadScenario(
                "transfers",
                30,
                0,
                ArrivalModel.Kind.POISSON,
                20,
                32,
                0,
                100,
                List.of(),
                List.of(),
                List.of(),
                Map.of(),
                10,
                8,
                100,
                2,
                1_000_000
        );

        assertEquals(10, transferOnly.transferRatePerSecond());
        assertEquals(8, transferOnly.walletCount());
        assertThrows(IllegalArgumentException.class, () -> new WorkloadScenario(
                "unfunded",
                30,
                0,
                ArrivalModel.Kind.CONSTANT,
                20,
                4,
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
                101
        ));
    }

    @Test
    void loadsTheShippedTransferScenario() throws Exception {
        WorkloadScenario scenario =
                WorkloadScenario.fromYaml(Path.of("scenarios", "transfers.yaml"));

        assertEquals("transfers", scenario.name());
        assertEquals(20, scenario.transferRatePerSecond());
        assertEquals(8, scenario.walletCount());
        assertTrue(scenario.parsedOperationMix().containsKey(ReadOperation.CHAIN_HEAD));
    }

    @Test
    void loadsSteadyAndSpikeTransferScenarios() throws Exception {
        WorkloadScenario steady = WorkloadScenario.fromYaml(
                Path.of("scenarios", "transfers-steady.yaml"));
        WorkloadScenario spike = WorkloadScenario.fromYaml(
                Path.of("scenarios", "transfers-spike.yaml"));

        assertEquals(ArrivalModel.Kind.CONSTANT, steady.arrivalModel());
        assertEquals(2, steady.transferRatePerSecond());
        assertEquals(ArrivalModel.Kind.BURST, spike.arrivalModel());
        assertEquals(20, spike.burstPeriodSeconds());
        assertEquals(10, spike.transferRatePerSecond());
    }

    @Test
    void transferScenariosMatchTheDemoLoadLevels() throws Exception {
        WorkloadScenario steady = WorkloadScenario.fromYaml(
                Path.of("scenarios", "transfers-steady.yaml"));
        WorkloadScenario spike = WorkloadScenario.fromYaml(
                Path.of("scenarios", "transfers-spike.yaml"));
        WorkloadScenario high = WorkloadScenario.fromYaml(
                Path.of("scenarios", "transfers.yaml"));

        assertEquals(2, steady.transferRatePerSecond());
        assertEquals(ArrivalModel.Kind.CONSTANT, steady.arrivalModel());
        assertEquals(10, spike.transferRatePerSecond());
        assertEquals(ArrivalModel.Kind.BURST, spike.arrivalModel());
        assertEquals(20, high.transferRatePerSecond());
        assertEquals(ArrivalModel.Kind.POISSON, high.arrivalModel());
    }
}
