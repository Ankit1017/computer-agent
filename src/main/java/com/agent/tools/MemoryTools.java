package com.agent.tools;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.allminilml6v2.AllMiniLmL6V2EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.chroma.ChromaEmbeddingStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Objects;

/**
 * Dedicated tool for explicit long-term semantic memory storage via local ChromaDB
 * and in-process ONNX embeddings (all-MiniLM-L6-v2).
 */
public class MemoryTools {

    private static final Logger log = LoggerFactory.getLogger(MemoryTools.class);
    private static final String DEFAULT_CHROMA_URL = "http://localhost:8000";
    private static final String DEFAULT_COLLECTION_NAME = "agent_memory";

    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;

    /**
     * Default constructor: initializes local in-process ONNX embedding model
     * and connects to the local ChromaDB instance at http://localhost:8000.
     */
    public MemoryTools() {
        this(new AllMiniLmL6V2EmbeddingModel(),
                ChromaEmbeddingStore.builder()
                        .baseUrl(DEFAULT_CHROMA_URL)
                        .collectionName(DEFAULT_COLLECTION_NAME)
                        .timeout(Duration.ofSeconds(30))
                        .build());
    }

    /**
     * Dependency-injected constructor allowing shared or custom models and stores.
     *
     * @param embeddingModel embedding model for converting text to vector representations
     * @param embeddingStore target vector embedding store
     */
    public MemoryTools(EmbeddingModel embeddingModel, EmbeddingStore<TextSegment> embeddingStore) {
        this.embeddingModel = Objects.requireNonNull(embeddingModel, "embeddingModel must not be null");
        this.embeddingStore = Objects.requireNonNull(embeddingStore, "embeddingStore must not be null");
    }

    /**
     * Explicitly commits a text fact or preference to long-term vector memory.
     *
     * @param information text content to remember
     * @return confirmation message
     */
    @Tool("Save important facts, preferences, or architectural decisions to your long-term memory. Use this when the user says 'remember this' or when you solve a complex bug you might need later.")
    public String saveToLongTermMemory(
            @P("The text information or fact to commit to long-term memory") String information) {

        if (information == null || information.isBlank()) {
            return "Error: Information to remember cannot be empty.";
        }

        String trimmedInfo = information.trim();
        log.info("Tool Call: saveToLongTermMemory(info='{}')", trimmedInfo);
        System.out.println("\n[MEMORY] Storing to long-term vector memory: " + trimmedInfo);
        System.out.flush();

        try {
            TextSegment segment = TextSegment.from(trimmedInfo);
            Embedding embedding = embeddingModel.embed(segment).content();
            embeddingStore.add(embedding, segment);

            log.info("Successfully added segment to Chroma collection '{}'", DEFAULT_COLLECTION_NAME);
            return "Successfully committed to long-term memory.";
        } catch (Exception e) {
            log.error("Failed to store information in vector memory", e);
            return "Error saving to long-term memory: " + e.getMessage();
        }
    }

    public EmbeddingModel getEmbeddingModel() {
        return embeddingModel;
    }

    public EmbeddingStore<TextSegment> getEmbeddingStore() {
        return embeddingStore;
    }
}
