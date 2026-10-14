package com.agent.orchestration.workflow.engine;

import com.agent.WorkerAgent;
import com.agent.config.AgentConfig;
import com.agent.factory.SubAgentFactory;
import com.agent.orchestration.workflow.model.WorkflowEdge;
import com.agent.orchestration.workflow.model.WorkflowGraph;
import com.agent.orchestration.workflow.model.WorkflowNode;
import com.agent.orchestration.workflow.services.WorkflowRegistryService;
import com.agent.orchestration.workflow.state.AppendListReducer;
import com.agent.orchestration.workflow.state.WorkflowState;
import com.agent.registry.ToolRegistry;
import dev.langchain4j.model.chat.ChatLanguageModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ScopedStateContractTest {

    @Test
    @DisplayName("Phase A: Should scope read channels in agent prompt to only declared readChannels")
    void shouldScopeReadChannelsInAgentPrompt() {
        // Given a node with readChannels: ["public_spec"]
        WorkflowNode node = new WorkflowNode(
                "step1",
                "analyst",
                "Analyze the provided spec",
                false,
                false,
                List.of("public_spec"),
                Collections.emptyList()
        );
        WorkflowEdge edge = new WorkflowEdge("step1", "__END__");
        WorkflowGraph graph = new WorkflowGraph("contract_read_test", "Read Contract Test", "step1", List.of(node), List.of(edge));

        WorkflowState state = new WorkflowState();
        state.applyUpdate(Map.of(
                "public_spec", "Microservices Architecture v2",
                "secret_api_key", "sk-proj-SUPER-SECRET-9999",
                "bloated_raw_logs", "100MB of raw trace logs"
        ));

        AtomicReference<String> deliveredPrompt = new AtomicReference<>();
        WorkflowAgentInvoker invoker = (agentId, prompt) -> {
            deliveredPrompt.set(prompt);
            return "{\"result\": \"analysis complete\"}";
        };

        WorkflowEngine engine = new WorkflowEngine(graph, invoker, state);
        engine.execute();

        String prompt = deliveredPrompt.get();
        assertThat(prompt).isNotNull();
        // Public spec channel must be visible
        assertThat(prompt).contains("public_spec");
        assertThat(prompt).contains("Microservices Architecture v2");

        // Secret and bloated channels must NOT be leaked in the prompt
        assertThat(prompt).doesNotContain("secret_api_key");
        assertThat(prompt).doesNotContain("sk-proj-SUPER-SECRET-9999");
        assertThat(prompt).doesNotContain("bloated_raw_logs");
        assertThat(prompt).doesNotContain("100MB of raw trace logs");
    }

    @Test
    @DisplayName("Phase B: Should firewall unauthorized writes and only allow declared writeChannels")
    void shouldFirewallUnauthorizedWrites() {
        // Given a node with writeChannels restricted to ["review_notes"]
        WorkflowNode node = new WorkflowNode(
                "review_step",
                "reviewer",
                "Perform code review",
                false,
                false,
                Collections.emptyList(),
                List.of("review_notes")
        );
        WorkflowEdge edge = new WorkflowEdge("review_step", "__END__");
        WorkflowGraph graph = new WorkflowGraph("contract_write_test", "Write Contract Test", "review_step", List.of(node), List.of(edge));

        WorkflowState state = new WorkflowState();
        state.registerReducer("review_notes", new AppendListReducer<String>());

        // Malicious or misbehaving agent attempts to overwrite admin privilege and status
        WorkflowAgentInvoker invoker = (agentId, prompt) -> """
                ```json
                {
                  "review_notes": ["Code looks good and conforms to standards."],
                  "approved": true,
                  "system_role": "ADMIN_OVERRIDE"
                }
                ```
                """;

        WorkflowEngine engine = new WorkflowEngine(graph, invoker, state);
        WorkflowState finalState = engine.execute();

        // review_notes channel must be merged
        List<String> notes = finalState.get("review_notes", List.class);
        assertThat(notes).containsExactly("Code looks good and conforms to standards.");

        // Unauthorized keys must be stripped by the firewall
        assertThat(finalState.get("approved")).isNull();
        assertThat(finalState.get("system_role")).isNull();
    }

    @Test
    @DisplayName("Precedence: WorkflowNode contracts must override AgentConfig defaults")
    void shouldHonorWorkflowNodeContractOverAgentConfigDefaults() {
        // AgentConfig default contract: read=["agent_read_channel"], write=["agent_write_channel"]
        AgentConfig agentConfig = new AgentConfig(
                "specialist",
                "Specialist Role",
                "Instructions",
                List.of("tool1"),
                List.of("agent_read_channel"),
                List.of("agent_write_channel")
        );

        // WorkflowNode overrides: read=["node_read_channel"], write=["node_write_channel"]
        WorkflowNode node = new WorkflowNode(
                "node1",
                "specialist",
                "Execute with override",
                false,
                false,
                List.of("node_read_channel"),
                List.of("node_write_channel")
        );
        WorkflowEdge edge = new WorkflowEdge("node1", "__END__");
        WorkflowGraph graph = new WorkflowGraph("override_test", "Override Test", "node1", List.of(node), List.of(edge));

        WorkflowState state = new WorkflowState();
        state.applyUpdate(Map.of(
                "agent_read_channel", "agent-read-value",
                "node_read_channel", "node-read-value"
        ));

        AtomicReference<String> deliveredPrompt = new AtomicReference<>();
        WorkflowAgentInvoker invoker = new WorkflowAgentInvoker() {
            @Override
            public String invoke(String agentId, String prompt) {
                deliveredPrompt.set(prompt);
                return """
                        {
                          "agent_write_channel": "unauthorized-agent-write",
                          "node_write_channel": "authorized-node-write"
                        }
                        """;
            }

            @Override
            public AgentConfig getConfig(String agentId) {
                return agentConfig;
            }
        };

        WorkflowEngine engine = new WorkflowEngine(graph, invoker, state);
        WorkflowState finalState = engine.execute();

        // 1. Verify Read Scoping: node_read_channel present, agent_read_channel absent
        assertThat(deliveredPrompt.get()).contains("node_read_channel");
        assertThat(deliveredPrompt.get()).contains("node-read-value");
        assertThat(deliveredPrompt.get()).doesNotContain("agent_read_channel");
        assertThat(deliveredPrompt.get()).doesNotContain("agent-read-value");

        // 2. Verify Write Scoping: node_write_channel preserved, agent_write_channel stripped
        assertThat(finalState.get("node_write_channel")).isEqualTo("authorized-node-write");
        assertThat(finalState.get("agent_write_channel")).isNull();
    }

    @Test
    @DisplayName("Backward Compatibility: Empty or null contracts must allow unrestricted read and write access")
    void shouldAllowUnrestrictedAccessWhenContractsAreEmptyOrNull() {
        // Node with no contracts (backward compatible default)
        WorkflowNode node = new WorkflowNode(
                "legacy_step",
                "legacy_agent",
                "Execute legacy step"
        );
        WorkflowEdge edge = new WorkflowEdge("legacy_step", "__END__");
        WorkflowGraph graph = new WorkflowGraph("legacy_test", "Legacy Test", "legacy_step", List.of(node), List.of(edge));

        WorkflowState state = new WorkflowState();
        state.applyUpdate(Map.of("channel_a", "val_a", "channel_b", "val_b"));

        AtomicReference<String> deliveredPrompt = new AtomicReference<>();
        WorkflowAgentInvoker invoker = (agentId, prompt) -> {
            deliveredPrompt.set(prompt);
            return """
                    {
                      "channel_c": "val_c",
                      "channel_d": "val_d"
                    }
                    """;
        };

        WorkflowEngine engine = new WorkflowEngine(graph, invoker, state);
        WorkflowState finalState = engine.execute();

        // Both input channels must be readable
        assertThat(deliveredPrompt.get()).contains("channel_a");
        assertThat(deliveredPrompt.get()).contains("channel_b");

        // Both output channels must be written
        assertThat(finalState.get("channel_c")).isEqualTo("val_c");
        assertThat(finalState.get("channel_d")).isEqualTo("val_d");
    }

    @Test
    @DisplayName("SubAgentFactory Integration: Resolves AgentConfig contracts via SubAgentFactory")
    void shouldResolveContractsViaSubAgentFactory() {
        SubAgentFactory factory = new SubAgentFactory(mock(ChatLanguageModel.class), mock(ToolRegistry.class));

        AgentConfig securedConfig = new AgentConfig(
                "finance_analyst",
                "Financial Analyst",
                "Analyze financial data",
                List.of("calc_tool"),
                List.of("quarterly_revenue"),
                List.of("financial_summary")
        );

        AtomicReference<String> deliveredPrompt = new AtomicReference<>();
        WorkerAgent mockWorker = task -> {
            deliveredPrompt.set(task);
            return """
                    {
                      "financial_summary": "Q3 Revenue grew by 15%",
                      "insider_trading_tip": "BUY STOCK X"
                    }
                    """;
        };

        factory.registerAgent(securedConfig, mockWorker);

        // Node declares no explicit overrides, inherits from AgentConfig
        WorkflowNode node = new WorkflowNode("calc_step", "finance_analyst", "Run quarterly report");
        WorkflowEdge edge = new WorkflowEdge("calc_step", "__END__");
        WorkflowGraph graph = new WorkflowGraph("finance_graph", "Finance Graph", "calc_step", List.of(node), List.of(edge));

        WorkflowState state = new WorkflowState();
        state.applyUpdate(Map.of(
                "quarterly_revenue", "$5.2B",
                "executive_salaries", "$100M"
        ));

        WorkflowEngine engine = new WorkflowEngine(graph, factory, state);
        WorkflowState finalState = engine.execute();

        // Read contract from AgentConfig should be honored
        assertThat(deliveredPrompt.get()).contains("quarterly_revenue");
        assertThat(deliveredPrompt.get()).contains("$5.2B");
        assertThat(deliveredPrompt.get()).doesNotContain("executive_salaries");
        assertThat(deliveredPrompt.get()).doesNotContain("$100M");

        // Write firewalling should have allowed financial_summary and stripped insider_trading_tip
        assertThat(finalState.get("financial_summary")).isEqualTo("Q3 Revenue grew by 15%");
        assertThat(finalState.get("insider_trading_tip")).isNull();
    }

    @Test
    @DisplayName("WorkflowRegistryService: Includes contract tags in workflow description")
    void shouldIncludeContractsInCatalogDescription() {
        WorkflowNode node = new WorkflowNode(
                "auditor_node",
                "auditor",
                "Audit logs",
                false,
                false,
                List.of("system_logs"),
                List.of("audit_verdict")
        );
        WorkflowEdge edge = new WorkflowEdge("auditor_node", "__END__");
        WorkflowGraph graph = new WorkflowGraph("audit_graph", "Audit Graph", "auditor_node", List.of(node), List.of(edge));

        WorkflowRegistryService registry = new WorkflowRegistryService();
        registry.registerWorkflow(graph);

        String description = registry.describeWorkflow(graph);
        assertThat(description).contains("[readChannels: [system_logs]]");
        assertThat(description).contains("[writeChannels: [audit_verdict]]");
    }

    @Test
    @DisplayName("Phase B Fallback: Raw text responses should always preserve <nodeId>_output even with restricted writeChannels")
    void shouldPreserveNodeIdOutputFallbackEvenWithRestrictedWriteChannels() {
        WorkflowNode node = new WorkflowNode(
                "summarizer",
                "writer",
                "Summarize paper",
                false,
                false,
                Collections.emptyList(),
                List.of("summary")
        );
        WorkflowEdge edge = new WorkflowEdge("summarizer", "__END__");
        WorkflowGraph graph = new WorkflowGraph("raw_output_test", "Raw Output Test", "summarizer", List.of(node), List.of(edge));

        WorkflowState state = new WorkflowState();

        // Agent outputs plain unformatted text (no JSON)
        WorkflowAgentInvoker invoker = (agentId, prompt) -> "This is a plain text summary of the document.";

        WorkflowEngine engine = new WorkflowEngine(graph, invoker, state);
        WorkflowState finalState = engine.execute();

        // Plain text fallback key summarizer_output should be retained
        assertThat(finalState.get("summarizer_output")).isEqualTo("This is a plain text summary of the document.");
    }
}
