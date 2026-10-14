# Graph Report - computer-agent  (2026-10-14)

## Corpus Check
- cluster-only mode — file stats not available

## Summary
- 1012 nodes · 3764 edges · 37 communities (17 shown, 20 thin omitted)
- Extraction: 83% EXTRACTED · 17% INFERRED · 0% AMBIGUOUS · INFERRED: 654 edges (avg confidence: 0.85)
- Token cost: 0 input · 0 output

## Graph Freshness
- Built from commit: `2e55ffad`
- Run `git rev-parse HEAD` and compare to check if the graph is stale.
- Run `graphify update .` after code changes (no API cost).

## Community Hubs (Navigation)
- BlackboardService
- SwarmSpan
- dev.langchain4j.agent.tool.Tool
- VisionToolsFacade.java
- WebToolsFacade
- org.junit.jupiter.api.Test
- WorkflowEngine
- CoreConsolidationTest.java
- com.fasterxml.jackson.databind.ObjectMapper
- .requestPermission
- .execute
- WorkflowCheckpoint
- WorkflowRegistryService.java
- SubAgentFactory
- LongTermMemoryService.java
- WorkflowToolsFacade
- WorkflowEdge
- OrchestrationService
- WorkflowEngine.java
- AgentMain.java
- SubAgentFactoryTest.java
- GitExecutionService
- WorkflowRegistryService
- InMemoryCheckpointer
- .executeWorkflow
- .main
- .createAgent
- OrchestrationServiceTest
- MemoryToolsFacadeTest
- .resume
- WorkflowState
- .buildPrompt
- WorkflowParserService.java
- com.agent:computer-agent

## God Nodes (most connected - your core abstractions)
1. `SubAgentFactory` - 61 edges
2. `WorkflowEdge` - 57 edges
3. `WorkflowRegistryService` - 57 edges
4. `BlackboardService` - 43 edges
5. `WorkflowEngine` - 40 edges
6. `WorkflowGraph` - 39 edges
7. `SwarmSpan` - 38 edges
8. `AgentConfig` - 36 edges
9. `InMemoryCheckpointer` - 35 edges
10. `WorkflowState` - 35 edges

## Surprising Connections (you probably didn't know these)
- `CoreConsolidationTest` --references--> `ContextToolsFacade`  [EXTRACTED]
  src/test/java/com/agent/consolidation/CoreConsolidationTest.java → src/main/java/com/agent/tools/context/ContextToolsFacade.java
- `ToolRegistryTest` --references--> `ContextToolsFacade`  [EXTRACTED]
  src/test/java/com/agent/registry/ToolRegistryTest.java → src/main/java/com/agent/tools/context/ContextToolsFacade.java
- `OrchestrationService` --references--> `BlackboardService`  [EXTRACTED]
  src/main/java/com/agent/orchestration/services/OrchestrationService.java → src/main/java/com/agent/tools/context/services/BlackboardService.java
- `CoreConsolidationTest` --references--> `BlackboardService`  [EXTRACTED]
  src/test/java/com/agent/consolidation/CoreConsolidationTest.java → src/main/java/com/agent/tools/context/services/BlackboardService.java
- `HybridOrchestratorIntegrationTest` --references--> `BlackboardService`  [EXTRACTED]
  src/test/java/com/agent/orchestration/HybridOrchestratorIntegrationTest.java → src/main/java/com/agent/tools/context/services/BlackboardService.java

## Import Cycles
- None detected.

## Communities (37 total, 20 thin omitted)

### Community 0 - "BlackboardService"
Cohesion: 0.05
Nodes (15): ContextToolsFacade, BlackboardEvent, BlackboardEventType, CREATED, DELETED, EXPIRED, UPDATED, BlackboardListener (+7 more)

### Community 1 - "SwarmSpan"
Cohesion: 0.06
Nodes (11): InMemoryTraceExporter, SpanEvent, SpanStatus, ERROR, OK, UNSET, SwarmSpan, SwarmTelemetry (+3 more)

### Community 2 - "dev.langchain4j.agent.tool.Tool"
Cohesion: 0.07
Nodes (5): CommandExecutorService, FileSystemService, TerminalToolsFacade, TerminalToolsFacadeTest, TerminalToolsTest

### Community 3 - "VisionToolsFacade.java"
Cohesion: 0.05
Nodes (8): ComputerAgent, OllamaConfig, OllamaModelFactory, ChatSession, ComputerAgentIntegrationTest, ComputerAgentTest, ChatSessionMemoryIntegrationTest, ChatSessionTest

### Community 4 - "WebToolsFacade"
Cohesion: 0.09
Nodes (6): WebAnalysisService, WebDistillationService, WebNavigationService, WebSearchService, WebToolsFacade, WebToolsFacadeTest

### Community 5 - "org.junit.jupiter.api.Test"
Cohesion: 0.10
Nodes (6): OllamaModelFactoryTest, GitExecutionServiceTest, CommandExecutorResilienceTest, WebAnalysisServiceTest, WebDistillationServiceTest, WebSearchServiceTest

### Community 6 - "WorkflowEngine"
Cohesion: 0.08
Nodes (4): ConditionEvaluator, StateUpdateParser, WorkflowEngine, ConditionEvaluatorTest

### Community 8 - "com.fasterxml.jackson.databind.ObjectMapper"
Cohesion: 0.09
Nodes (5): JsonFileCheckpointer, WorkflowParserService, JsonLinesTraceExporter, WorkflowCheckpointTest, WorkflowParserServiceTest

### Community 10 - ".execute"
Cohesion: 0.15
Nodes (5): AppendListReducer, OverwriteReducer, Reducer, ScopedStateContractTest, WorkflowStateTest

### Community 12 - "WorkflowCheckpoint"
Cohesion: 0.10
Nodes (9): WorkflowCheckpoint, WorkflowExecutionStatus, COMPLETED, FAILED, ROLLED_BACK, RUNNING, SUSPENDED, SequenceStep (+1 more)

### Community 13 - "WorkflowRegistryService.java"
Cohesion: 0.21
Nodes (3): WorkflowAgentInvoker, WorkflowGraph, WorkflowNode

### Community 14 - "SubAgentFactory"
Cohesion: 0.10
Nodes (3): DelegatingChatMemory, InternalWorkerService, SubAgentFactory

### Community 15 - "LongTermMemoryService.java"
Cohesion: 0.16
Nodes (3): EmbeddingModelFactory, LongTermMemoryService, LongTermMemoryServiceTest

### Community 18 - "OrchestrationService"
Cohesion: 0.14
Nodes (4): DelegationFacade, OrchestrationService, DelegationFacadeTest, HybridOrchestratorIntegrationTest

### Community 22 - "SubAgentFactoryTest.java"
Cohesion: 0.25
Nodes (5): ToolRegistry, GitToolsFacade, MemoryToolsFacade, SubAgentFactoryTest, ToolRegistryTest

### Community 24 - "WorkflowRegistryService"
Cohesion: 0.18
Nodes (4): AgentConfig, WorkflowCheckpointer, WorkflowRegistryService, WorkflowToolsFacadeTest

### Community 27 - ".main"
Cohesion: 0.24
Nodes (3): AgentMain, TelemetryToolsFacade, CoreConsolidationTest

## Knowledge Gaps
- **13 isolated node(s):** `CREATED`, `DELETED`, `EXPIRED`, `UPDATED`, `ERROR` (+8 more)
  These have ≤1 connection - possible missing edges. (Counts symbols only; 101 node(s) total have ≤1 connection when file, concept and rationale nodes are included.)
- **20 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `SubAgentFactory` connect `SubAgentFactory` to `VisionToolsFacade.java`, `WorkflowEngine`, `CoreConsolidationTest.java`, `com.fasterxml.jackson.databind.ObjectMapper`, `org.slf4j.Logger`, `WorkflowRegistryService.java`, `WorkflowToolsFacade`, `OrchestrationService`, `WorkflowEngine.java`, `AgentMain.java`, `SubAgentFactoryTest.java`, `WorkflowRegistryService`, `.main`, `.createAgent`, `OrchestrationServiceTest`?**
  _High betweenness centrality (0.064) - this node is a cross-community bridge._
- **What connects `CREATED`, `DELETED`, `EXPIRED` to the rest of the system?**
  _13 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `BlackboardService` be split into smaller, more focused modules?**
  _Cohesion score 0.050140056022408966 - nodes in this community are weakly interconnected._
- **Why does `SwarmSpan` connect `SwarmSpan` to `WorkflowEngine.java`?**
  _High betweenness centrality (0.035) - this node is a cross-community bridge._
- **Should `SwarmSpan` be split into smaller, more focused modules?**
  _Cohesion score 0.05714285714285714 - nodes in this community are weakly interconnected._
- **Why does `WorkflowState` connect `WorkflowState` to `WorkflowEngine`, `CoreConsolidationTest.java`, `.execute`, `org.slf4j.Logger`, `WorkflowRegistryService.java`, `WorkflowEngine.java`, `.executeWorkflow`, `.resume`?**
  _High betweenness centrality (0.035) - this node is a cross-community bridge._
- **Should `dev.langchain4j.agent.tool.Tool` be split into smaller, more focused modules?**
  _Cohesion score 0.06993006993006994 - nodes in this community are weakly interconnected._