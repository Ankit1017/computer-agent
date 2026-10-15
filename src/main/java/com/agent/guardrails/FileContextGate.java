package com.agent.guardrails;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe Read-Before-Write and Stale-Write detection gate.
 * Tracks file read snapshots (SHA-256 content hashes and timestamps) across canonical paths
 * and blocks modifications to unread or externally modified existing files.
 */
public class FileContextGate {

    public static final String REASON_READ_BEFORE_WRITE = "READ_BEFORE_WRITE";
    public static final String REASON_STALE_FILE_CONTEXT = "STALE_FILE_CONTEXT";

    public record FileSnapshot(String sha256Hex, long lastModifiedMillis) {}

    private static final Map<Path, FileSnapshot> SNAPSHOTS = new ConcurrentHashMap<>();

    private FileContextGate() {}

    /**
     * Records a read snapshot (SHA-256 hash and lastModifiedTime) for an existing file.
     *
     * @param filePath target file path
     */
    public static void recordRead(Path filePath) {
        if (filePath == null) return;
        Path canonical = filePath.toAbsolutePath().normalize();
        if (!Files.exists(canonical) || Files.isDirectory(canonical)) {
            return;
        }

        try {
            String sha256 = computeSha256(canonical);
            long lastModified = Files.getLastModifiedTime(canonical).toMillis();
            SNAPSHOTS.put(canonical, new FileSnapshot(sha256, lastModified));
        } catch (Exception e) {
            // Non-critical hash failure does not abort caller
        }
    }

    /**
     * Validates whether a file write, replace, or regex mutation is allowed.
     *
     * @param filePath target file path
     * @return GuardrailDecision indicating whether the write is allowed or blocked
     */
    public static GuardrailDecision validateWrite(Path filePath) {
        if (filePath == null) {
            return GuardrailDecision.allow();
        }

        Path canonical = filePath.toAbsolutePath().normalize();

        // Case A: New file creation on disk does not require prior read
        if (!Files.exists(canonical)) {
            return GuardrailDecision.allow();
        }

        String fileName = canonical.getFileName() != null ? canonical.getFileName().toString() : filePath.toString();

        // Case B: Existing file that has not been read in the current session/turn
        FileSnapshot snapshot = SNAPSHOTS.get(canonical);
        if (snapshot == null) {
            String msg = "[GUARDRAIL BLOCKED: READ_BEFORE_WRITE] Existing file '" + fileName
                    + "' cannot be modified before reading its contents. Please call readFileLines first to inspect the file before modifying it.";
            return GuardrailDecision.block(REASON_READ_BEFORE_WRITE, msg);
        }

        // Case C: Stale snapshot check - file on disk was modified externally since last read
        try {
            String currentSha256 = computeSha256(canonical);
            if (!snapshot.sha256Hex().equalsIgnoreCase(currentSha256)) {
                String msg = "[GUARDRAIL BLOCKED: STALE_FILE_CONTEXT] File '" + fileName
                        + "' has been modified externally since it was last read. Please call readFileLines again to refresh your context before applying changes.";
                return GuardrailDecision.block(REASON_STALE_FILE_CONTEXT, msg);
            }
        } catch (Exception e) {
            // If file cannot be read for hashing, block with stale context warning
            String msg = "[GUARDRAIL BLOCKED: STALE_FILE_CONTEXT] File '" + fileName
                    + "' could not be verified against last read snapshot: " + e.getMessage();
            return GuardrailDecision.block(REASON_STALE_FILE_CONTEXT, msg);
        }

        return GuardrailDecision.allow();
    }

    /**
     * Updates the recorded snapshot after a successful write/mutation on disk.
     *
     * @param filePath target file path
     */
    public static void recordWrite(Path filePath) {
        if (filePath == null) return;
        Path canonical = filePath.toAbsolutePath().normalize();
        if (!Files.exists(canonical) || Files.isDirectory(canonical)) {
            return;
        }

        try {
            String sha256 = computeSha256(canonical);
            long lastModified = Files.getLastModifiedTime(canonical).toMillis();
            SNAPSHOTS.put(canonical, new FileSnapshot(sha256, lastModified));
        } catch (Exception e) {
            // Ignore error on snapshot update
        }
    }

    /**
     * Checks if a read snapshot is currently held for the specified path.
     */
    public static boolean hasReadSnapshot(Path filePath) {
        if (filePath == null) return false;
        return SNAPSHOTS.containsKey(filePath.toAbsolutePath().normalize());
    }

    /**
     * Returns the recorded snapshot for a path, or null if unrecorded.
     */
    public static FileSnapshot getSnapshot(Path filePath) {
        if (filePath == null) return null;
        return SNAPSHOTS.get(filePath.toAbsolutePath().normalize());
    }

    /**
     * Returns total number of active read snapshots.
     */
    public static int getSnapshotCount() {
        return SNAPSHOTS.size();
    }

    /**
     * Clears all recorded read snapshots (useful for test isolation and session resets).
     */
    public static void reset() {
        SNAPSHOTS.clear();
    }

    /**
     * Computes the SHA-256 hexadecimal digest of a file.
     */
    public static String computeSha256(Path path) throws IOException {
        try (InputStream is = Files.newInputStream(path)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = is.read(buffer)) != -1) {
                digest.update(buffer, 0, bytesRead);
            }
            byte[] hash = digest.digest();
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 MessageDigest not available", e);
        }
    }
}
