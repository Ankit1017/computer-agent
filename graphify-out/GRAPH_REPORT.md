# Graph Report - computer-agent  (2026-10-15)

## Corpus Check
- cluster-only mode — file stats not available

## Summary
- 643 nodes · 1657 edges · 29 communities (10 shown, 19 thin omitted)
- Extraction: 91% EXTRACTED · 9% INFERRED · 0% AMBIGUOUS · INFERRED: 143 edges (avg confidence: 0.85)
- Token cost: 0 input · 0 output

## Graph Freshness
- Built from commit: `7770ee30`
- Run `git rev-parse HEAD` and compare to check if the graph is stale.
- Run `graphify update .` after code changes (no API cost).

## Community Hubs (Navigation)
- AgentMain.java
- SubAgentFactory
- SwarmSpan
- WorkflowRegistryService
- BlackboardService
- WorkflowCheckpoint
- VisionToolsFacade.java
- LongTermMemoryService.java
- dev.langchain4j.agent.tool.Tool
- WorkflowState
- SecurityInterceptor
- WorkflowEngine
- .initializeTools
- VisionToolsFacade
- WebNavigationService
- CommandExecutorService
- ConditionEvaluator
- WorkflowAgentInvoker
- WorkflowSagaRollbackException
- StateUpdateParser
- dev.langchain4j.service.SystemMessage
- com.agent:computer-agent

## God Nodes (most connected - your core abstractions)
1. `SubAgentFactory` - 49 edges
2. `WorkflowRegistryService` - 44 edges
3. `SwarmSpan` - 38 edges
4. `WorkflowEngine` - 37 edges
5. `AgentConfig` - 28 edges
6. `BlackboardService` - 28 edges
7. `WorkflowGraph` - 27 edges
8. `WorkflowState` - 26 edges
9. `SwarmTelemetry` - 23 edges
10. `WorkflowCheckpoint` - 23 edges

## Surprising Connections (you probably didn't know these)
- `JsonFileCheckpointer` --implements--> `WorkflowCheckpointer`  [EXTRACTED]
  src/main/java/com/agent/orchestration/workflow/checkpoint/JsonFileCheckpointer.java → src/main/java/com/agent/orchestration/workflow/checkpoint/WorkflowCheckpointer.java
- `AppendListReducer` --implements--> `Reducer`  [EXTRACTED]
  src/main/java/com/agent/orchestration/workflow/state/AppendListReducer.java → src/main/java/com/agent/orchestration/workflow/state/Reducer.java
- `JsonLinesTraceExporter` --implements--> `TraceExporter`  [EXTRACTED]
  src/main/java/com/agent/telemetry/JsonLinesTraceExporter.java → src/main/java/com/agent/telemetry/TraceExporter.java
- `WorkflowEngine` --references--> `AgentConfig`  [EXTRACTED]
  src/main/java/com/agent/orchestration/workflow/engine/WorkflowEngine.java → src/main/java/com/agent/config/AgentConfig.java
- `WorkflowRegistryService` --references--> `AgentConfig`  [EXTRACTED]
  src/main/java/com/agent/orchestration/workflow/services/WorkflowRegistryService.java → src/main/java/com/agent/config/AgentConfig.java

## Import Cycles
- None detected.

## Communities (29 total, 19 thin omitted)

### Community 0 - "AgentMain.java"
Cohesion: 0.05
Nodes (8): OllamaModelFactory, JsonFileCheckpointer, SequenceStep, WorkflowRoutingException, WorkflowValidationException, AppendListReducer, JsonLinesTraceExporter, TelemetryAttributes

### Community 1 - "SubAgentFactory"
Cohesion: 0.05
Nodes (11): AgentMain, AgentConfig, DelegatingChatMemory, InternalWorkerService, SubAgentFactory, DelegationFacade, OrchestratorPromptBuilder, OrchestrationService (+3 more)

### Community 2 - "SwarmSpan"
Cohesion: 0.05
Nodes (10): InMemoryTraceExporter, SpanEvent, SpanStatus, ERROR, OK, UNSET, SwarmSpan, SwarmTelemetry (+2 more)

### Community 3 - "WorkflowRegistryService"
Cohesion: 0.08
Nodes (6): WorkflowEdge, WorkflowExecutionResult, WorkflowGraph, WorkflowParserService, WorkflowRegistryService, WorkflowToolsFacade

### Community 4 - "BlackboardService"
Cohesion: 0.06
Nodes (12): ContextToolsFacade, BlackboardEvent, BlackboardEventType, CREATED, DELETED, EXPIRED, UPDATED, BlackboardListener (+4 more)

### Community 5 - "WorkflowCheckpoint"
Cohesion: 0.09
Nodes (9): InMemoryCheckpointer, WorkflowCheckpoint, WorkflowCheckpointer, WorkflowExecutionStatus, COMPLETED, FAILED, ROLLED_BACK, RUNNING (+1 more)

### Community 7 - "LongTermMemoryService.java"
Cohesion: 0.13
Nodes (3): EmbeddingModelFactory, MemoryToolsFacade, LongTermMemoryService

### Community 9 - "WorkflowState"
Cohesion: 0.15
Nodes (3): OverwriteReducer, Reducer, WorkflowState

### Community 10 - "SecurityInterceptor"
Cohesion: 0.18
Nodes (3): GitToolsFacade, GitExecutionService, SecurityInterceptor

### Community 12 - ".initializeTools"
Cohesion: 0.24
Nodes (4): WebAnalysisService, WebDistillationService, WebSearchService, WebToolsFacade

## Knowledge Gaps
- **13 isolated node(s):** `ERROR`, `OK`, `UNSET`, `com.agent:computer-agent`, `CREATED` (+8 more)
  These have ≤1 connection - possible missing edges. (Counts symbols only; 116 node(s) total have ≤1 connection when file, concept and rationale nodes are included.)
- **19 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `SubAgentFactory` connect `SubAgentFactory` to `AgentMain.java`, `WorkflowRegistryService`, `VisionToolsFacade.java`, `WorkflowEngine`, `.runSuperstepLoop`, `WorkflowAgentInvoker`?**
  _High betweenness centrality (0.111) - this node is a cross-community bridge._
- **What connects `ERROR`, `OK`, `UNSET` to the rest of the system?**
  _13 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `AgentMain.java` be split into smaller, more focused modules?**
  _Cohesion score 0.05003217503217503 - nodes in this community are weakly interconnected._
- **Why does `SwarmSpan` connect `SwarmSpan` to `AgentMain.java`, `.runSuperstepLoop`?**
  _High betweenness centrality (0.098) - this node is a cross-community bridge._
- **Should `SubAgentFactory` be split into smaller, more focused modules?**
  _Cohesion score 0.05271629778672032 - nodes in this community are weakly interconnected._
- **Why does `SwarmTelemetry` connect `SwarmSpan` to `AgentMain.java`, `SubAgentFactory`, `VisionToolsFacade`, `VisionToolsFacade.java`?**
  _High betweenness centrality (0.089) - this node is a cross-community bridge._
- **Should `SwarmSpan` be split into smaller, more focused modules?**
  _Cohesion score 0.0546448087431694 - nodes in this community are weakly interconnected._