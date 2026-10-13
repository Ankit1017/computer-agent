package com.agent.tools.memory.services;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.allminilml6v2.AllMiniLmL6V2EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
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
            this.embeddingModel = new AllMiniLmL6V2EmbeddingModel();
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
        try {
            // Split long facts to ensure high-quality embeddings
            Document document = Document.from(fact);
            List<TextSegment> segments = DocumentSplitters.recursive(500, 50).split(document);
            
            embeddingStore.addAll(embeddingModel.embedAll(segments).content(), segments);
            return "Successfully stored information in long-term memory. Segments created: " + segments.size();
        } catch (Exception e) {
            return "Error storing information: " + e.getMessage();
        }
    }

    public String searchInformation(String query) {
        if (!isAvailable) return "Error: Memory store is offline.";
        try {
            List<EmbeddingMatch<TextSegment>> matches = embeddingStore.findRelevant(
                    embeddingModel.embed(query).content(),
                    3, // Return top 3 most relevant facts
                    0.5 // Minimum relevance score
            );

            if (matches.isEmpty()) {
                return "No highly relevant information found in long-term memory for query: '" + query + "'";
            }

            StringBuilder sb = new StringBuilder("Semantic Search Results for '" + query + "':\n\n");
            for (int i = 0; i < matches.size(); i++) {
                sb.append("--- Fact ").append(i + 1).append(" (Relevance: ")
                  .append(String.format("%.2f", matches.get(i).score())).append(") ---\n");
                sb.append(matches.get(i).embedded().text()).append("\n\n");
            }
            return sb.toString().trim();
        } catch (Exception e) {
            return "Error searching memory: " + e.getMessage();
        }
    }
}
