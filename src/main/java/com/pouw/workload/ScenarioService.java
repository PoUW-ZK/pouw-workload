package com.pouw.workload;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ScenarioService {
    private static final Logger LOGGER = LoggerFactory.getLogger(ScenarioService.class);
    private final Path scenariosDirectory;
    private final LightNodeClient client;
    private final WorkloadMetrics metrics;
    private String status = "stopped";
    private String error;
    private String scenarioName;
    private WorkloadScenarioRunner runner;
    private ReadOperationExecutor executor;
    private WorkloadScenarioRunner.RunResult result;

    public ScenarioService(Path scenariosDirectory, LightNodeClient client, WorkloadMetrics metrics) {
        this.scenariosDirectory = scenariosDirectory;
        this.client = client;
        this.metrics = metrics;
    }

    public List<String> scenarios() throws IOException {
        try (var files = Files.list(scenariosDirectory)) {
            return files.filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith(".yaml"))
                    .map(name -> name.substring(0, name.length() - 5))
                    .sorted().toList();
        }
    }

    public synchronized void start(String name) throws IOException {
        if ("running".equals(status) || "stopping".equals(status)) {
            throw new IllegalStateException("Another scenario is running");
        }
        if (name == null || !name.matches("[A-Za-z0-9_-]+")) {
            throw new IllegalArgumentException("Invalid scenario name");
        }
        Path path = scenariosDirectory.resolve(name + ".yaml");
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException("Unknown scenario: " + name);
        }
        WorkloadScenario scenario = WorkloadScenario.fromYaml(path);
        scenarioName = name;
        error = null;
        result = null;
        executor = null;
        status = "running";
        runner = new WorkloadScenarioRunner(client, metrics, current -> {
            synchronized (this) {
                executor = current;
            }
        });
        WorkloadScenarioRunner current = runner;
        Thread.ofVirtual().name("workload-scenario").start(() -> run(current, scenario));
    }

    private void run(WorkloadScenarioRunner current, WorkloadScenario scenario) {
        try {
            WorkloadScenarioRunner.RunResult completed = current.run(scenario);
            synchronized (this) {
                result = completed;
                status = "stopped";
            }
        } catch (CancellationException cancelled) {
            synchronized (this) {
                status = "stopped";
            }
        } catch (Exception failure) {
            LOGGER.error("Scenario failed", failure);
            synchronized (this) {
                error = failure.toString();
                status = "error";
            }
        }
    }

    public synchronized void stop() {
        if (!"running".equals(status)) {
            throw new IllegalStateException("No scenario is running");
        }
        status = "stopping";
        runner.stop();
    }

    public synchronized Status status() {
        Map<ReadOperation, ReadOperationExecutor.OperationMetrics> operations =
                executor == null ? Map.of() : executor.snapshot();
        return new Status(status, scenarioName, error, operations);
    }

    public synchronized WorkloadScenarioRunner.RunResult result() {
        return result;
    }

    public record Status(String status, String scenario, String error,
                         Map<ReadOperation, ReadOperationExecutor.OperationMetrics> operations) {
    }
}
