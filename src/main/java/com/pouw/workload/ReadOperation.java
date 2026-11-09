package com.pouw.workload;

import java.util.Locale;

public enum ReadOperation {
    CHAIN_HEAD("chain_head"),
    BLOCK_HEADER("block_header"),
    BLOCK("block"),
    BALANCE("balance"),
    NONCE("nonce"),
    TRANSACTION_STATUS("transaction_status"),
    PROOF_TASK_STATUS("proof_task_status");

    private final String configName;

    ReadOperation(String configName) {
        this.configName = configName;
    }

    public String configName() {
        return configName;
    }

    public static ReadOperation fromConfigName(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Read operation name must not be blank");
        }
        String normalized = value.toLowerCase(Locale.ROOT);
        for (ReadOperation operation : values()) {
            if (operation.configName.equals(normalized)) {
                return operation;
            }
        }
        throw new IllegalArgumentException("Unknown read operation: " + value);
    }
}
