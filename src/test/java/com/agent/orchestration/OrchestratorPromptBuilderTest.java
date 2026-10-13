package com.agent.orchestration;

import com.agent.config.AgentConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OrchestratorPromptBuilderTest {

    @Test
    @DisplayName("Should build comprehensive orchestrator prompt containing agent manifest and directives")
    void shouldBuildPromptWithConfigs() {
        List<AgentConfig> configs = List.of(
                new AgentConfig("devops", "DevOps Engineer", "Manage git and builds", List.of("git")),
                new AgentConfig("researcher", "Web Research Specialist", "Scour internet", List.of("web"))
        );

        String prompt = OrchestratorPromptBuilder.buildPrompt(configs);

        assertThat(prompt)
                .contains("You are the Swarm Orchestrator.")
                .contains("CRITICAL: You ONLY have the tools: 'delegateTask' and 'delegateParallel'.")
                .contains("- devops: DevOps Engineer - Manage git and builds")
                .contains("- researcher: Web Research Specialist - Scour internet")
                .contains("PARALLEL EXECUTION:")
                .contains("delegateParallel")
                .contains("BLACKBOARD & CORE MEMORY AUTO-INJECTION");
    }
}
