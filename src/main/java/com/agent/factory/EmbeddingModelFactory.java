package com.agent.factory;

import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.allminilml6v2.AllMiniLmL6V2EmbeddingModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

/**
 * Centralized factory managing the shared in-process embedding model singleton.
 * Prevents redundant initialization of heavy ONNX runtime models and duplicate heap allocation.
 */
public final class EmbeddingModelFactory {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingModelFactory.class);
    private static volatile EmbeddingModel sharedModel;

    private EmbeddingModelFactory() {
        // Utility factory
    }

    /**
     * Returns the shared singleton {@link EmbeddingModel} instance, initializing it lazily if necessary.
     *
     * @return shared EmbeddingModel instance
     */
    public static EmbeddingModel getSharedModel() {
        EmbeddingModel local = sharedModel;
        if (local == null) {
            synchronized (EmbeddingModelFactory.class) {
                local = sharedModel;
                if (local == null) {
                    log.info("[EMBEDDING-FACTORY] Initializing shared AllMiniLmL6V2 ONNX embedding model...");
                    sharedModel = local = new AllMiniLmL6V2EmbeddingModel();
                    log.info("[EMBEDDING-FACTORY] Shared AllMiniLmL6V2 ONNX embedding model ready.");
                }
            }
        }
        return local;
    }

    /**
     * Overrides the shared embedding model instance. Useful for testing or custom model injection.
     *
     * @param model custom EmbeddingModel to use globally
     */
    public static synchronized void setSharedModel(EmbeddingModel model) {
        sharedModel = Objects.requireNonNull(model, "EmbeddingModel must not be null");
        log.info("[EMBEDDING-FACTORY] Shared embedding model overridden with: {}", model.getClass().getSimpleName());
    }

    /**
     * Resets the shared instance, forcing re-initialization on next retrieval.
     */
    public static synchronized void reset() {
        sharedModel = null;
        log.debug("[EMBEDDING-FACTORY] Shared embedding model reset.");
    }
}
