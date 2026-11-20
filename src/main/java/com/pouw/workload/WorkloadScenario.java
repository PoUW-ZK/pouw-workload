package com.pouw.workload;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public record WorkloadScenario(
        String name,
        @JsonProperty("duration_seconds") long durationSeconds,
        @JsonProperty("rate_per_second") double ratePerSecond,
        @JsonProperty("arrival_model") ArrivalModel.Kind arrivalModel,
        @JsonProperty("burst_period_seconds") long burstPeriodSeconds,
        @JsonProperty("max_concurrency") int maxConcurrency,
        @JsonProperty("min_height") long minHeight,
        @JsonProperty("max_height") long maxHeight,
        List<String> addresses,
        @JsonProperty("transaction_ids") List<String> transactionIds,
        @JsonProperty("proof_task_ids") List<String> proofTaskIds,
        @JsonProperty("operation_mix") Map<String, Double> operationMix,
        @JsonProperty("transfer_rate_per_second") double transferRatePerSecond,
        @JsonProperty("wallet_count") int walletCount,
        @JsonProperty("transfer_amount") long transferAmount,
        @JsonProperty("transfer_fee") long transferFee,
        @JsonProperty("wallet_funding_amount") long walletFundingAmount
) {
    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory());

    public WorkloadScenario(
            String name,
            long durationSeconds,
            double ratePerSecond,
            ArrivalModel.Kind arrivalModel,
            long burstPeriodSeconds,
            int maxConcurrency,
            long minHeight,
            long maxHeight,
            List<String> addresses,
            List<String> transactionIds,
            List<String> proofTaskIds,
            Map<String, Double> operationMix
    ) {
        this(name, durationSeconds, ratePerSecond, arrivalModel, burstPeriodSeconds,
                maxConcurrency, minHeight, maxHeight, addresses, transactionIds, proofTaskIds,
                operationMix, 0, 0, 0, 0, 0);
    }

    public WorkloadScenario {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        if (durationSeconds <= 0 || durationSeconds > Long.MAX_VALUE / 1_000_000_000L) {
            throw new IllegalArgumentException("duration_seconds is outside the supported range");
        }
        if (!isValidRate(ratePerSecond) || !isValidRate(transferRatePerSecond)
                || (ratePerSecond == 0 && transferRatePerSecond == 0)) {
            throw new IllegalArgumentException(
                    "read and transfer rates must be in [0, 1000000000] and one must be positive");
        }
        if (arrivalModel == null) {
            throw new IllegalArgumentException("arrival_model must not be null");
        }
        if (arrivalModel == ArrivalModel.Kind.BURST
                && (burstPeriodSeconds <= 1 || burstPeriodSeconds > Long.MAX_VALUE / 1_000_000_000L)) {
            throw new IllegalArgumentException("burst_period_seconds is outside the supported range");
        }
        if (maxConcurrency <= 0) {
            throw new IllegalArgumentException("max_concurrency must be greater than zero");
        }
        if (minHeight < 0 || maxHeight < minHeight || maxHeight == Long.MAX_VALUE) {
            throw new IllegalArgumentException("height range must satisfy 0 <= min_height <= max_height");
        }

        addresses = immutableList(addresses, "addresses");
        transactionIds = immutableList(transactionIds, "transaction_ids");
        proofTaskIds = immutableList(proofTaskIds, "proof_task_ids");
        operationMix = operationMix == null ? Map.of() : operationMix;
        if (ratePerSecond > 0 && operationMix.isEmpty()) {
            throw new IllegalArgumentException("operation_mix must contain at least one operation");
        }

        EnumMap<ReadOperation, Double> weights = new EnumMap<>(ReadOperation.class);
        double totalWeight = 0;
        for (Map.Entry<String, Double> entry : operationMix.entrySet()) {
            if (entry.getKey() == null) {
                throw new IllegalArgumentException("operation_mix must not contain a blank operation name");
            }
            ReadOperation operation = ReadOperation.fromConfigName(entry.getKey());
            Double weight = entry.getValue();
            if (weight == null || !Double.isFinite(weight) || weight < 0) {
                throw new IllegalArgumentException("operation weights must be finite and non-negative");
            }
            weights.put(operation, weight);
            totalWeight += weight;
        }
        if (ratePerSecond > 0 && (!Double.isFinite(totalWeight) || totalWeight <= 0)) {
            throw new IllegalArgumentException("operation_mix must have a positive total weight");
        }

        if ((weight(weights, ReadOperation.BALANCE) > 0 || weight(weights, ReadOperation.NONCE) > 0)
                && addresses.isEmpty()) {
            throw new IllegalArgumentException("addresses are required for balance and nonce operations");
        }
        if (weight(weights, ReadOperation.TRANSACTION_STATUS) > 0 && transactionIds.isEmpty()) {
            throw new IllegalArgumentException("transaction_ids are required for transaction_status");
        }
        if (weight(weights, ReadOperation.PROOF_TASK_STATUS) > 0 && proofTaskIds.isEmpty()) {
            throw new IllegalArgumentException("proof_task_ids are required for proof_task_status");
        }
        if (transferRatePerSecond > 0) {
            if (walletCount <= 0) {
                throw new IllegalArgumentException("wallet_count must be greater than zero for transfers");
            }
            if (transferAmount <= 0) {
                throw new IllegalArgumentException("transfer_amount must be greater than zero");
            }
            if (transferFee < 0) {
                throw new IllegalArgumentException("transfer_fee must be non-negative");
            }
            if (transferAmount > Long.MAX_VALUE - transferFee
                    || walletFundingAmount < transferAmount + transferFee) {
                throw new IllegalArgumentException(
                        "wallet_funding_amount must cover the transfer amount and fee");
            }
        }
        operationMix = Map.copyOf(operationMix);
    }

    public static WorkloadScenario fromYaml(Path path) throws IOException {
        try (var input = java.nio.file.Files.newInputStream(path)) {
            return YAML_MAPPER.readValue(input, WorkloadScenario.class);
        }
    }

    Map<ReadOperation, Double> parsedOperationMix() {
        EnumMap<ReadOperation, Double> weights = new EnumMap<>(ReadOperation.class);
        operationMix.forEach((name, weight) -> weights.put(ReadOperation.fromConfigName(name), weight));
        return Map.copyOf(weights);
    }

    private static double weight(Map<ReadOperation, Double> weights, ReadOperation operation) {
        return weights.getOrDefault(operation, 0d);
    }

    private static boolean isValidRate(double rate) {
        return Double.isFinite(rate) && rate >= 0 && rate <= 1_000_000_000;
    }

    private static List<String> immutableList(List<String> values, String name) {
        List<String> immutable = values == null ? List.of() : List.copyOf(values);
        if (immutable.stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new IllegalArgumentException(name + " must not contain blank values");
        }
        return immutable;
    }
}
