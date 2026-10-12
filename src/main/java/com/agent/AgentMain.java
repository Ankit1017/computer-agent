package com.agent;

import com.agent.config.AgentConfig;
import com.agent.registry.ToolRegistry;
import com.agent.tools.GitTools;
import com.agent.tools.MemoryTools;
import com.agent.tools.TerminalTools;
import com.agent.tools.WebTools;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.allminilml6v2.AllMiniLmL6V2EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.chroma.ChromaEmbeddingStore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Scanner;

/**
 * Phase 7, Milestone 7.1.1: Agent Specification Schema & Tool Registry Verification.
 */
public class AgentMain {

    private static final Logger log = LoggerFactory.getLogger(AgentMain.class);

    public static void main(String[] args) {
        log.info("Starting Computer Agent - Milestone 7.1.1 Tool Registry Verification");

        try {
            // 1. Shared Scanner for HITL tool security confirmation
            Scanner scanner = new Scanner(System.in);

            // 2. Initialize Local Vector Store & In-Process ONNX Embedding Model
            EmbeddingModel embeddingModel = new AllMiniLmL6V2EmbeddingModel();
            EmbeddingStore<TextSegment> embeddingStore;
            try {
                embeddingStore = ChromaEmbeddingStore.builder()
                        .baseUrl("http://localhost:8000")
                        .collectionName("agent_memory")
                        .timeout(Duration.ofSeconds(2))
                        .build();
            } catch (Exception e) {
                log.warn("ChromaDB not reachable at http://localhost:8000, using in-memory store for verification");
                embeddingStore = new InMemoryEmbeddingStore<>();
            }

            // 3. Instantiate Tools
            TerminalTools terminalTools = new TerminalTools(scanner);
            GitTools gitTools = new GitTools(scanner);
            WebTools webTools = new WebTools(scanner);
            MemoryTools memoryTools = new MemoryTools(embeddingModel, embeddingStore);

            // 4. Instantiate ToolRegistry and register tools
            ToolRegistry registry = new ToolRegistry(terminalTools, gitTools, webTools, memoryTools);

            // 5. Read agents.json from classpath
            ObjectMapper mapper = new ObjectMapper();
            try (InputStream is = AgentMain.class.getClassLoader().getResourceAsStream("agents.json")) {
                if (is == null) {
                    throw new IllegalStateException("agents.json not found in classpath resources");
                }

                List<AgentConfig> configs = mapper.readValue(is, new TypeReference<List<AgentConfig>>() {});

                System.out.println("=======================================================");
                System.out.println("Multi-Agent Swarm: Agent Specification & Tool Registry");
                System.out.println("=======================================================");

                for (AgentConfig config : configs) {
                    Object[] resolvedTools = registry.getTools(config.tools());
                    List<String> toolClassNames = Arrays.stream(resolvedTools)
                            .map(tool -> tool.getClass().getSimpleName())
                            .toList();

                    System.out.println("Loaded Agent: " + config.name() + " with tools: " + toolClassNames);
                }
                System.out.println("=======================================================");
            }
        } catch (Exception e) {
            log.error("Fatal error during registry verification", e);
            System.err.println("ERROR: Could not verify agent registry: " + e.getMessage());
            System.exit(1);
        }
    }
}
