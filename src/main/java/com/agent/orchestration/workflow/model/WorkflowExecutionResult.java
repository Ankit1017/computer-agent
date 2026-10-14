package com.agent.orchestration.workflow.model;

import com.agent.orchestration.workflow.checkpoint.WorkflowExecutionStatus;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Structured outcome returned by the workflow execution subsystem,
 * detailing run identification, lifecycle execution status,
 * interrupted nodes (if suspended), resulting state snapshot, and diagnostic message.
 *
 * @param threadId         unique execution thread identifier
 * @param workflowId       identifier of the executed workflow
 * @param status           lifecycle status (COMPLETED, SUSPENDED, FAILED)
 * @param interruptedNodes set of node identifiers triggering an interrupt, if suspended
 * @param stateData        unmodifiable snapshot of the resulting state payload
 * @param message          human- and LLM-readable summary of the execution outcome
 */
public record WorkflowExecutionResult(
        @JsonProperty("threadId") String threadId,
        @JsonProperty("workflowId") String workflowId,
        @JsonProperty("status") WorkflowExecutionStatus status,
        @JsonProperty("interruptedNodes") Set<String> interruptedNodes,
        @JsonProperty("stateData") Map<String, Object> stateData,
        @JsonProperty("message") String message
) {

    @JsonCreator
    public WorkflowExecutionResult(
            @JsonProperty("threadId") String threadId,
            @JsonProperty("workflowId") String workflowId,
            @JsonProperty("status") WorkflowExecutionStatus status,
            @JsonProperty("interruptedNodes") Set<String> interruptedNodes,
            @JsonProperty("stateData") Map<String, Object> stateData,
            @JsonProperty("message") String message
    ) {
        this.threadId = Objects.requireNonNull(threadId, "threadId must not be null");
        this.workflowId = Objects.requireNonNull(workflowId, "workflowId must not be null");
        this.status = status != null ? status : WorkflowExecutionStatus.COMPLETED;
        this.interruptedNodes = interruptedNodes != null
                ? Collections.unmodifiableSet(new LinkedHashSet<>(interruptedNodes))
                : Collections.emptySet();
        this.stateData = stateData != null
                ? Collections.unmodifiableMap(new LinkedHashMap<>(stateData))
                : Collections.emptyMap();
        this.message = message != null ? message : "";
    }

    public boolean isSuspended() {
        return status == WorkflowExecutionStatus.SUSPENDED;
    }

    public boolean isCompleted() {
        return status == WorkflowExecutionStatus.COMPLETED;
    }

    public boolean isFailed() {
        return status == WorkflowExecutionStatus.FAILED;
    }
}
