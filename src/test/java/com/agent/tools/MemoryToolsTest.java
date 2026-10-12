package com.agent.tools;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.allminilml6v2.AllMiniLmL6V2EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MemoryToolsTest {

    @Test
    @DisplayName("Should validate empty or null inputs for saveToLongTermMemory")
    void shouldHandleEmptyInputs() {
        EmbeddingModel model = new AllMiniLmL6V2EmbeddingModel();
        EmbeddingStore<TextSegment> store = new InMemoryEmbeddingStore<>();
        MemoryTools memoryTools = new MemoryTools(model, store);

        assertThat(memoryTools.saveToLongTermMemory("")).isEqualTo("Error: Information to remember cannot be empty.");
        assertThat(memoryTools.saveToLongTermMemory(null)).isEqualTo("Error: Information to remember cannot be empty.");
        assertThat(memoryTools.saveToLongTermMemory("   ")).isEqualTo("Error: Information to remember cannot be empty.");
    }

    @Test
    @DisplayName("Should embed and commit facts into vector store")
    void shouldCommitFactToMemoryStore() {
        EmbeddingModel model = new AllMiniLmL6V2EmbeddingModel();
        EmbeddingStore<TextSegment> store = new InMemoryEmbeddingStore<>();
        MemoryTools memoryTools = new MemoryTools(model, store);

        String fact = "My preferred Python formatting tool is 'black' and I always use a line length of 120.";
        String result = memoryTools.saveToLongTermMemory(fact);

        assertThat(result).isEqualTo("Successfully committed to long-term memory.");

        // Verify retrieval via similarity search
        Embedding queryEmbedding = model.embed("What python formatter do I use?").content();
        List<EmbeddingMatch<TextSegment>> matches = store.findRelevant(queryEmbedding, 1);

        assertThat(matches).isNotEmpty();
        assertThat(matches.get(0).embedded().text()).isEqualTo(fact);
        assertThat(matches.get(0).score()).isGreaterThan(0.6);
    }

    @Test
    @DisplayName("Should query long-term memory and return matching memories")
    void shouldQueryMemorySuccessfully() {
        EmbeddingModel model = new AllMiniLmL6V2EmbeddingModel();
        EmbeddingStore<TextSegment> store = new InMemoryEmbeddingStore<>();
        MemoryTools memoryTools = new MemoryTools(model, store);

        memoryTools.saveToLongTermMemory("On Windows, use python3 command alias.");
        memoryTools.saveToLongTermMemory("PostgreSQL database runs on port 5432.");

        String searchResult = memoryTools.queryMemory("how to run python on Windows?");
        assertThat(searchResult)
                .contains("Memories retrieved:")
                .contains("On Windows, use python3 command alias.");

        String notFound = memoryTools.queryMemory("What is the speed of light in vacuum?");
        assertThat(notFound).isEqualTo("No relevant memories found.");
    }

    @Test
    @DisplayName("Should validate empty query in queryMemory")
    void shouldValidateEmptyQuery() {
        EmbeddingModel model = new AllMiniLmL6V2EmbeddingModel();
        EmbeddingStore<TextSegment> store = new InMemoryEmbeddingStore<>();
        MemoryTools memoryTools = new MemoryTools(model, store);

        assertThat(memoryTools.queryMemory("")).isEqualTo("Error: Query cannot be empty.");
        assertThat(memoryTools.queryMemory(null)).isEqualTo("Error: Query cannot be empty.");
    }
}
