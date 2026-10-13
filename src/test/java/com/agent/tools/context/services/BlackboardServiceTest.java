package com.agent.tools.context.services;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BlackboardServiceTest {

    private BlackboardService service;

    @BeforeEach
    void setUp() {
        service = new BlackboardService();
    }

    @AfterEach
    void tearDown() {
        service.shutdown();
    }

    @Test
    @DisplayName("Should write and read permanent data in global namespace")
    void shouldWriteAndReadPermanentData() {
        String writeResult = service.writeData("global", "apiKey", "sk-12345");
        assertThat(writeResult).contains("Wrote key 'apiKey' to namespace 'global' (Permanent).");

        String readResult = service.readData("global", "apiKey");
        assertThat(readResult).isEqualTo("sk-12345");
    }

    @Test
    @DisplayName("Should isolate keys across different namespaces")
    void shouldIsolateKeysAcrossNamespaces() {
        service.writeData("core", "config", "core-val");
        service.writeData("scratchpad", "config", "scratch-val");

        assertThat(service.readData("core", "config")).isEqualTo("core-val");
        assertThat(service.readData("scratchpad", "config")).isEqualTo("scratch-val");
        assertThat(service.readData("global", "config")).contains("not found in namespace 'global'");
    }

    @Test
    @DisplayName("Should return error message when key is not found")
    void shouldReturnErrorForMissingKey() {
        String result = service.readData("global", "missingKey");
        assertThat(result).isEqualTo("Error: Key 'missingKey' not found in namespace 'global'.");
    }

    @Test
    @DisplayName("Should validate empty or null keys")
    void shouldValidateEmptyOrNullKeys() {
        assertThat(service.writeData("global", null, "val")).isEqualTo("Error: Key cannot be empty.");
        assertThat(service.writeData("global", "   ", "val")).isEqualTo("Error: Key cannot be empty.");
        assertThat(service.readData("global", null)).isEqualTo("Error: Key cannot be empty.");
        assertThat(service.readData("global", "   ")).isEqualTo("Error: Key cannot be empty.");
    }

    @Test
    @DisplayName("Should list active keys in namespace")
    void shouldListActiveKeys() {
        service.writeData("global", "k1", "v1");
        service.writeData("global", "k2", "v2");

        String listResult = service.listKeys("global");
        assertThat(listResult).contains("Active keys in 'global':");
        assertThat(listResult).contains("k1");
        assertThat(listResult).contains("k2");

        assertThat(service.listKeys("empty_namespace")).contains("Namespace 'empty_namespace' is empty.");
    }

    @Test
    @DisplayName("Should expire temporary data after TTL expires")
    void shouldExpireTemporaryDataAfterTtl() throws InterruptedException {
        String writeResult = service.writeData("scratchpad", "tempToken", "secret123", 1);
        assertThat(writeResult).contains("Expires in 1s");

        // Immediately available
        assertThat(service.readData("scratchpad", "tempToken")).isEqualTo("secret123");

        // Wait for TTL expiry
        Thread.sleep(1200);

        // Expired and removed on access
        String expiredResult = service.readData("scratchpad", "tempToken");
        assertThat(expiredResult).contains("has expired and was removed");
    }

    @Test
    @DisplayName("Should dump active keys and values in namespace as formatted string")
    void shouldDumpNamespaceWithActiveKeys() {
        service.writeData("core", "ProjectCodeName", "Operation Phoenix");
        service.writeData("core", "TargetPort", "8080");

        String dump = service.dumpNamespace("core");
        assertThat(dump).contains("- ProjectCodeName: Operation Phoenix");
        assertThat(dump).contains("- TargetPort: 8080");
    }

    @Test
    @DisplayName("Should return empty string when namespace is empty or nonexistent")
    void shouldReturnEmptyStringWhenNamespaceEmpty() {
        assertThat(service.dumpNamespace("nonexistent")).isEmpty();
        assertThat(service.dumpNamespace(null)).isEmpty();
    }

    @Test
    @DisplayName("Should omit expired keys from dumpNamespace")
    void shouldOmitExpiredKeysFromDumpNamespace() throws InterruptedException {
        service.writeData("core", "tempKey", "tempValue", 1);
        service.writeData("core", "permKey", "permValue", 0);

        Thread.sleep(1200);

        String dump = service.dumpNamespace("core");
        assertThat(dump).doesNotContain("tempKey");
        assertThat(dump).contains("- permKey: permValue");
    }

    @Test
    @DisplayName("Should handle very large TTL without arithmetic overflow")
    void shouldHandleVeryLargeTtlWithoutOverflow() {
        service.writeData("global", "longTtlKey", "value", Long.MAX_VALUE);
        String result = service.readData("global", "longTtlKey");
        assertThat(result).isEqualTo("value");
    }
}
