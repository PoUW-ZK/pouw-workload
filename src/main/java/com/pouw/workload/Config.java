package com.pouw.workload;

import java.util.Arrays;
import java.util.List;

public record Config(String lightNodeUrl, List<String> fullNodeUrls, int maxConcurrency, int timeoutMs) {
    private static final String DEFAULT_LIGHT_NODE_URL = "http://localhost:8080";
    private static final String DEFAULT_FULL_NODE_URLS =
            "http://node-1:8000,http://node-2:8000,http://node-3:8000";
    private static final int DEFAULT_MAX_CONCURRENCY = 100;
    private static final int DEFAULT_TIMEOUT_MS = 5000;

    public static Config fromEnvironment() {
        List<String> fullNodeUrls = Arrays.stream(
                        environmentValue("FULL_NODE_URLS", DEFAULT_FULL_NODE_URLS).split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .toList();
        if (fullNodeUrls.isEmpty()) {
            throw new IllegalArgumentException("FULL_NODE_URLS must contain at least one URL");
        }
        return new Config(
                environmentValue("LIGHT_NODE_URL", DEFAULT_LIGHT_NODE_URL),
                fullNodeUrls,
                positiveIntegerEnvironmentValue("MAX_CONCURRENCY", DEFAULT_MAX_CONCURRENCY),
                positiveIntegerEnvironmentValue("TIMEOUT_MS", DEFAULT_TIMEOUT_MS)
        );
    }

    private static String environmentValue(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private static int positiveIntegerEnvironmentValue(String name, int defaultValue) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }

        try {
            int parsed = Integer.parseInt(value);
            if (parsed <= 0) {
                throw new IllegalArgumentException(name + " must be greater than zero");
            }
            return parsed;
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException(name + " must be a positive integer", error);
        }
    }
}
