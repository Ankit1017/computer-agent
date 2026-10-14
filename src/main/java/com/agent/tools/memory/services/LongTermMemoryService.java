package com.agent.tools.memory.services;

import com.agent.factory.EmbeddingModelFactory;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.chroma.ChromaEmbeddingStore;

import java.time.Duration;
import java.util.List;

public class LongTermMemoryService {
    private EmbeddingModel embeddingModel;
    private EmbeddingStore<TextSegment> embeddingStore;
    private boolean isAvailable = false;

    public LongTermMemoryService() {
        System.out.println("[MEMORY] Initializing Semantic Memory (ChromaDB)...");
        try {
            this.embeddingModel = EmbeddingModelFactory.getSharedModel();
            this.embeddingStore = ChromaEmbeddingStore.builder()
                    .baseUrl("http://localhost:8000")
                    .collectionName("swarm_memory")
                    .timeout(Duration.ofSeconds(5))
                    .build();
            this.isAvailable = true;
            System.out.println("[MEMORY] Semantic Memory Engine Ready.");
        } catch (Exception e) {
            System.err.println("[MEMORY] WARNING: Failed to initialize ChromaDB. Long-term memory will be unavailable. Ensure Chroma is running on port 8000.");
        }
    }

    public LongTermMemoryService(EmbeddingModel embeddingModel, EmbeddingStore<TextSegment> embeddingStore) {
        this.embeddingModel = embeddingModel;
        this.embeddingStore = embeddingStore;
        this.isAvailable = (embeddingModel != null && embeddingStore != null);
    }

    public String storeInformation(String fact) {
        if (!isAvailable) return "Error: Memory store is offline.";
        if (fact == null || fact.isBlank()) {
            return "Error: Information to store cannot be empty.";
        }
        try {
            // Split long facts to ensure high-quality embeddings
            Document document = Document.from(fact.trim());
            List<TextSegment> segments = DocumentSplitters.recursive(500, 50).split(document);
            
            if (segments.isEmpty()) {
                return "Error: No valid content found to store.";
            }

            embeddingStore.addAll(embeddingModel.embedAll(segments).content(), segments);
            return "Successfully stored information in long-term memory. Segments created: " + segments.size();
        } catch (Exception e) {
            return "Error storing information: " + e.getMessage();
        }
    }

    public String searchInformation(String query) {
        if (!isAvailable) return "Error: Memory store is offline.";
        if (query == null || query.isBlank()) {
            return "Error: Query cannot be empty.";
        }
        try {
            EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                    .queryEmbedding(embeddingModel.embed(query.trim()).content())
                    .maxResults(3)
                    .minScore(0.5)
                    .build();

            EmbeddingSearchResult<TextSegment> searchResult = embeddingStore.search(request);
            List<EmbeddingMatch<TextSegment>> matches = searchResult != null ? searchResult.matches() : List.of();

            if (matches == null || matches.isEmpty()) {
                return "No highly relevant information found in long-term memory for query: '" + query + "'";
            }

            StringBuilder sb = new StringBuilder("Semantic Search Results for '" + query + "':\n\n");
            for (int i = 0; i < matches.size(); i++) {
                EmbeddingMatch<TextSegment> match = matches.get(i);
                if (match != null && match.embedded() != null) {
                    sb.append("--- Fact ").append(i + 1).append(" (Relevance: ")
                      .append(String.format("%.2f", match.score())).append(") ---\n");
                    sb.append(match.embedded().text()).append("\n\n");
                }
            }
            return sb.toString().trim();
        } catch (Exception e) {
            return "Error searching memory: " + e.getMessage();
        }
    }
}
