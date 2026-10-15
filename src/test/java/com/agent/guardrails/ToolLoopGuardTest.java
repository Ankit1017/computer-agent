package com.agent.guardrails;

import com.agent.telemetry.InMemoryTraceExporter;
import com.agent.telemetry.SpanStatus;
import com.agent.telemetry.SwarmSpan;
import com.agent.telemetry.SwarmTelemetry;
import com.agent.telemetry.TelemetryAttributes;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ToolLoopGuardTest {

    @BeforeEach
    void setUp() {
        SwarmTelemetry.reset();
        ToolLoopGuard.resetDefaults();
        SwarmTelemetry.setGuardrailsAlwaysEnabled(true);
    }

    @AfterEach
    void tearDown() {
        SwarmTelemetry.reset();
        ToolLoopGuard.resetDefaults();
    }

    @AfterAll
    static void tearDownAll() {
        SwarmTelemetry.reset();
        ToolLoopGuard.resetDefaults();
    }

    @Test
    @DisplayName("Scenario 1: Consecutive identical tool calls trigger warning on 2nd repeat")
    void testConsecutiveIdenticalCalls_warningOnSecondCall() {
        AtomicInteger invocationCount = new AtomicInteger(0);

        String result1 = SwarmTelemetry.executeTool("readFile", "{\"path\":\"docs/spec.txt\"}", () -> {
            invocationCount.incrementAndGet();
            return "File Content V1";
        });

        assertThat(result1).isEqualTo("File Content V1");
        assertThat(invocationCount.get()).isEqualTo(1);

        String result2 = SwarmTelemetry.executeTool("readFile", "{\"path\":\"docs/spec.txt\"}", () -> {
            invocationCount.incrementAndGet();
            return "File Content V1";
        });

        assertThat(invocationCount.get()).isEqualTo(2);
        assertThat(result2).contains("File Content V1");
        assertThat(result2).contains("[GUARDRAIL WARNING: You have called 'readFile' with identical arguments 2 times in a row.");

        InMemoryTraceExporter exporter = SwarmTelemetry.getInMemoryExporter();
        List<SwarmSpan> toolSpans = exporter.getSpansByName("tool.execute");
        assertThat(toolSpans).hasSize(2);

        SwarmSpan secondSpan = toolSpans.get(1);
        assertThat(secondSpan.getAttribute(TelemetryAttributes.GUARDRAIL_WARNING)).isNotNull();
        assertThat(secondSpan.getEvents()).anyMatch(e -> "guardrail.loop_warning".equals(e.name()));
        assertThat(secondSpan.getStatus()).isEqualTo(SpanStatus.OK);
    }

    @Test
    @DisplayName("Scenario 2: Consecutive identical tool calls trigger circuit breaker on 3rd repeat and short-circuit action")
    void testConsecutiveIdenticalCalls_circuitBreakerOnThirdCall() {
        AtomicInteger invocationCount = new AtomicInteger(0);

        SwarmTelemetry.executeTool("readFile", "{\"path\":\"README.md\"}", () -> {
            invocationCount.incrementAndGet();
            return "Content 1";
        });
        SwarmTelemetry.executeTool("readFile", "{\"path\":\"README.md\"}", () -> {
            invocationCount.incrementAndGet();
            return "Content 2";
        });

        assertThat(invocationCount.get()).isEqualTo(2);

        // 3rd identical invocation: circuit breaker MUST trip and NOT invoke the supplier
        String result3 = SwarmTelemetry.executeTool("readFile", "{\"path\":\"README.md\"}", () -> {
            invocationCount.incrementAndGet();
            return "Content 3";
        });

        assertThat(invocationCount.get()).isEqualTo(2); // Short-circuited!
        assertThat(result3).startsWith("[GUARDRAIL CIRCUIT BREAKER]");
        assertThat(result3).contains("Tool 'readFile' was blocked after 3 consecutive identical invocations with arguments: {\"path\":\"README.md\"}");

        InMemoryTraceExporter exporter = SwarmTelemetry.getInMemoryExporter();
        List<SwarmSpan> toolSpans = exporter.getSpansByName("tool.execute");
        assertThat(toolSpans).hasSize(3);

        SwarmSpan thirdSpan = toolSpans.get(2);
        assertThat(thirdSpan.getAttribute(TelemetryAttributes.GUARDRAIL_BLOCKED)).isEqualTo(true);
        assertThat(thirdSpan.getAttribute(TelemetryAttributes.GUARDRAIL_REASON)).isEqualTo(ToolLoopGuard.REASON_CONSECUTIVE);
        assertThat(thirdSpan.getStatus()).isEqualTo(SpanStatus.ERROR);
        assertThat(thirdSpan.getEvents()).anyMatch(e -> "guardrail.loop_circuit_breaker".equals(e.name()));
    }

    @Test
    @DisplayName("Scenario 3: Distinct arguments for the same tool pass through without warning or blocking")
    void testDistinctArguments_passThroughWithoutInterference() {
        AtomicInteger invocationCount = new AtomicInteger(0);

        for (int i = 1; i <= 5; i++) {
            final int index = i;
            String result = SwarmTelemetry.executeTool("readFile", "{\"path\":\"file" + index + ".txt\"}", () -> {
                invocationCount.incrementAndGet();
                return "Content of file" + index;
            });

            assertThat(result).isEqualTo("Content of file" + index);
            assertThat(result).doesNotContain("[GUARDRAIL WARNING");
            assertThat(result).doesNotContain("[GUARDRAIL CIRCUIT BREAKER]");
        }

        assertThat(invocationCount.get()).isEqualTo(5);
        InMemoryTraceExporter exporter = SwarmTelemetry.getInMemoryExporter();
        List<SwarmSpan> toolSpans = exporter.getSpansByName("tool.execute");
        assertThat(toolSpans).hasSize(5);

        for (SwarmSpan span : toolSpans) {
            assertThat(span.getStatus()).isEqualTo(SpanStatus.OK);
            assertThat(span.getAttribute(TelemetryAttributes.GUARDRAIL_BLOCKED)).isNull();
            assertThat(span.getAttribute(TelemetryAttributes.GUARDRAIL_WARNING)).isNull();
        }
    }

    @Test
    @DisplayName("Scenario 4: Ping-pong oscillation (A -> B -> A -> B -> A) trips circuit breaker on 3rd repeat of A")
    void testPingPongOscillation_tripsOnFifthCall() {
        AtomicInteger countA = new AtomicInteger(0);
        AtomicInteger countB = new AtomicInteger(0);

        // 1. Tool A
        String res1 = SwarmTelemetry.executeTool("searchTool", "{\"q\":\"queryA\"}", () -> {
            countA.incrementAndGet();
            return "Result A";
        });
        assertThat(res1).isEqualTo("Result A");

        // 2. Tool B
        String res2 = SwarmTelemetry.executeTool("readTool", "{\"doc\":\"docB\"}", () -> {
            countB.incrementAndGet();
            return "Result B";
        });
        assertThat(res2).isEqualTo("Result B");

        // 3. Tool A (2nd time)
        String res3 = SwarmTelemetry.executeTool("searchTool", "{\"q\":\"queryA\"}", () -> {
            countA.incrementAndGet();
            return "Result A";
        });
        assertThat(res3).isEqualTo("Result A");

        // 4. Tool B (2nd time)
        String res4 = SwarmTelemetry.executeTool("readTool", "{\"doc\":\"docB\"}", () -> {
            countB.incrementAndGet();
            return "Result B";
        });
        assertThat(res4).isEqualTo("Result B");

        assertThat(countA.get()).isEqualTo(2);
        assertThat(countB.get()).isEqualTo(2);

        // 5. Tool A (3rd repeat in alternating pattern A-B-A-B-A -> circuit breaker trips!)
        String res5 = SwarmTelemetry.executeTool("searchTool", "{\"q\":\"queryA\"}", () -> {
            countA.incrementAndGet();
            return "Result A";
        });

        assertThat(countA.get()).isEqualTo(2); // Action NOT invoked!
        assertThat(res5).startsWith("[GUARDRAIL CIRCUIT BREAKER]");
        assertThat(res5).contains("Ping-pong tool oscillation detected between 'searchTool' and 'readTool'. Break the loop and synthesize your final answer.");

        InMemoryTraceExporter exporter = SwarmTelemetry.getInMemoryExporter();
        List<SwarmSpan> toolSpans = exporter.getSpansByName("tool.execute");
        assertThat(toolSpans).hasSize(5);

        SwarmSpan fifthSpan = toolSpans.get(4);
        assertThat(fifthSpan.getAttribute(TelemetryAttributes.GUARDRAIL_BLOCKED)).isEqualTo(true);
        assertThat(fifthSpan.getAttribute(TelemetryAttributes.GUARDRAIL_REASON)).isEqualTo(ToolLoopGuard.REASON_PING_PONG);
        assertThat(fifthSpan.getStatus()).isEqualTo(SpanStatus.ERROR);
    }

    @Test
    @DisplayName("Scenario 5: Max total tool calls per turn cap trips circuit breaker when exceeded")
    void testMaxTotalToolCallsPerTurnCap_tripsWhenExceeded() {
        ToolLoopGuard.setGlobalMaxCallsPerTurn(5);
        AtomicInteger count = new AtomicInteger(0);

        for (int i = 1; i <= 5; i++) {
            final int idx = i;
            String res = SwarmTelemetry.executeTool("tool" + idx, "{}", () -> {
                count.incrementAndGet();
                return "Output " + idx;
            });
            assertThat(res).isEqualTo("Output " + idx);
        }

        assertThat(count.get()).isEqualTo(5);

        // 6th call exceeds maximum limit of 5
        String res6 = SwarmTelemetry.executeTool("tool6", "{}", () -> {
            count.incrementAndGet();
            return "Output 6";
        });

        assertThat(count.get()).isEqualTo(5); // Blocked, action was NOT executed
        assertThat(res6).startsWith("[GUARDRAIL CIRCUIT BREAKER]");
        assertThat(res6).contains("Maximum allowed tool calls per turn (5) exceeded. Stop invoking tools and present your results to the user.");

        InMemoryTraceExporter exporter = SwarmTelemetry.getInMemoryExporter();
        List<SwarmSpan> toolSpans = exporter.getSpansByName("tool.execute");
        assertThat(toolSpans).hasSize(6);

        SwarmSpan sixthSpan = toolSpans.get(5);
        assertThat(sixthSpan.getAttribute(TelemetryAttributes.GUARDRAIL_BLOCKED)).isEqualTo(true);
        assertThat(sixthSpan.getAttribute(TelemetryAttributes.GUARDRAIL_REASON)).isEqualTo(ToolLoopGuard.REASON_MAX_CALLS);
        assertThat(sixthSpan.getStatus()).isEqualTo(SpanStatus.ERROR);
    }

    @Test
    @DisplayName("Scenario 6: Turn boundary isolation resets history so counts start fresh")
    void testTurnBoundaryIsolation_resetsHistoryAcrossTurns() {
        AtomicInteger invocationCount = new AtomicInteger(0);

        // Turn 1
        try (SwarmSpan turn1 = SwarmTelemetry.startSpan("agent.invoke")) {
            SwarmTelemetry.executeTool("writeFile", "{\"file\":\"test.log\"}", () -> {
                invocationCount.incrementAndGet();
                return "Wrote 1";
            });

            String resWarn = SwarmTelemetry.executeTool("writeFile", "{\"file\":\"test.log\"}", () -> {
                invocationCount.incrementAndGet();
                return "Wrote 2";
            });
            assertThat(resWarn).contains("[GUARDRAIL WARNING");
        }

        assertThat(invocationCount.get()).isEqualTo(2);

        // Turn 2: New agent.invoke span starts -> history must be reset!
        try (SwarmSpan turn2 = SwarmTelemetry.startSpan("agent.invoke")) {
            String freshRes1 = SwarmTelemetry.executeTool("writeFile", "{\"file\":\"test.log\"}", () -> {
                invocationCount.incrementAndGet();
                return "Wrote Turn 2";
            });

            // Must NOT have warning because it is the 1st invocation of this new turn!
            assertThat(freshRes1).isEqualTo("Wrote Turn 2");
            assertThat(freshRes1).doesNotContain("[GUARDRAIL WARNING");
            assertThat(freshRes1).doesNotContain("[GUARDRAIL CIRCUIT BREAKER]");
        }

        assertThat(invocationCount.get()).isEqualTo(3);
    }

    @Test
    @DisplayName("Scenario 7: JSON argument canonicalization normalizes key order and whitespace variations")
    void testArgumentNormalization_whitespaceAndKeyOrdering() {
        String json1 = "{\"name\": \"agent\", \"timeout\": 5000}";
        String json2 = "{\n  \"timeout\": 5000,\n  \"name\": \"agent\"\n}";
        String json3 = "{\"name\":\"agent\",\"timeout\":5000}";

        AtomicInteger count = new AtomicInteger(0);

        // Call 1
        String r1 = SwarmTelemetry.executeTool("createAgent", json1, () -> {
            count.incrementAndGet();
            return "AgentCreated";
        });
        assertThat(r1).isEqualTo("AgentCreated");

        // Call 2 with inverted keys and extra whitespace -> recognized as 2nd identical call!
        String r2 = SwarmTelemetry.executeTool("createAgent", json2, () -> {
            count.incrementAndGet();
            return "AgentCreated";
        });
        assertThat(r2).contains("[GUARDRAIL WARNING");

        // Call 3 with compact formatting -> recognized as 3rd identical call and blocked!
        String r3 = SwarmTelemetry.executeTool("createAgent", json3, () -> {
            count.incrementAndGet();
            return "AgentCreated";
        });

        assertThat(count.get()).isEqualTo(2); // Call 3 short-circuited
        assertThat(r3).startsWith("[GUARDRAIL CIRCUIT BREAKER]");
        assertThat(r3).contains("Tool 'createAgent' was blocked after 3 consecutive identical invocations");
    }

    @Test
    @DisplayName("Scenario 8: Standalone ToolLoopGuard instance supports isolated evaluation and thresholds")
    void testStandaloneInstance() {
        ToolLoopGuard guard = new ToolLoopGuard(2, 3, 4);

        assertThat(guard.getCallCount()).isEqualTo(0);

        GuardrailDecision d1 = guard.evaluate("cmd", "{\"arg\":1}");
        assertThat(d1.isBlocked()).isFalse();
        assertThat(d1.isWarning()).isFalse();
        assertThat(guard.getCallCount()).isEqualTo(1);

        GuardrailDecision d2 = guard.evaluate("cmd", "{\"arg\":1}");
        assertThat(d2.isBlocked()).isFalse();
        assertThat(d2.isWarning()).isTrue();
        assertThat(d2.warningMessage()).contains("2 times in a row");

        GuardrailDecision d3 = guard.evaluate("cmd", "{\"arg\":1}");
        assertThat(d3.isBlocked()).isTrue();
        assertThat(d3.reason()).isEqualTo(ToolLoopGuard.REASON_CONSECUTIVE);
        assertThat(d3.blockMessage()).contains("blocked after 3 consecutive identical invocations");

        guard.clearTurn();
        assertThat(guard.getCallCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("Scenario 9: Tool calls outside agent turn bypass guardrails when not explicitly enabled")
    void testStandaloneToolCallsOutsideAgentTurn_bypassGuardrails() {
        SwarmTelemetry.setGuardrailsAlwaysEnabled(false);

        AtomicInteger count = new AtomicInteger(0);
        for (int i = 0; i < 30; i++) {
            String res = SwarmTelemetry.executeTool("cmd", "{\"arg\":1}", () -> {
                count.incrementAndGet();
                return "OK";
            });
            assertThat(res).isEqualTo("OK");
        }

        // Even with 30 identical calls, outside of an agent turn it passes freely
        assertThat(count.get()).isEqualTo(30);
    }
}
