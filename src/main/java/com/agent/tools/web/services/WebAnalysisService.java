package com.agent.tools.web.services;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.allminilml6v2.AllMiniLmL6V2EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;

import java.util.List;

public class WebAnalysisService {
    private final EmbeddingModel embeddingModel;

    public WebAnalysisService() {
        // Fast, local in-process ONNX embedding model
        this.embeddingModel = new AllMiniLmL6V2EmbeddingModel();
    }

    public WebAnalysisService(EmbeddingModel embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    public String analyzeContent(String markdownContent, String query) {
        if (markdownContent == null || markdownContent.isBlank()) {
            return "Error: Markdown content is empty. Cannot perform analysis.";
        }
        if (query == null || query.isBlank()) {
            return "Error: Query cannot be empty.";
        }

        try {
            // 1. Create a Document
            Document document = Document.from(markdownContent);

            // 2. Split into chunks (e.g., 500 characters, with 50 char overlap)
            List<TextSegment> segments = DocumentSplitters
                    .recursive(500, 50)
                    .split(document);

            if (segments.isEmpty()) {
                return "Analysis complete. No sections of the page were found to analyze.";
            }

            // 3. Create a temporary in-memory store for this specific analysis
            EmbeddingStore<TextSegment> embeddingStore = new InMemoryEmbeddingStore<>();

            // 4. Embed and store the chunks
            embeddingStore.addAll(embeddingModel.embedAll(segments).content(), segments);

            // 5. Search the store for the query
            EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                    .queryEmbedding(embeddingModel.embed(query).content())
                    .maxResults(3)
                    .minScore(0.5)
                    .build();

            EmbeddingSearchResult<TextSegment> searchResult = embeddingStore.search(request);
            List<EmbeddingMatch<TextSegment>> matches = searchResult != null ? searchResult.matches() : List.of();

            // If strict 0.5 score yielded no matches, attempt a slightly broader search with 0.3
            if (matches.isEmpty()) {
                EmbeddingSearchRequest broaderRequest = EmbeddingSearchRequest.builder()
                        .queryEmbedding(embeddingModel.embed(query).content())
                        .maxResults(3)
                        .minScore(0.3)
                        .build();
                EmbeddingSearchResult<TextSegment> broaderResult = embeddingStore.search(broaderRequest);
                if (broaderResult != null && !broaderResult.matches().isEmpty()) {
                    matches = broaderResult.matches();
                }
            }

            if (matches.isEmpty()) {
                return "Analysis complete. No sections of the page were highly relevant to the query: '" + query + "'";
            }

            // 6. Format the results
            StringBuilder sb = new StringBuilder("Relevant excerpts found for query '" + query + "':\n\n");
            for (int i = 0; i < matches.size(); i++) {
                sb.append("--- Excerpt ").append(i + 1).append(" (Score: ")
                  .append(String.format("%.2f", matches.get(i).score())).append(") ---\n");
                sb.append(matches.get(i).embedded().text()).append("\n\n");
            }
            return sb.toString().trim();

        } catch (Exception e) {
            return "Error performing semantic analysis: " + e.getMessage();
        }
    }
}
