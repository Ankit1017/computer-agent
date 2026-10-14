package com.agent.orchestration;

import com.agent.config.AgentConfig;
import com.agent.orchestration.workflow.services.WorkflowRegistryService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OrchestratorPromptBuilderTest {

    @Test
    @DisplayName("Should build comprehensive orchestrator prompt containing hybrid routing, dynamic delegation, and workflow directives")
    void shouldBuildPromptWithConfigs() {
        List<AgentConfig> configs = List.of(
                new AgentConfig("devops", "DevOps Engineer", "Manage git and builds", List.of("git")),
                new AgentConfig("researcher", "Web Research Specialist", "Scour internet", List.of("web"))
        );

        String prompt = OrchestratorPromptBuilder.buildPrompt(configs);

        assertThat(prompt)
                .contains("You are the Swarm Orchestrator and Supervisor.")
                .contains("Dynamic Delegation Tools ('delegateTask', 'delegateParallel')")
                .contains("Deterministic Workflow Tools ('listAvailableWorkflows', 'executeWorkflow', 'resumeWorkflow')")
                .contains("HYBRID INTENT ROUTING DIRECTIVES:")
                .contains("DETERMINISTIC WORKFLOW ROUTING:")
                .contains("DYNAMIC SUB-AGENT DELEGATION ROUTING:")
                .contains("STRICT WORKFLOW HUMAN-IN-THE-LOOP (HITL) & ANTI-LOOPING RULES:")
                .contains("When 'executeWorkflow' or 'resumeWorkflow' returns a status of 'SUSPENDED'")
                .contains("ONLY when the human user explicitly replies to authorize, approve, or provide state updates")
                .contains("'devops' (DevOps Engineer)")
                .contains("'researcher' (Web Research Specialist)")
                .contains("PARALLEL EXECUTION:")
                .contains("delegateParallel")
                .contains("BLACKBOARD & CORE MEMORY AUTO-INJECTION");
    }

    @Test
    @DisplayName("Should inject registered workflow catalog IDs when provided")
    void shouldInjectAvailableWorkflows() {
        List<AgentConfig> configs = List.of(
                new AgentConfig("architect", "Software Architect", "Design software", List.of("terminal"))
        );
        List<String> workflows = List.of("research_pipeline", "deploy_pipeline");

        String prompt = OrchestratorPromptBuilder.buildPrompt(configs, workflows);

        assertThat(prompt)
                .contains("REGISTERED DECLARATIVE WORKFLOWS IN CATALOG:")
                .contains("- 'research_pipeline'")
                .contains("- 'deploy_pipeline'");
    }

    @Test
    @DisplayName("Should support WorkflowRegistryService overload dynamically")
    void shouldSupportWorkflowRegistryServiceOverload() {
        WorkflowRegistryService registryMock = mock(WorkflowRegistryService.class);
        when(registryMock.listWorkflowIds()).thenReturn(List.of("catalog_pipeline_v1"));

        List<AgentConfig> configs = List.of(
                new AgentConfig("developer", "Senior Developer", "Write code", List.of("terminal"))
        );

        String prompt = OrchestratorPromptBuilder.buildPrompt(configs, registryMock);

        assertThat(prompt)
                .contains("REGISTERED DECLARATIVE WORKFLOWS IN CATALOG:")
                .contains("- 'catalog_pipeline_v1'");
    }
}
