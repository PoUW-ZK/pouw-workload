package com.pouw.workload;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

public final class Main {
    static final int API_PORT = 8000;
    static final int METRICS_PORT = 8001;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Logger LOGGER = LoggerFactory.getLogger(Main.class);

    private Main() {
    }

    public static void main(String[] args) {
        Config config = Config.fromEnvironment();
        Duration requestTimeout = Duration.ofMillis(config.timeoutMs());
        LightNodeClient client = new LightNodeClient(config.lightNodeUrl(), requestTimeout);
        FullNodeClient fullNodeClient = new FullNodeClient(config.fullNodeUrls(), requestTimeout);
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        ScenarioService service = new ScenarioService(
                Path.of("scenarios"), client, fullNodeClient, requestTimeout, new WorkloadMetrics(registry));
        startServers(API_PORT, METRICS_PORT, service, registry);
    }

    static RunningServers startServers(
            int apiPort,
            int metricsPort,
            ScenarioService service,
            PrometheusMeterRegistry registry
    ) {
        Javalin api = api(service).start(apiPort);
        try {
            Javalin metrics = metrics(registry).start(metricsPort);
            return new RunningServers(api, metrics);
        } catch (RuntimeException | Error error) {
            api.stop();
            throw error;
        }
    }

    static Javalin api(ScenarioService service) {
        return Javalin.create(config -> {
            config.routes.get("/health", ctx -> ctx.json(Map.of("status", "UP")));
            config.routes.get("/scenarios", ctx -> scenarios(ctx, service));
            for (String prefix : new String[] {"", "/api/v1"}) {
                config.routes.post(prefix + "/start", ctx -> start(ctx, service));
                config.routes.post(prefix + "/stop", ctx -> stop(ctx, service));
                config.routes.get(prefix + "/status", ctx -> ctx.json(service.status()));
                config.routes.get(prefix + "/results", ctx -> results(ctx, service));
                if (!prefix.isEmpty()) {
                    config.routes.get(prefix + "/scenarios", ctx -> scenarios(ctx, service));
                }
            }
        });
    }

    static Javalin metrics(PrometheusMeterRegistry registry) {
        return Javalin.create(config -> config.routes.get("/metrics",
                ctx -> ctx.contentType("text/plain; version=0.0.4; charset=utf-8").result(registry.scrape())));
    }

    private static void scenarios(Context ctx, ScenarioService service) {
        try {
            ctx.json(Map.of("scenarios", service.scenarios()));
        } catch (IOException error) {
            scenarioError(ctx, error);
        }
    }

    private static void scenarioError(Context ctx, IOException error) {
        LOGGER.error("Unable to access scenarios", error);
        ctx.status(500).json(Map.of("error", "Unable to access scenarios"));
    }

    private static void start(Context ctx, ScenarioService service) {
        try {
            StartRequest request = JSON.readValue(ctx.body(), StartRequest.class);
            if (request == null) {
                throw new IllegalArgumentException("Request body must be an object");
            }
            service.start(request.scenarioName());
            ctx.status(202).json(Map.of("message", "Scenario started"));
        } catch (JsonProcessingException | IllegalArgumentException error) {
            ctx.status(400).json(Map.of("error", error.getMessage()));
        } catch (IllegalStateException error) {
            ctx.status(409).json(Map.of("error", error.getMessage()));
        } catch (IOException error) {
            scenarioError(ctx, error);
        }
    }

    private static void stop(Context ctx, ScenarioService service) {
        try {
            service.stop();
            ctx.status(202).json(Map.of("message", "Stop requested"));
        } catch (IllegalStateException error) {
            ctx.status(409).json(Map.of("error", error.getMessage()));
        }
    }

    private static void results(Context ctx, ScenarioService service) {
        WorkloadScenarioRunner.RunResult result = service.result();
        if (result == null) {
            ctx.status(404).json(Map.of("error", "No results are available"));
            return;
        }
        StringBuilder csv = new StringBuilder("operation,completed,failures,rejected,p50_us,p95_us,p99_us\n");
        result.operations().forEach((operation, values) -> csv.append(operation.name()).append(',')
                .append(values.completed()).append(',').append(values.failures()).append(',')
                .append(values.rejected()).append(',').append(values.p50Micros()).append(',')
                .append(values.p95Micros()).append(',').append(values.p99Micros()).append('\n'));
        TransferOperationExecutor.TransferMetrics transfers = result.transfers();
        csv.append("TRANSFER,").append(transfers.completed()).append(',')
                .append(transfers.failures()).append(',')
                .append(transfers.rejected().values().stream().mapToLong(Long::longValue).sum())
                .append(',').append(transfers.p50Micros()).append(',')
                .append(transfers.p95Micros()).append(',').append(transfers.p99Micros()).append('\n');
        ctx.contentType("text/csv").result(csv.toString());
    }

    private record StartRequest(@JsonProperty("scenario_name") String scenarioName) {
    }

    record RunningServers(Javalin api, Javalin metrics) implements AutoCloseable {
        @Override
        public void close() {
            try {
                metrics.stop();
            } finally {
                api.stop();
            }
        }
    }
}
