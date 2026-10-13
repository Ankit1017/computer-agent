package com.agent.tools.context.services;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public class BlackboardService {

    private static class BlackboardEntry {
        final String value;
        final long expiryTimeMillis;

        BlackboardEntry(String value, long ttlSeconds) {
            this.value = (value != null) ? value : "";
            if (ttlSeconds > 0) {
                // Prevent long arithmetic overflow by capping at 10 years (315,360,000 seconds)
                long safeTtl = Math.min(ttlSeconds, 315_360_000L);
                this.expiryTimeMillis = System.currentTimeMillis() + (safeTtl * 1000L);
            } else {
                this.expiryTimeMillis = 0;
            }
        }

        boolean isExpired() {
            return expiryTimeMillis > 0 && System.currentTimeMillis() > expiryTimeMillis;
        }
    }

    private final Map<String, Map<String, BlackboardEntry>> namespaces = new ConcurrentHashMap<>();
    
    // Use a daemon thread so the cleanup task never blocks JVM shutdown
    private final ScheduledExecutorService cleanupExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "blackboard-ttl-cleanup");
        thread.setDaemon(true);
        return thread;
    });

    public BlackboardService() {
        // Pre-initialize standard enterprise namespaces
        namespaces.put("global", new ConcurrentHashMap<>());
        namespaces.put("core", new ConcurrentHashMap<>());
        namespaces.put("scratchpad", new ConcurrentHashMap<>());

        // Run TTL cleanup sweep every 10 seconds
        cleanupExecutor.scheduleAtFixedRate(this::cleanupExpiredEntries, 10, 10, TimeUnit.SECONDS);
    }

    public String writeData(String namespace, String key, String value) {
        return writeData(namespace, key, value, 0);
    }

    public String writeData(String namespace, String key, String value, long ttlSeconds) {
        if (key == null || key.isBlank()) {
            return "Error: Key cannot be empty.";
        }
        String ns = (namespace == null || namespace.isBlank()) ? "global" : namespace.trim().toLowerCase();
        String trimmedKey = key.trim();
        namespaces.computeIfAbsent(ns, k -> new ConcurrentHashMap<>())
                  .put(trimmedKey, new BlackboardEntry(value, ttlSeconds));
        
        String ttlMsg = ttlSeconds > 0 ? " (Expires in " + ttlSeconds + "s)" : " (Permanent)";
        return "Wrote key '" + trimmedKey + "' to namespace '" + ns + "'" + ttlMsg + ".";
    }

    public String readData(String namespace, String key) {
        if (key == null || key.isBlank()) {
            return "Error: Key cannot be empty.";
        }
        String ns = (namespace == null || namespace.isBlank()) ? "global" : namespace.trim().toLowerCase();
        Map<String, BlackboardEntry> space = namespaces.get(ns);
        String trimmedKey = key.trim();
        if (space == null || !space.containsKey(trimmedKey)) {
            return "Error: Key '" + trimmedKey + "' not found in namespace '" + ns + "'.";
        }
        
        BlackboardEntry entry = space.get(trimmedKey);
        if (entry == null || entry.isExpired()) {
            space.remove(trimmedKey);
            return "Error: Key '" + trimmedKey + "' has expired and was removed.";
        }
        return entry.value != null ? entry.value : "";
    }

    public String listKeys(String namespace) {
        String ns = (namespace == null || namespace.isBlank()) ? "global" : namespace.trim().toLowerCase();
        Map<String, BlackboardEntry> space = namespaces.get(ns);
        if (space == null || space.isEmpty()) {
            return "Namespace '" + ns + "' is empty.";
        }
        
        Set<String> activeKeys = space.entrySet().stream()
            .filter(e -> e.getValue() != null && !e.getValue().isExpired())
            .map(Map.Entry::getKey)
            .collect(Collectors.toSet());
            
        if (activeKeys.isEmpty()) {
            return "Namespace '" + ns + "' is empty.";
        }
        return "Active keys in '" + ns + "': " + activeKeys;
    }

    /**
     * Dumps all active key-value pairs in a namespace as a formatted string.
     * Returns an empty string if the namespace is empty or doesn't exist.
     */
    public String dumpNamespace(String namespace) {
        String ns = (namespace == null || namespace.isBlank()) ? "global" : namespace.trim().toLowerCase();
        Map<String, BlackboardEntry> space = namespaces.get(ns);
        if (space == null || space.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        space.forEach((key, entry) -> {
            if (entry != null && !entry.isExpired()) {
                sb.append("- ").append(key).append(": ").append(entry.value).append("\n");
            }
        });

        return sb.toString().trim();
    }

    private void cleanupExpiredEntries() {
        namespaces.values().forEach(space -> space.entrySet().removeIf(e -> e.getValue() == null || e.getValue().isExpired()));
    }

    public void shutdown() {
        System.out.println("[BLACKBOARD] Shutting down TTL cleanup service...");
        cleanupExecutor.shutdownNow();
    }
}
