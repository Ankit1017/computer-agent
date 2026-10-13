package com.agent.tools.context;

import com.agent.tools.context.services.BlackboardService;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

public class ContextToolsFacade {
    private final BlackboardService blackboardService;

    public ContextToolsFacade(BlackboardService blackboardService) {
        this.blackboardService = blackboardService;
    }

    @Tool("Write permanent information to the shared global blackboard for other agents to read.")
    public String writeToBlackboard(
            @P("The unique key to store the data under") String key,
            @P("The data payload to store") String data) {
        return blackboardService.writeData("global", key, data, 0);
    }

    @Tool("Write permanent information to a specific namespace on the blackboard (e.g., 'core').")
    public String writeToNamespacedBlackboard(
            @P("The namespace to store data in") String namespace,
            @P("The unique key") String key,
            @P("The data payload") String data) {
        return blackboardService.writeData(namespace, key, data, 0);
    }

    @Tool("Write temporary scratchpad data to a specific namespace. The data will automatically delete itself after ttlSeconds.")
    public String writeTemporaryData(
            @P("The namespace to store data in (use 'scratchpad')") String namespace,
            @P("The unique key") String key,
            @P("The data payload") String data,
            @P("Time-to-Live in seconds before this data is deleted (e.g., 30)") int ttlSeconds) {
        return blackboardService.writeData(namespace, key, data, ttlSeconds);
    }

    @Tool("Read information from the shared global blackboard using a specific key.")
    public String readFromBlackboard(@P("The key of the data to retrieve") String key) {
        return blackboardService.readData("global", key);
    }

    @Tool("Read information from a specific namespace (e.g., 'scratchpad', 'global', 'core').")
    public String readFromNamespacedBlackboard(
            @P("The namespace") String namespace, 
            @P("The key of the data to retrieve") String key) {
        return blackboardService.readData(namespace, key);
    }

    @Tool("List all active keys currently stored in the global blackboard to see what information is available.")
    public String listBlackboardKeys() {
        return blackboardService.listKeys("global");
    }

    @Tool("List all active keys in a specific namespace on the blackboard.")
    public String listNamespacedBlackboardKeys(@P("The namespace") String namespace) {
        return blackboardService.listKeys(namespace);
    }
}
