package com.pouw.workload;

import com.github.tomakehurst.wiremock.WireMockServer;
import jdk.jfr.Recording;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkloadPerformanceTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void warmsUpAndProfilesConfiguredThroughputAgainstNoDelayWireMock() throws Exception {
        WireMockServer server = new WireMockServer(wireMockConfig().dynamicPort());
        server.start();
        try {
            server.stubFor(get("/v1/blocks/head").willReturn(okJson("""
                    {"id":"blk:abc","header":{"height":100}}
                    """)));
            LightNodeClient client = new LightNodeClient(server.baseUrl(), Duration.ofSeconds(2));
            for (int request = 0; request < 20; request++) {
                client.getChainHead().get(2, TimeUnit.SECONDS);
            }

            Path recordingPath = temporaryDirectory.resolve("workload-throughput.jfr");
            WorkloadScenario scenario = new WorkloadScenario(
                    "throughput-profile",
                    2,
                    2_000,
                    ArrivalModel.Kind.CONSTANT,
                    20,
                    256,
                    1,
                    100,
                    List.of(),
                    List.of(),
                    List.of(),
                    Map.of("chain_head", 1d)
            );
            WorkloadScenarioRunner.RunResult result;
            try (Recording recording = new Recording()) {
                recording.enable("jdk.ExecutionSample").withPeriod(Duration.ofMillis(10));
                recording.enable("jdk.ThreadPark");
                recording.start();
                result = new WorkloadScenarioRunner(client).run(scenario);
                recording.stop();
                recording.dump(recordingPath);
            }

            assertTrue(Math.abs(result.offeredRequestsPerSecond() / 2_000 - 1) < 0.05,
                    "Offered rate was " + result.offeredRequestsPerSecond() + " requests/s");
            assertTrue(result.completedRequestsPerSecond() > 0);
            assertTrue(Files.size(recordingPath) > 0);
        } finally {
            server.stop();
        }
    }
}
