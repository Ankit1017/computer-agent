package com.agent.tools.context.events;

/**
 * Handle representing an active Blackboard listener subscription.
 * Implements AutoCloseable to allow unregistering via close().
 */
@FunctionalInterface
public interface BlackboardSubscription extends AutoCloseable {

    /**
     * Unsubscribes the registered listener from further event notifications.
     */
    @Override
    void close();
}
