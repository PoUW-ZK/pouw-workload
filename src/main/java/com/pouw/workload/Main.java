package com.pouw.workload;

import io.javalin.Javalin;

import java.time.Duration;
import java.util.Map;

public final class Main {
    private Main() {
    }

    public static void main(String[] args) {
        Config config = Config.fromEnvironment();
        LightNodeClient lightNodeClient = new LightNodeClient(
                config.lightNodeUrl(),
                Duration.ofMillis(config.timeoutMs())
        );

        // Javalin 7: routes are declared in the config
        Javalin.create(javalin -> javalin.routes.get("/health",
                        context -> context.json(Map.of("status", "UP"))))
                .start(8000);

        System.out.println("Java L1 Workload Generator Initialized");
    }
}
