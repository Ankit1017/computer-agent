package com.agent.orchestration;

import com.agent.orchestration.services.OrchestrationService;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

import java.util.Objects;

public class DelegationFacade {
    private final OrchestrationService orchestrationService;

    public DelegationFacade(OrchestrationService orchestrationService) {
        this.orchestrationService = Objects.requireNonNull(orchestrationService, "orchestrationService must not be null");
    }

    @Tool("Delegate a task to a specific sub-agent (e.g., 'architect', 'developer', 'devops', 'gui_tester', 'researcher').")
    public String delegateTask(
            @P("The ID of the sub-agent") String agentId,
            @P("The detailed task description") String task) {
        return orchestrationService.routeTask(agentId, task);
    }
}
