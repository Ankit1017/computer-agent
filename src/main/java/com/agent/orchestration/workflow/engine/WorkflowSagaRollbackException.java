package com.agent.orchestration.workflow.engine;

import com.agent.orchestration.workflow.state.WorkflowState;

import java.util.Collections;
import java.util.List;

/**
 * Exception thrown when a workflow superstep execution fails and compensating rollback
 * actions are executed according to the Saga pattern in reverse chronological order (LIFO).
 */
public class WorkflowSagaRollbackException extends RuntimeException {

    private final String threadId;
    private final String workflowId;
    private final Throwable originalCause;
    private final List<String> compensatedNodes;
    private final WorkflowState finalState;

    public WorkflowSagaRollbackException(
            String threadId,
            String workflowId,
            Throwable originalCause,
            List<String> compensatedNodes,
            WorkflowState finalState
    ) {
        super(
                "Workflow '" + workflowId + "' execution in thread '" + threadId
                        + "' failed due to: " + (originalCause != null ? originalCause.getMessage() : "unknown error")
                        + ". Saga rollback executed in LIFO order for nodes: " + (compensatedNodes != null ? compensatedNodes : Collections.emptyList()),
                originalCause
        );
        this.threadId = threadId;
        this.workflowId = workflowId;
        this.originalCause = originalCause;
        this.compensatedNodes = compensatedNodes != null ? List.copyOf(compensatedNodes) : Collections.emptyList();
        this.finalState = finalState;
    }

    public String getThreadId() {
        return threadId;
    }

    public String getWorkflowId() {
        return workflowId;
    }

    public Throwable getOriginalCause() {
        return originalCause;
    }

    public List<String> getCompensatedNodes() {
        return compensatedNodes;
    }

    public WorkflowState getFinalState() {
        return finalState;
    }
}
