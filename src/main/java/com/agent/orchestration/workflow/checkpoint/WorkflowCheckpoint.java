package com.agent.orchestration.workflow.checkpoint;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable point-in-time snapshot representing the execution state, graph topology
 * pointers, cooperative interrupt status, and completed node history of a workflow run.
 *
 * @param threadId         unique identifier for the workflow execution run
 * @param workflowId       identifier of the executing WorkflowGraph
 * @param superstep        counter of the superstep
 * @param activeNodes      set of target node IDs scheduled for the next superstep
 * @param stateData        deep snapshot of the WorkflowState payload
 * @param timestamp        epoch milliseconds when the checkpoint was created
 * @param status           lifecycle execution status (RUNNING, SUSPENDED, COMPLETED, FAILED, ROLLED_BACK)
 * @param interruptedNodes set of node IDs that caused or are subject to an interrupt breakpoint
 * @param interruptType    type of interrupt ("BEFORE" or "AFTER"), or null if normal boundary
 * @param completedNodes   chronological list of completed node IDs across supersteps for Saga rollbacks
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record WorkflowCheckpoint(
        @JsonProperty("threadId") String threadId,
        @JsonProperty("workflowId") String workflowId,
        @JsonProperty("superstep") int superstep,
        @JsonProperty("activeNodes") Set<String> activeNodes,
        @JsonProperty("stateData") Map<String, Object> stateData,
        @JsonProperty("timestamp") long timestamp,
        @JsonProperty("status") WorkflowExecutionStatus status,
        @JsonProperty("interruptedNodes") Set<String> interruptedNodes,
        @JsonProperty("interruptType") String interruptType,
        @JsonProperty("completedNodes") List<String> completedNodes
) {

    public WorkflowCheckpoint {
        Objects.requireNonNull(threadId, "threadId must not be null");
        Objects.requireNonNull(workflowId, "workflowId must not be null");
        activeNodes = activeNodes != null
                ? Collections.unmodifiableSet(new LinkedHashSet<>(activeNodes))
                : Collections.emptySet();
        stateData = stateData != null
                ? Collections.unmodifiableMap(new LinkedHashMap<>(stateData))
                : Collections.emptyMap();
        interruptedNodes = interruptedNodes != null
                ? Collections.unmodifiableSet(new LinkedHashSet<>(interruptedNodes))
                : Collections.emptySet();
        completedNodes = completedNodes != null
                ? Collections.unmodifiableList(new ArrayList<>(completedNodes))
                : Collections.emptyList();

        if (status == null) {
            status = isTerminal(activeNodes)
                    ? WorkflowExecutionStatus.COMPLETED
                    : WorkflowExecutionStatus.RUNNING;
        }

        interruptType = (interruptType != null && !interruptType.isBlank())
                ? interruptType.trim().toUpperCase()
                : null;
    }

    @JsonCreator
    public static WorkflowCheckpoint fromJson(
            @JsonProperty("threadId") String threadId,
            @JsonProperty("workflowId") String workflowId,
            @JsonProperty("superstep") int superstep,
            @JsonProperty("activeNodes") Set<String> activeNodes,
            @JsonProperty("stateData") Map<String, Object> stateData,
            @JsonProperty("timestamp") Long timestamp,
            @JsonProperty("status") WorkflowExecutionStatus status,
            @JsonProperty("interruptedNodes") Set<String> interruptedNodes,
            @JsonProperty("interruptType") String interruptType,
            @JsonProperty("completedNodes") List<String> completedNodes
    ) {
        return new WorkflowCheckpoint(
                threadId,
                workflowId,
                superstep,
                activeNodes,
                stateData,
                timestamp != null ? timestamp : System.currentTimeMillis(),
                status,
                interruptedNodes,
                interruptType,
                completedNodes
        );
    }

    public WorkflowCheckpoint(
            String threadId,
            String workflowId,
            int superstep,
            Set<String> activeNodes,
            Map<String, Object> stateData,
            long timestamp,
            WorkflowExecutionStatus status,
            Set<String> interruptedNodes,
            String interruptType
    ) {
        this(threadId, workflowId, superstep, activeNodes, stateData, timestamp, status, interruptedNodes, interruptType, Collections.emptyList());
    }

    public WorkflowCheckpoint(
            String threadId,
            String workflowId,
            int superstep,
            Set<String> activeNodes,
            Map<String, Object> stateData,
            long timestamp
    ) {
        this(
                threadId,
                workflowId,
                superstep,
                activeNodes,
                stateData,
                timestamp,
                isTerminal(activeNodes) ? WorkflowExecutionStatus.COMPLETED : WorkflowExecutionStatus.RUNNING,
                Collections.emptySet(),
                null,
                Collections.emptyList()
        );
    }

    public WorkflowCheckpoint(
            String threadId,
            String workflowId,
            int superstep,
            Set<String> activeNodes,
            Map<String, Object> stateData
    ) {
        this(threadId, workflowId, superstep, activeNodes, stateData, System.currentTimeMillis());
    }

    @JsonIgnore
    public boolean isSuspended() {
        return status == WorkflowExecutionStatus.SUSPENDED;
    }

    @JsonIgnore
    public boolean isCompleted() {
        return status == WorkflowExecutionStatus.COMPLETED;
    }

    @JsonIgnore
    public boolean isRolledBack() {
        return status == WorkflowExecutionStatus.ROLLED_BACK;
    }

    private static boolean isTerminal(Set<String> nodes) {
        return nodes != null && !nodes.isEmpty() && nodes.stream().allMatch("__END__"::equalsIgnoreCase);
    }
}
