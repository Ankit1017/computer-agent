package com.agent.orchestration.workflow.checkpoint;

import java.util.List;
import java.util.Optional;

/**
 * Storage abstraction contract for persisting and retrieving {@link WorkflowCheckpoint}s,
 * enabling durable execution, crash recovery, and execution audit trails.
 */
public interface WorkflowCheckpointer {

    /**
     * Persists a point-in-time checkpoint.
     *
     * @param checkpoint the checkpoint to record
     */
    void save(WorkflowCheckpoint checkpoint);

    /**
     * Retrieves the most recent checkpoint for a given thread execution ID.
     *
     * @param threadId unique workflow run identifier
     * @return optional containing the latest checkpoint, or empty if none recorded
     */
    Optional<WorkflowCheckpoint> loadLatest(String threadId);

    /**
     * Retrieves the chronological audit trail of all checkpoints recorded for a thread.
     * Checkpoints are sorted by superstep in ascending order.
     *
     * @param threadId unique workflow run identifier
     * @return unmodifiable list of checkpoints
     */
    List<WorkflowCheckpoint> listHistory(String threadId);
}
