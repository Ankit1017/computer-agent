package com.agent.orchestration.workflow.checkpoint;

/**
 * Lifecycle execution status of a workflow run recorded in {@link WorkflowCheckpoint}.
 */
public enum WorkflowExecutionStatus {
    /**
     * Workflow is actively executing or transitioning between supersteps.
     */
    RUNNING,

    /**
     * Workflow has cooperatively suspended execution at an interrupt breakpoint
     * (e.g. interruptBefore or interruptAfter) and is awaiting human review or resumption.
     */
    SUSPENDED,

    /**
     * Workflow has reached a terminal node (__END__) and completed successfully.
     */
    COMPLETED,

    /**
     * Workflow terminated abnormally due to an unhandled exception or routing failure.
     */
    FAILED
}
