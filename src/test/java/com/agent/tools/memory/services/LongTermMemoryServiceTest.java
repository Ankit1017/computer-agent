package com.agent.tools.memory.services;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.allminilml6v2.AllMiniLmL6V2EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LongTermMemoryServiceTest {

    private EmbeddingModel embeddingModel;
    private EmbeddingStore<TextSegment> embeddingStore;
    private LongTermMemoryService memoryService;

    @BeforeEach
    void setUp() {
        embeddingModel = new AllMiniLmL6V2EmbeddingModel();
        embeddingStore = new InMemoryEmbeddingStore<>();
        memoryService = new LongTermMemoryService(embeddingModel, embeddingStore);
    }

    @Test
    @DisplayName("Should store information and retrieve it via semantic search")
    void shouldStoreAndSearchInformation() {
        String fact = "The server deployment password for production is AlphaBravoCharlie99!";
        String storeResult = memoryService.storeInformation(fact);

        assertThat(storeResult).contains("Successfully stored information in long-term memory");

        String searchResult = memoryService.searchInformation("What is the production deployment password?");
        assertThat(searchResult)
                .contains("Semantic Search Results")
                .contains("AlphaBravoCharlie99!");
    }

    @Test
    @DisplayName("Should return not found message when query has no relevant matches")
    void shouldReturnNotFoundWhenNoMatches() {
        String searchResult = memoryService.searchInformation("quantum entanglement in superconductors");
        assertThat(searchResult).contains("No highly relevant information found in long-term memory");
    }

    @Test
    @DisplayName("Should return error message when memory service is offline")
    void shouldReturnErrorWhenOffline() {
        LongTermMemoryService offlineService = new LongTermMemoryService(null, null);

        assertThat(offlineService.storeInformation("fact")).isEqualTo("Error: Memory store is offline.");
        assertThat(offlineService.searchInformation("query")).isEqualTo("Error: Memory store is offline.");
    }
}
