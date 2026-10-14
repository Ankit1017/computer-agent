package com.agent.tools.context.services;

import com.agent.tools.context.events.BlackboardEvent;
import com.agent.tools.context.events.BlackboardEventType;
import com.agent.tools.context.events.BlackboardListener;
import com.agent.tools.context.events.BlackboardSubscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.stream.Collectors;

public class BlackboardService {

    private static final Logger log = LoggerFactory.getLogger(BlackboardService.class);

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

    private static class SubscriptionEntry {
        final String namespace; // normalized or "*"
        final String key;       // normalized or "*"
        final BlackboardListener listener;

        SubscriptionEntry(String namespace, String key, BlackboardListener listener) {
            this.namespace = (namespace == null || namespace.isBlank() || namespace.equals("*")) ? "*" : namespace.trim().toLowerCase();
            this.key = (key == null || key.isBlank() || key.equals("*")) ? "*" : key.trim();
            this.listener = listener;
        }

        boolean matches(String eventNs, String eventKey) {
            boolean nsMatch = "*".equals(this.namespace) || this.namespace.equalsIgnoreCase(eventNs);
            boolean keyMatch = "*".equals(this.key) || this.key.equals(eventKey);
            return nsMatch && keyMatch;
        }
    }

    private final Map<String, Map<String, BlackboardEntry>> namespaces = new ConcurrentHashMap<>();
    private final List<SubscriptionEntry> subscriptions = new CopyOnWriteArrayList<>();
    private final ExecutorService eventExecutor = Executors.newVirtualThreadPerTaskExecutor();
    
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

    /**
     * Subscribes a listener to all events across all namespaces and keys.
     */
    public BlackboardSubscription addListener(BlackboardListener listener) {
        return addListener("*", "*", listener);
    }

    /**
     * Subscribes a listener to all events within a specific namespace.
     */
    public BlackboardSubscription addListener(String namespace, BlackboardListener listener) {
        return addListener(namespace, "*", listener);
    }

    /**
     * Subscribes a listener to events matching a specific namespace and key path.
     */
    public BlackboardSubscription addListener(String namespace, String key, BlackboardListener listener) {
        if (listener == null) {
            throw new IllegalArgumentException("listener must not be null");
        }
        SubscriptionEntry entry = new SubscriptionEntry(namespace, key, listener);
        subscriptions.add(entry);
        return () -> subscriptions.remove(entry);
    }

    /**
     * Unsubscribes a listener from all registered subscription topics.
     */
    public void removeListener(BlackboardListener listener) {
        if (listener != null) {
            subscriptions.removeIf(s -> s.listener.equals(listener));
        }
    }

    /**
     * Returns the number of currently active subscriptions.
     */
    public int getListenerCount() {
        return subscriptions.size();
    }

    /**
     * Asynchronously disseminates a BlackboardEvent across matching listeners on virtual threads.
     */
    public void publishEvent(BlackboardEventType type, String namespace, String key, String value) {
        if (subscriptions.isEmpty()) {
            return;
        }
        BlackboardEvent event = new BlackboardEvent(type, namespace, key, value);
        eventExecutor.submit(() -> {
            for (SubscriptionEntry sub : subscriptions) {
                if (sub.matches(namespace, key)) {
                    try {
                        sub.listener.onBlackboardEvent(event);
                    } catch (Exception e) {
                        log.warn("[BLACKBOARD] Listener error during {} event: {}", type, e.getMessage());
                    }
                }
            }
        });
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
        Map<String, BlackboardEntry> space = namespaces.computeIfAbsent(ns, k -> new ConcurrentHashMap<>());
        BlackboardEntry oldEntry = space.put(trimmedKey, new BlackboardEntry(value, ttlSeconds));

        BlackboardEventType type = (oldEntry != null && !oldEntry.isExpired())
                ? BlackboardEventType.UPDATED
                : BlackboardEventType.CREATED;
        publishEvent(type, ns, trimmedKey, value);

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
            if (entry != null && entry.isExpired()) {
                space.remove(trimmedKey);
                publishEvent(BlackboardEventType.EXPIRED, ns, trimmedKey, entry.value);
            }
            return "Error: Key '" + trimmedKey + "' has expired and was removed.";
        }
        return entry.value != null ? entry.value : "";
    }

    /**
     * Explicitly removes a key from the specified namespace, emitting a DELETED event if present.
     */
    public boolean deleteData(String namespace, String key) {
        if (key == null || key.isBlank()) {
            return false;
        }
        String ns = (namespace == null || namespace.isBlank()) ? "global" : namespace.trim().toLowerCase();
        String trimmedKey = key.trim();
        Map<String, BlackboardEntry> space = namespaces.get(ns);
        if (space != null) {
            BlackboardEntry removed = space.remove(trimmedKey);
            if (removed != null && !removed.isExpired()) {
                publishEvent(BlackboardEventType.DELETED, ns, trimmedKey, removed.value);
                return true;
            }
        }
        return false;
    }

    /**
     * Awaits a matching blackboard event asynchronously without polling or busy-waiting.
     */
    public CompletableFuture<BlackboardEvent> awaitEvent(String namespace, String key, long timeout, TimeUnit unit) {
        CompletableFuture<BlackboardEvent> future = new CompletableFuture<>();
        String targetNs = (namespace == null || namespace.isBlank()) ? "global" : namespace.trim().toLowerCase();
        String targetKey = (key != null && !key.isBlank()) ? key.trim() : "*";

        BlackboardSubscription sub = addListener(targetNs, targetKey, event -> {
            if (!future.isDone()) {
                future.complete(event);
            }
        });

        future.whenComplete((res, err) -> sub.close());

        if (timeout > 0 && unit != null) {
            cleanupExecutor.schedule(() -> {
                if (!future.isDone()) {
                    future.completeExceptionally(new TimeoutException(
                            "Timed out awaiting blackboard event in namespace '" + targetNs + "' for key '" + targetKey + "'"
                    ));
                }
            }, timeout, unit);
        }

        return future;
    }

    /**
     * Awaits an active key value in the specified namespace.
     * If the key already exists and is active, returns immediately; otherwise waits reactively.
     */
    public String awaitValue(String namespace, String key, long timeoutSeconds) {
        String current = readData(namespace, key);
        if (current != null && !current.startsWith("Error:")) {
            return current;
        }
        try {
            BlackboardEvent event = awaitEvent(namespace, key, timeoutSeconds, TimeUnit.SECONDS).get();
            return event.value();
        } catch (Exception e) {
            return "Error: " + (e.getCause() != null ? e.getCause().getMessage() : e.getMessage());
        }
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

    /**
     * Scans namespaces for expired entries, removes them, and broadcasts EXPIRED events.
     */
    void cleanupExpiredEntries() {
        namespaces.forEach((ns, space) -> {
            space.entrySet().removeIf(entry -> {
                BlackboardEntry be = entry.getValue();
                if (be == null) {
                    return true;
                }
                if (be.isExpired()) {
                    publishEvent(BlackboardEventType.EXPIRED, ns, entry.getKey(), be.value);
                    return true;
                }
                return false;
            });
        });
    }

    public void shutdown() {
        System.out.println("[BLACKBOARD] Shutting down TTL cleanup service...");
        cleanupExecutor.shutdownNow();
        eventExecutor.shutdown();
    }
}

