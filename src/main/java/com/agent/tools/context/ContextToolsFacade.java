package com.agent.tools.context;

import com.agent.tools.context.services.BlackboardService;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

public class ContextToolsFacade {
    private final BlackboardService blackboardService;

    public ContextToolsFacade(BlackboardService blackboardService) {
        this.blackboardService = blackboardService;
    }

    @Tool("Write information to the shared global blackboard for other agents to read.")
    public String writeToBlackboard(
            @P("The unique key to store the data under") String key,
            @P("The data payload to store") String data) {
        return blackboardService.writeData("global", key, data);
    }

    @Tool("Write information to a specific namespace on the blackboard (e.g., 'core', 'scratchpad').")
    public String writeToNamespacedBlackboard(
            @P("The namespace to store data in") String namespace,
            @P("The unique key to store the data under") String key,
            @P("The data payload to store") String data) {
        return blackboardService.writeData(namespace, key, data);
    }

    @Tool("Read information from the shared global blackboard using a specific key.")
    public String readFromBlackboard(@P("The key of the data to retrieve") String key) {
        return blackboardService.readData("global", key);
    }

    @Tool("List all active keys currently stored in the global blackboard to see what information is available.")
    public String listBlackboardKeys() {
        return blackboardService.listKeys("global");
    }
}
