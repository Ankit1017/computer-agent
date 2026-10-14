package com.agent.tools.context.services;

import com.agent.tools.context.ContextToolsFacade;
import com.agent.tools.context.events.BlackboardEvent;
import com.agent.tools.context.events.BlackboardEventType;
import com.agent.tools.context.events.BlackboardSubscription;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class BlackboardReactiveTest {

    private BlackboardService service;

    @BeforeEach
    void setUp() {
        service = new BlackboardService();
    }

    @AfterEach
    void tearDown() {
        service.shutdown();
    }

    @Test
    @DisplayName("Should asynchronously notify listener with CREATED and UPDATED events")
    void shouldNotifyListenerOnCreateAndProduceUpdate() throws Exception {
        BlockingQueue<BlackboardEvent> eventQueue = new LinkedBlockingQueue<>();

        // Subscribe to "core" namespace and specific key "deploymentState"
        service.addListener("core", "deploymentState", eventQueue::offer);

        // 1. Initial write triggers CREATED event
        service.writeData("core", "deploymentState", "INITIALIZING");
        BlackboardEvent createEvent = eventQueue.poll(2, TimeUnit.SECONDS);

        assertThat(createEvent).isNotNull();
        assertThat(createEvent.eventType()).isEqualTo(BlackboardEventType.CREATED);
        assertThat(createEvent.namespace()).isEqualTo("core");
        assertThat(createEvent.key()).isEqualTo("deploymentState");
        assertThat(createEvent.value()).isEqualTo("INITIALIZING");
        assertThat(createEvent.timestamp()).isGreaterThan(0L);

        // 2. Overwrite triggers UPDATED event
        service.writeData("core", "deploymentState", "DEPLOYED");
        BlackboardEvent updateEvent = eventQueue.poll(2, TimeUnit.SECONDS);

        assertThat(updateEvent).isNotNull();
        assertThat(updateEvent.eventType()).isEqualTo(BlackboardEventType.UPDATED);
        assertThat(updateEvent.namespace()).isEqualTo("core");
        assertThat(updateEvent.key()).isEqualTo("deploymentState");
        assertThat(updateEvent.value()).isEqualTo("DEPLOYED");
    }

    @Test
    @DisplayName("Should broadcast EXPIRED event when TTL entries are purged during cleanup sweep")
    void shouldBroadcastExpiredEventOnTtlCleanup() throws Exception {
        BlockingQueue<BlackboardEvent> eventQueue = new LinkedBlockingQueue<>();
        service.addListener("scratchpad", "tempSessionToken", eventQueue::offer);

        // Write with 1-second TTL
        service.writeData("scratchpad", "tempSessionToken", "token_abc123", 1);
        BlackboardEvent created = eventQueue.poll(2, TimeUnit.SECONDS);
        assertThat(created).isNotNull();
        assertThat(created.eventType()).isEqualTo(BlackboardEventType.CREATED);

        // Wait for TTL expiration (1.1s)
        Thread.sleep(1100);

        // Trigger cleanup sweep
        service.cleanupExpiredEntries();

        BlackboardEvent expired = eventQueue.poll(2, TimeUnit.SECONDS);
        assertThat(expired).isNotNull();
        assertThat(expired.eventType()).isEqualTo(BlackboardEventType.EXPIRED);
        assertThat(expired.namespace()).isEqualTo("scratchpad");
        assertThat(expired.key()).isEqualTo("tempSessionToken");
        assertThat(expired.value()).isEqualTo("token_abc123");
    }

    @Test
    @DisplayName("Should emit DELETED event upon explicit key deletion")
    void shouldEmitDeletedEventOnExplicitDeletion() throws Exception {
        BlockingQueue<BlackboardEvent> eventQueue = new LinkedBlockingQueue<>();
        service.addListener("global", "removableKey", eventQueue::offer);

        service.writeData("global", "removableKey", "temporaryPayload");
        eventQueue.poll(2, TimeUnit.SECONDS); // Drain CREATED

        boolean deleted = service.deleteData("global", "removableKey");
        assertThat(deleted).isTrue();

        BlackboardEvent deletedEvent = eventQueue.poll(2, TimeUnit.SECONDS);
        assertThat(deletedEvent).isNotNull();
        assertThat(deletedEvent.eventType()).isEqualTo(BlackboardEventType.DELETED);
        assertThat(deletedEvent.key()).isEqualTo("removableKey");
        assertThat(deletedEvent.value()).isEqualTo("temporaryPayload");

        // Deleting non-existent key returns false and produces no event
        assertThat(service.deleteData("global", "nonExistentKey")).isFalse();
        assertThat(eventQueue.poll(200, TimeUnit.MILLISECONDS)).isNull();
    }

    @Test
    @DisplayName("Should enforce topic filtering and support unsubscription")
    void shouldEnforceTopicFilteringAndUnsubscribe() throws Exception {
        CopyOnWriteArrayList<BlackboardEvent> wildcardEvents = new CopyOnWriteArrayList<>();
        CopyOnWriteArrayList<BlackboardEvent> opsEvents = new CopyOnWriteArrayList<>();

        BlackboardSubscription wildcardSub = service.addListener(wildcardEvents::add);
        BlackboardSubscription opsSub = service.addListener("ops", opsEvents::add);

        // Write to "global"
        service.writeData("global", "gKey", "gVal");
        // Write to "ops"
        service.writeData("ops", "oKey", "oVal");

        Thread.sleep(200);

        // Wildcard should have seen both events
        assertThat(wildcardEvents).hasSize(2);
        // Ops should have only seen 1 event from "ops" namespace
        assertThat(opsEvents).hasSize(1);
        assertThat(opsEvents.get(0).namespace()).isEqualTo("ops");

        // Unsubscribe ops listener
        opsSub.close();
        service.writeData("ops", "oKey2", "oVal2");

        Thread.sleep(200);

        // Ops listener receives no new events
        assertThat(opsEvents).hasSize(1);
        // Wildcard listener still active
        assertThat(wildcardEvents).hasSize(3);

        wildcardSub.close();
    }

    @Test
    @DisplayName("Should reactively await value written by another thread without busy-wait polling")
    void shouldReactivelyAwaitValueAcrossThreads() throws Exception {
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        CountDownLatch readyToWait = new CountDownLatch(1);
        AtomicReference<String> resolvedValue = new AtomicReference<>();

        executor.submit(() -> {
            readyToWait.countDown();
            String val = service.awaitValue("cluster", "leaderId", 5);
            resolvedValue.set(val);
        });

        readyToWait.await();
        // Give listener subscription a moment to attach
        Thread.sleep(100);

        // Write from producer thread
        service.writeData("cluster", "leaderId", "node-42-leader");

        // Wait for completion
        long deadline = System.currentTimeMillis() + 2000;
        while (resolvedValue.get() == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }

        assertThat(resolvedValue.get()).isEqualTo("node-42-leader");
        executor.shutdown();
    }

    @Test
    @DisplayName("Should return existing value immediately when awaiting an already active key")
    void shouldReturnExistingValueImmediatelyOnAwait() {
        service.writeData("config", "maxRetries", "3");

        long start = System.currentTimeMillis();
        String result = service.awaitValue("config", "maxRetries", 5);
        long elapsed = System.currentTimeMillis() - start;

        assertThat(result).isEqualTo("3");
        assertThat(elapsed).isLessThan(200);
    }

    @Test
    @DisplayName("Should support ContextToolsFacade delete and await tool methods")
    void shouldSupportContextToolsFacadeMethods() {
        ContextToolsFacade facade = new ContextToolsFacade(service);

        // Write and delete
        facade.writeToNamespacedBlackboard("inventory", "sku123", "available");
        assertThat(facade.readFromNamespacedBlackboard("inventory", "sku123")).isEqualTo("available");

        String deleteMsg = facade.deleteFromBlackboard("inventory", "sku123");
        assertThat(deleteMsg).contains("Deleted key 'sku123' from namespace 'inventory'.");
        assertThat(facade.readFromNamespacedBlackboard("inventory", "sku123")).contains("not found in namespace");

        // Await with immediate resolution
        facade.writeToNamespacedBlackboard("signals", "ready", "true");
        String signal = facade.awaitBlackboardKey("signals", "ready", 2);
        assertThat(signal).isEqualTo("true");
    }
}
