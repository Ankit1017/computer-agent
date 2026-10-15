package com.agent.telemetry;

/**
 * Standardized OpenTelemetry attribute keys for agent swarm tracing and observability.
 */
public final class TelemetryAttributes {

    private TelemetryAttributes() {}

    // Agent & Execution Identity
    public static final String AGENT_ID = "agent.id";
    public static final String AGENT_NAME = "agent.name";
    public static final String MEMORY_ID = "memory.id";

    // Workflow & BSP Topology
    public static final String WORKFLOW_ID = "workflow.id";
    public static final String WORKFLOW_NODE_ID = "workflow.node.id";
    public static final String THREAD_ID = "thread.id";
    public static final String SUPERSTEP_INDEX = "superstep.index";

    // Tools & Actuation
    public static final String TOOL_NAME = "tool.name";
    public static final String TOOL_ARGUMENTS_JSON = "tool.arguments.json";

    // Token Counts & Usage
    public static final String LLM_TOKENS_PROMPT = "llm.tokens.prompt";
    public static final String LLM_TOKENS_COMPLETION = "llm.tokens.completion";
    public static final String LLM_TOKENS_TOTAL = "llm.tokens.total";

    // LLM Context Payloads
    public static final String LLM_CONTEXT_SYSTEM_PROMPT = "llm.context.system_prompt";
    public static final String LLM_CONTEXT_ACTIVE_STATE_JSON = "llm.context.active_state_json";
    public static final String LLM_CONTEXT_CORE_MEMORY_INJECTED = "llm.context.core_memory_injected";

    // Errors & Exceptions
    public static final String ERROR_MESSAGE = "error.message";
    public static final String ERROR_TYPE = "error.type";

    // Guardrails & Loop Protection
    public static final String GUARDRAIL_WARNING = "guardrail.warning";
    public static final String GUARDRAIL_BLOCKED = "guardrail.blocked";
    public static final String GUARDRAIL_REASON = "guardrail.reason";
    public static final String GUARDRAIL_REDACTED = "guardrail.redacted";
    public static final String GUARDRAIL_REDACTION_COUNT = "guardrail.redaction_count";
}
