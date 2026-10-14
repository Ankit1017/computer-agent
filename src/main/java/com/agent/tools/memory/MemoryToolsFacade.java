package com.agent.tools.memory;

import com.agent.tools.memory.services.LongTermMemoryService;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

public class MemoryToolsFacade {
    private final LongTermMemoryService longTermMemoryService;

    public MemoryToolsFacade(LongTermMemoryService longTermMemoryService) {
        this.longTermMemoryService = longTermMemoryService;
    }

    @Tool("Store an important fact, architectural decision, or learned solution into long-term semantic memory for future reference.")
    public String storeFact(@P("The information to remember") String fact) {
        return com.agent.telemetry.SwarmTelemetry.executeTool("storeFact",
                com.agent.telemetry.SwarmTelemetry.formatArgs("fact", fact),
                () -> {
                    System.out.println("[MEMORY] Archiving fact to vector store...");
                    try {
                        return longTermMemoryService.storeInformation(fact);
                    } catch (Exception e) {
                        return "Error storing information: " + e.getMessage();
                    }
                });
    }

    @Tool("Search long-term semantic memory for previously stored facts, solutions, or context using natural language.")
    public String searchMemory(@P("The search query") String query) {
        return com.agent.telemetry.SwarmTelemetry.executeTool("searchMemory",
                com.agent.telemetry.SwarmTelemetry.formatArgs("query", query),
                () -> {
                    System.out.println("[MEMORY] Searching vector store for: " + query);
                    try {
                        return longTermMemoryService.searchInformation(query);
                    } catch (Exception e) {
                        return "Error searching memory: " + e.getMessage();
                    }
                });
    }
}
