package com.agent.tools.context.events;

/**
 * Immutable event representing a mutation or lifecycle transition of an entry in the Blackboard.
 *
 * @param eventType the lifecycle action (CREATED, UPDATED, EXPIRED, DELETED)
 * @param namespace the target namespace
 * @param key       the unique entry key
 * @param value     the entry payload at the time of the event
 * @param timestamp epoch millisecond timestamp of the event
 */
public record BlackboardEvent(
        BlackboardEventType eventType,
        String namespace,
        String key,
        String value,
        long timestamp
) {
    public BlackboardEvent(BlackboardEventType eventType, String namespace, String key, String value) {
        this(eventType, namespace, key, value, System.currentTimeMillis());
    }
}
