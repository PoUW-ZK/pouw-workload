package com.pouw.workload;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

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
}
