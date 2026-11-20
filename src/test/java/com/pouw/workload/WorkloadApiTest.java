package com.pouw.workload;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import io.javalin.Javalin;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.junit.jupiter.api.Assertions.*;

class WorkloadApiTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void startsStopsAndExposesResultsAndMetrics(@TempDir Path scenarios) throws Exception {
        Files.writeString(scenarios.resolve("test.yaml"), """
                name: test
                duration_seconds: 2
                rate_per_second: 10
                arrival_model: constant
                max_concurrency: 10
                min_height: 0
                max_height: 100
                operation_mix:
                  chain_head: 1
                """);
        Files.writeString(scenarios.resolve("invalid-model.yaml"), """
                name: invalid-model
                duration_seconds: 10
                rate_per_second: 5
                arrival_model: random
                max_concurrency: 10
                min_height: 0
                max_height: 100
                operation_mix:
                  chain_head: 1
                """);
        WireMockServer node = new WireMockServer(wireMockConfig().dynamicPort());
        node.start();
        node.stubFor(com.github.tomakehurst.wiremock.client.WireMock.get("/v1/light/head").willReturn(
                okJson("{\"chain_id\":4242,\"id\":\"blk:abc\",\"height\":100}")));
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        ScenarioService service = new ScenarioService(scenarios,
                new LightNodeClient(node.baseUrl(), Duration.ofSeconds(2)), new WorkloadMetrics(registry));
        Main.RunningServers servers = Main.startServers(0, 0, service, registry);
        Javalin api = servers.api();
        Javalin metrics = servers.metrics();
        try {
            assertEquals(8000, Main.API_PORT);
            assertEquals(8001, Main.METRICS_PORT);
            assertNotEquals(api.port(), metrics.port());
            assertEquals(200, get(api, "/health").statusCode());
            assertEquals("UP", json(get(api, "/health")).path("status").asText());
            assertEquals(200, get(api, "/scenarios").statusCode());
            assertTrue(json(get(api, "/scenarios")).path("scenarios").toString().contains("test"));
            assertEquals(200, get(api, "/api/v1/scenarios").statusCode());
            assertEquals(404, get(api, "/metrics").statusCode());
            assertEquals(404, get(metrics, "/health").statusCode());
            assertEquals(404, get(metrics, "/api/v1/status").statusCode());
            assertEquals(400, post(api, "/start", "{\"scenario_name\":\"invalid-model\"}").statusCode());
            assertEquals(400, post(api, "/start", "{\"scenario_name\":\"missing\"}").statusCode());
            assertEquals(400, post(api, "/api/v1/start", "{\"scenario_name\":\"../test\"}").statusCode());
            assertEquals(400, post(api, "/start", "null").statusCode());
            assertEquals(404, get(api, "/results").statusCode());
            assertEquals(404, get(api, "/api/v1/results").statusCode());
            assertEquals(202, post(api, "/api/v1/start", "{\"scenario_name\":\"test\"}").statusCode());
            assertEquals(409, post(api, "/start", "{\"scenario_name\":\"test\"}").statusCode());
            assertEquals("running", json(get(api, "/api/v1/status")).path("status").asText());
            assertEquals("running", json(get(api, "/status")).path("status").asText());
            long firstReadDeadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (registry.get("workload.reads.successful").tag("operation", "chain_head")
                    .counter().count() == 0 && System.nanoTime() < firstReadDeadline) {
                Thread.sleep(25);
            }
            assertTrue(registry.get("workload.reads.successful").tag("operation", "chain_head")
                    .counter().count() > 0);
            assertEquals(202, post(api, "/api/v1/stop", "").statusCode());
            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (!"stopped".equals(json(get(api, "/status")).path("status").asText())
                    && System.nanoTime() < deadline) {
                Thread.sleep(25);
            }

            assertEquals("stopped", json(get(api, "/status")).path("status").asText());
            assertTrue(json(get(api, "/status")).path("operations").path("CHAIN_HEAD").has("p50Micros"));
            assertEquals(409, post(api, "/stop", "").statusCode());
            assertTrue(get(api, "/results").body().contains("CHAIN_HEAD,"));
            assertEquals(200, get(api, "/api/v1/results").statusCode());
            HttpResponse<String> metricsResponse = get(metrics, "/metrics");
            assertEquals(200, metricsResponse.statusCode());
            assertTrue(metricsResponse.headers().firstValue("Content-Type").orElse("")
                    .startsWith("text/plain"));
            String scrape = metricsResponse.body();
            assertTrue(scrape.contains("workload_reads_sent_total{operation=\"chain_head\"}"));
            assertTrue(scrape.contains("workload_reads_successful_total{operation=\"chain_head\"}"));
            assertTrue(scrape.contains("workload_reads_latency_seconds_bucket{"));
            assertEquals(202, post(api, "/start", "{\"scenario_name\":\"test\"}").statusCode());
            deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (!"stopped".equals(json(get(api, "/status")).path("status").asText())
                    && System.nanoTime() < deadline) {
                Thread.sleep(25);
            }
            assertEquals("stopped", json(get(api, "/status")).path("status").asText());
            assertEquals(200, get(api, "/results").statusCode());
        } finally {
            servers.close();
            node.stop();
            registry.close();
        }
    }

    @Test
    void stopCancelsHeadLookupBeforeScheduling(@TempDir Path scenarios) throws Exception {
        Files.writeString(scenarios.resolve("header.yaml"), """
                name: header
                duration_seconds: 30
                rate_per_second: 10
                arrival_model: constant
                max_concurrency: 10
                min_height: 0
                max_height: 100
                operation_mix:
                  block_header: 1
                """);
        WireMockServer node = new WireMockServer(wireMockConfig().dynamicPort());
        node.start();
        node.stubFor(com.github.tomakehurst.wiremock.client.WireMock.get("/v1/light/head").willReturn(
                aResponse().withFixedDelay(5000).withHeader("Content-Type", "application/json")
                        .withBody("{\"chain_id\":4242,\"id\":\"blk:abc\",\"height\":100}")));
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        ScenarioService service = new ScenarioService(scenarios,
                new LightNodeClient(node.baseUrl(), Duration.ofSeconds(10)), new WorkloadMetrics(registry));
        Javalin api = Main.api(service).start(0);
        try {
            assertEquals(202, post(api, "/start", "{\"scenario_name\":\"header\"}").statusCode());
            long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (node.getAllServeEvents().isEmpty() && System.nanoTime() < deadline) {
                Thread.sleep(25);
            }
            assertFalse(node.getAllServeEvents().isEmpty());
            assertEquals(202, post(api, "/stop", "").statusCode());
            deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
            while (!"stopped".equals(json(get(api, "/status")).path("status").asText())
                    && System.nanoTime() < deadline) {
                Thread.sleep(25);
            }
            assertEquals("stopped", json(get(api, "/status")).path("status").asText());
            assertEquals(0, registry.get("workload.reads.sent").tag("operation", "block_header").counter().count());
        } finally {
            api.stop();
            node.stop();
            registry.close();
        }
    }

    private JsonNode json(HttpResponse<String> response) throws Exception {
        return JSON.readTree(response.body());
    }

    private HttpResponse<String> get(Javalin server, String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + server.port() + path))
                .GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(Javalin server, String path, String body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + server.port() + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
}
