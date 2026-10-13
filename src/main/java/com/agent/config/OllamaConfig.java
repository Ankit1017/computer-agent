package com.agent.config;

import java.time.Duration;

/**
 * Immutable configuration for the local Ollama LLM provider.
 * Supports defaults and overrides via system properties or environment variables.
 */
public record OllamaConfig(
        String baseUrl,
        String modelName,
        double temperature,
        Duration timeout
) {
    public static final String DEFAULT_BASE_URL = "http://localhost:11434";
    public static final String DEFAULT_MODEL_NAME = "llama3.1";
    public static final double DEFAULT_TEMPERATURE = 0.0;
    public static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(5);

    /**
     * Resolves configuration using environment variables / system properties with fallbacks.
     * Env variables:
     * - OLLAMA_BASE_URL
     * - OLLAMA_MODEL
     * - OLLAMA_TEMPERATURE
     * - OLLAMA_TIMEOUT_SECONDS
     */
    public static OllamaConfig fromEnvOrDefault() {
        String baseUrl = getEnvOrProperty("OLLAMA_BASE_URL", DEFAULT_BASE_URL);
        String modelName = getEnvOrProperty("OLLAMA_MODEL", DEFAULT_MODEL_NAME);

        double temperature = DEFAULT_TEMPERATURE;
        String tempStr = getEnvOrProperty("OLLAMA_TEMPERATURE", null);
        if (tempStr != null && !tempStr.isBlank()) {
            try {
                temperature = Double.parseDouble(tempStr.trim());
            } catch (NumberFormatException ignored) {}
        }

        Duration timeout = DEFAULT_TIMEOUT;
        String timeoutSecStr = getEnvOrProperty("OLLAMA_TIMEOUT_SECONDS", null);
        if (timeoutSecStr != null && !timeoutSecStr.isBlank()) {
            try {
                timeout = Duration.ofSeconds(Long.parseLong(timeoutSecStr.trim()));
            } catch (NumberFormatException ignored) {}
        }

        return new OllamaConfig(baseUrl, modelName, temperature, timeout);
    }

    private static String getEnvOrProperty(String key, String fallback) {
        String val = System.getProperty(key);
        if (val != null && !val.isBlank()) {
            return val;
        }
        val = System.getenv(key);
        if (val != null && !val.isBlank()) {
            return val;
        }
        return fallback;
    }
}
