package com.agent.factory;

import com.agent.config.OllamaConfig;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;

/**
 * Factory for creating configured instances of {@link ChatLanguageModel} backed by local Ollama.
 * Designed for modularity, clean testability, and future injection into LangChain4j AiServices.
 */
public final class OllamaModelFactory {

    private static final Logger log = LoggerFactory.getLogger(OllamaModelFactory.class);

    private OllamaModelFactory() {
        // Prevent instantiation of utility factory class
    }

    /**
     * Creates a {@link ChatLanguageModel} using default or environment-defined settings.
     *
     * @return fully configured ChatLanguageModel instance
     */
    public static ChatLanguageModel createModel() {
        return createModel(OllamaConfig.fromEnvOrDefault());
    }

    /**
     * Creates a {@link ChatLanguageModel} with a specific model name, keeping standard settings.
     *
     * @param modelName name of the Ollama model (e.g. "llama3.1", "gpt-oss:20b")
     * @return fully configured ChatLanguageModel instance
     */
    public static ChatLanguageModel createModel(String modelName) {
        OllamaConfig baseConfig = OllamaConfig.fromEnvOrDefault();
        OllamaConfig config = new OllamaConfig(
                baseConfig.baseUrl(),
                modelName,
                baseConfig.temperature(),
                baseConfig.timeout()
        );
        return createModel(config);
    }

    /**
     * Creates a {@link ChatLanguageModel} using an explicit {@link OllamaConfig}.
     *
     * @param config the configuration record
     * @return fully configured ChatLanguageModel instance
     */
    public static ChatLanguageModel createModel(OllamaConfig config) {
        log.info("Initializing OllamaChatModel: baseUrl='{}', model='{}', temperature={}, timeout={}s",
                config.baseUrl(), config.modelName(), config.temperature(), config.timeout().toSeconds());

        return OllamaChatModel.builder()
                .baseUrl(config.baseUrl())
                .modelName(config.modelName())
                .temperature(config.temperature())
                .timeout(config.timeout())
                .build();
    }

    /**
     * Creates a {@link ChatLanguageModel} with explicit parameters.
     *
     * @param baseUrl     Ollama server endpoint (e.g. "http://localhost:11434")
     * @param modelName   model identifier (e.g. "llama3.1")
     * @param temperature sampling temperature (0.0 for deterministic tool/agent execution)
     * @param timeout     request timeout duration
     * @return fully configured ChatLanguageModel instance
     */
    public static ChatLanguageModel createModel(String baseUrl, String modelName, Double temperature, Duration timeout) {
        return createModel(new OllamaConfig(
                baseUrl != null ? baseUrl : OllamaConfig.DEFAULT_BASE_URL,
                modelName != null ? modelName : OllamaConfig.DEFAULT_MODEL_NAME,
                temperature != null ? temperature : OllamaConfig.DEFAULT_TEMPERATURE,
                timeout != null ? timeout : OllamaConfig.DEFAULT_TIMEOUT
        ));
    }
}
