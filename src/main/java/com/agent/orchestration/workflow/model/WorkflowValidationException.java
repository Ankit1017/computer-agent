package com.agent.orchestration.workflow.model;

/**
 * Exception thrown when a workflow definition fails structural or semantic validation.
 */
public class WorkflowValidationException extends IllegalArgumentException {

    public WorkflowValidationException(String message) {
        super(message);
    }

    public WorkflowValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
