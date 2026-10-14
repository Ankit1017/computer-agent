package com.agent.orchestration.workflow.model;

/**
 * Exception thrown when dynamic edge routing cannot resolve a valid outbound transition
 * in a workflow graph (e.g., no conditions evaluated to true and no default edge exists).
 */
public class WorkflowRoutingException extends IllegalStateException {

    public WorkflowRoutingException(String message) {
        super(message);
    }

    public WorkflowRoutingException(String message, Throwable cause) {
        super(message, cause);
    }
}
