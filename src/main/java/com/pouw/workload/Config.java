package com.pouw.workload;

public record Config(String lightNodeUrl, int maxConcurrency, int timeoutMs) {
    private static final String DEFAULT_LIGHT_NODE_URL = "http://localhost:8080";
    private static final int DEFAULT_MAX_CONCURRENCY = 100;
    private static final int DEFAULT_TIMEOUT_MS = 5000;

    public static Config fromEnvironment() {
        return new Config(
                environmentValue("LIGHT_NODE_URL", DEFAULT_LIGHT_NODE_URL),
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
