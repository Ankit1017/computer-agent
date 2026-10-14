package com.agent.tools.context.events;

/**
 * Functional interface for receiving asynchronous Blackboard lifecycle events.
 */
@FunctionalInterface
public interface BlackboardListener {

    /**
     * Invoked when a Blackboard event matching the subscription occurs.
     *
     * @param event the immutable blackboard event
     */
    void onBlackboardEvent(BlackboardEvent event);
}
