package com.agent.orchestration.workflow.checkpoint;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * File-based implementation of {@link WorkflowCheckpointer} that persists checkpoints
 * as indented JSON documents using Jackson. Provides crash-resilience across JVM restarts.
 */
public class JsonFileCheckpointer implements WorkflowCheckpointer {

    private static final Logger log = LoggerFactory.getLogger(JsonFileCheckpointer.class);
    private static final String FILE_PREFIX = "checkpoint_step_";
    private static final String FILE_SUFFIX = ".json";

    private final Path baseDirectory;
    private final ObjectMapper objectMapper;

    public JsonFileCheckpointer(Path baseDirectory) {
        this(baseDirectory, new ObjectMapper());
    }

    public JsonFileCheckpointer(File baseDirectory) {
        this(Objects.requireNonNull(baseDirectory, "baseDirectory must not be null").toPath(), new ObjectMapper());
    }

    public JsonFileCheckpointer(Path baseDirectory, ObjectMapper objectMapper) {
        this.baseDirectory = Objects.requireNonNull(baseDirectory, "baseDirectory must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    @Override
    public synchronized void save(WorkflowCheckpoint checkpoint) {
        Objects.requireNonNull(checkpoint, "checkpoint must not be null");

        Path threadDir = resolveThreadDir(checkpoint.threadId());
        try {
            Files.createDirectories(threadDir);

            String fileName = String.format("%s%04d%s", FILE_PREFIX, checkpoint.superstep(), FILE_SUFFIX);
            Path targetFile = threadDir.resolve(fileName);
            Path tempFile = Files.createTempFile(threadDir, "cp_tmp_", ".tmp");

            Map<String, Object> stateData = checkpoint.stateData();
            WorkflowCheckpoint checkpointToSave = checkpoint;
            if (stateData != null && !stateData.isEmpty()) {
                Map<String, Object> redactedState = com.agent.guardrails.SecretRedactor.redactMap(stateData);
                if (!redactedState.equals(stateData)) {
                    checkpointToSave = new WorkflowCheckpoint(
                            checkpoint.threadId(),
                            checkpoint.workflowId(),
                            checkpoint.superstep(),
                            checkpoint.activeNodes(),
                            redactedState,
                            checkpoint.timestamp(),
                            checkpoint.status(),
                            checkpoint.interruptedNodes(),
                            checkpoint.interruptType(),
                            checkpoint.completedNodes()
                    );
                }
            }

            objectMapper.writerWithDefaultPrettyPrinter().writeValue(tempFile.toFile(), checkpointToSave);

            try {
                Files.move(tempFile, targetFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tempFile, targetFile, StandardCopyOption.REPLACE_EXISTING);
            }

            log.debug("[CHECKPOINT] Persisted checkpoint for thread '{}' superstep {} to '{}'",
                    checkpoint.threadId(), checkpoint.superstep(), targetFile);

        } catch (IOException e) {
            log.error("Failed to persist checkpoint for thread '{}' superstep {}: {}",
                    checkpoint.threadId(), checkpoint.superstep(), e.getMessage(), e);
            throw new IllegalStateException("Failed to persist checkpoint to disk: " + e.getMessage(), e);
        }
    }

    @Override
    public Optional<WorkflowCheckpoint> loadLatest(String threadId) {
        if (threadId == null || threadId.isBlank()) {
            return Optional.empty();
        }

        List<WorkflowCheckpoint> history = listHistory(threadId);
        if (history.isEmpty()) {
            return Optional.empty();
        }

        return Optional.of(history.get(history.size() - 1));
    }

    @Override
    public List<WorkflowCheckpoint> listHistory(String threadId) {
        if (threadId == null || threadId.isBlank()) {
            return Collections.emptyList();
        }

        Path threadDir = resolveThreadDir(threadId);
        if (!Files.exists(threadDir) || !Files.isDirectory(threadDir)) {
            return Collections.emptyList();
        }

        List<WorkflowCheckpoint> checkpoints = new ArrayList<>();
        try (Stream<Path> stream = Files.list(threadDir)) {
            List<Path> files = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().startsWith(FILE_PREFIX))
                    .filter(p -> p.getFileName().toString().endsWith(FILE_SUFFIX))
                    .toList();

            for (Path file : files) {
                try {
                    WorkflowCheckpoint checkpoint = objectMapper.readValue(file.toFile(), WorkflowCheckpoint.class);
                    if (checkpoint != null) {
                        checkpoints.add(checkpoint);
                    }
                } catch (Exception e) {
                    log.warn("Corrupted or unreadable checkpoint file skipped '{}': {}", file, e.getMessage());
                }
            }
        } catch (IOException e) {
            log.error("Failed to list checkpoints for thread '{}': {}", threadId, e.getMessage(), e);
            throw new IllegalStateException("Failed to read checkpoints from disk: " + e.getMessage(), e);
        }

        checkpoints.sort(Comparator.comparingInt(WorkflowCheckpoint::superstep)
                .thenComparingLong(WorkflowCheckpoint::timestamp));

        return Collections.unmodifiableList(checkpoints);
    }

    private Path resolveThreadDir(String threadId) {
        String sanitizedId = threadId.replaceAll("[^a-zA-Z0-9._-]", "_");
        return baseDirectory.resolve(sanitizedId);
    }

    public Path getBaseDirectory() {
        return baseDirectory;
    }
}
