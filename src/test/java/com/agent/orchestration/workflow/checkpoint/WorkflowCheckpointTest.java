package com.agent.orchestration.workflow.checkpoint;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkflowCheckpointTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("Should create immutable WorkflowCheckpoint and cleanly serialize/deserialize to JSON")
    void shouldSerializeAndDeserializeWorkflowCheckpoint() throws IOException {
        WorkflowCheckpoint original = new WorkflowCheckpoint(
                "thread-123",
                "sample_workflow",
                2,
                Set.of("node_3a", "node_3b"),
                Map.of(
                        "status", "IN_PROGRESS",
                        "notes", List.of("Fact 1", "Fact 2"),
                        "metrics", Map.of("cpu", 42.5)
                ),
                1700000000000L
        );

        String json = objectMapper.writeValueAsString(original);
        WorkflowCheckpoint deserialized = objectMapper.readValue(json, WorkflowCheckpoint.class);

        assertThat(deserialized.threadId()).isEqualTo("thread-123");
        assertThat(deserialized.workflowId()).isEqualTo("sample_workflow");
        assertThat(deserialized.superstep()).isEqualTo(2);
        assertThat(deserialized.activeNodes()).containsExactlyInAnyOrder("node_3a", "node_3b");
        assertThat(deserialized.stateData()).containsEntry("status", "IN_PROGRESS");
        assertThat(deserialized.timestamp()).isEqualTo(1700000000000L);

        // Verify immutability of returned collections
        assertThatThrownBy(() -> deserialized.activeNodes().add("illegal_node"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> deserialized.stateData().put("illegal_key", "val"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("Should correctly record, retrieve latest, and order audit trail in InMemoryCheckpointer")
    void shouldManageCheckpointsInInMemoryCheckpointer() {
        InMemoryCheckpointer checkpointer = new InMemoryCheckpointer();

        assertThat(checkpointer.loadLatest("thread-empty")).isEmpty();
        assertThat(checkpointer.listHistory("thread-empty")).isEmpty();

        WorkflowCheckpoint step1 = new WorkflowCheckpoint("thread-1", "wf-A", 1, Set.of("node_2"), Map.of("step", 1));
        WorkflowCheckpoint step2 = new WorkflowCheckpoint("thread-1", "wf-A", 2, Set.of("node_3"), Map.of("step", 2));
        WorkflowCheckpoint step3 = new WorkflowCheckpoint("thread-1", "wf-A", 3, Set.of("__END__"), Map.of("step", 3));

        // Save out of order to verify chronological sorting
        checkpointer.save(step2);
        checkpointer.save(step1);
        checkpointer.save(step3);

        Optional<WorkflowCheckpoint> latest = checkpointer.loadLatest("thread-1");
        assertThat(latest).isPresent();
        assertThat(latest.get().superstep()).isEqualTo(3);
        assertThat(latest.get().activeNodes()).containsExactly("__END__");

        List<WorkflowCheckpoint> history = checkpointer.listHistory("thread-1");
        assertThat(history).hasSize(3);
        assertThat(history.get(0).superstep()).isEqualTo(1);
        assertThat(history.get(1).superstep()).isEqualTo(2);
        assertThat(history.get(2).superstep()).isEqualTo(3);
    }

    @Test
    @DisplayName("Should persist and restore checkpoints across disk using JsonFileCheckpointer with @TempDir")
    void shouldPersistAndRestoreCheckpointsWithJsonFileCheckpointer(@TempDir Path tempDir) {
        JsonFileCheckpointer fileCheckpointer = new JsonFileCheckpointer(tempDir);

        WorkflowCheckpoint cp1 = new WorkflowCheckpoint(
                "thread-disk-1",
                "disk_wf",
                1,
                Set.of("worker_node"),
                Map.of("artifacts", List.of("art_1", "art_2"), "status", "RUNNING")
        );
        WorkflowCheckpoint cp2 = new WorkflowCheckpoint(
                "thread-disk-1",
                "disk_wf",
                2,
                Set.of("__END__"),
                Map.of("artifacts", List.of("art_1", "art_2", "art_3"), "status", "COMPLETE")
        );

        fileCheckpointer.save(cp1);
        fileCheckpointer.save(cp2);

        // Verify separate instance pointing to the same directory can rehydrate checkpoints
        JsonFileCheckpointer readerCheckpointer = new JsonFileCheckpointer(tempDir);

        Optional<WorkflowCheckpoint> latest = readerCheckpointer.loadLatest("thread-disk-1");
        assertThat(latest).isPresent();
        assertThat(latest.get().superstep()).isEqualTo(2);
        assertThat(latest.get().activeNodes()).containsExactly("__END__");
        assertThat(latest.get().stateData()).containsEntry("status", "COMPLETE");

        @SuppressWarnings("unchecked")
        List<String> artifacts = (List<String>) latest.get().stateData().get("artifacts");
        assertThat(artifacts).containsExactly("art_1", "art_2", "art_3");

        List<WorkflowCheckpoint> history = readerCheckpointer.listHistory("thread-disk-1");
        assertThat(history).hasSize(2);
        assertThat(history.get(0).superstep()).isEqualTo(1);
        assertThat(history.get(1).superstep()).isEqualTo(2);
    }
}
