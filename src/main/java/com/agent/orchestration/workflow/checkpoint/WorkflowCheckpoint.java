package com.agent.orchestration.workflow.checkpoint;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable point-in-time snapshot representing the execution state and graph topology
 * pointers of a workflow run at the conclusion of a superstep.
 *
 * @param threadId    unique identifier for the workflow execution run
 * @param workflowId  identifier of the executing WorkflowGraph
 * @param superstep   counter of the completed superstep
 * @param activeNodes set of target node IDs scheduled for the next superstep
 * @param stateData   deep snapshot of the WorkflowState payload
 * @param timestamp   epoch milliseconds when the checkpoint was created
 */
public record WorkflowCheckpoint(
        @JsonProperty("threadId") String threadId,
        @JsonProperty("workflowId") String workflowId,
        @JsonProperty("superstep") int superstep,
        @JsonProperty("activeNodes") Set<String> activeNodes,
        @JsonProperty("stateData") Map<String, Object> stateData,
        @JsonProperty("timestamp") long timestamp
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
    }

    @JsonCreator
    public static WorkflowCheckpoint fromJson(
            @JsonProperty("threadId") String threadId,
            @JsonProperty("workflowId") String workflowId,
            @JsonProperty("superstep") int superstep,
            @JsonProperty("activeNodes") Set<String> activeNodes,
            @JsonProperty("stateData") Map<String, Object> stateData,
            @JsonProperty("timestamp") Long timestamp
    ) {
        return new WorkflowCheckpoint(
                threadId,
                workflowId,
                superstep,
                activeNodes,
                stateData,
                timestamp != null ? timestamp : System.currentTimeMillis()
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
}
