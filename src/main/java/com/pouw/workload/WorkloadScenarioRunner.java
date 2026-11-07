package com.pouw.workload;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class WorkloadScenarioRunner {
    private final LightNodeClient client;

    public WorkloadScenarioRunner(LightNodeClient client) {
        this.client = client;
    }

    public RunResult run(Path scenarioPath)
            throws IOException, InterruptedException, ExecutionException, TimeoutException {
        return run(WorkloadScenario.fromYaml(scenarioPath));
    }

    public RunResult run(WorkloadScenario scenario)
            throws InterruptedException, ExecutionException, TimeoutException {
        long initialHead = scenario.maxHeight();
        var operationMix = scenario.parsedOperationMix();
        if (operationMix.getOrDefault(ReadOperation.BLOCK_HEADER, 0d) > 0
                || operationMix.getOrDefault(ReadOperation.BLOCK, 0d) > 0) {
            initialHead = client.getChainHead().get(30, TimeUnit.SECONDS).height();
        }
        try (ReadOperationExecutor executor = new ReadOperationExecutor(client, scenario, initialHead);
             OpenLoopScheduler scheduler = new OpenLoopScheduler()) {
            ArrivalModel arrivalModel = ArrivalModel.create(
                    scenario.arrivalModel(),
                    scenario.ratePerSecond(),
                    scenario.burstPeriodSeconds()
            );
            OpenLoopScheduler.ScheduleResult scheduled = scheduler.start(
                    Duration.ofSeconds(scenario.durationSeconds()),
                    arrivalModel,
                    executor::submitNext
            ).get(scenario.durationSeconds() + 5, TimeUnit.SECONDS);
            executor.awaitCompletion(Duration.ofSeconds(10));
            return new RunResult(scheduled.scheduledArrivals(), executor.snapshot());
        }
    }

    public record RunResult(
            long scheduledArrivals,
            Map<ReadOperation, ReadOperationExecutor.OperationMetrics> operations
    ) {
    }
}
