# Computer Agent: Enterprise Multi-Agent Swarm & Workflow Engine

An enterprise-grade, local Multi-Agent AI system engineered in **Java 21** using **LangChain4j**, **Ollama**, **Microsoft Playwright**, **ChromaDB**, and **Java 21 Virtual Threads (Project Loom)**.

The system features two complementary orchestration paradigms:
1. **Dynamic Swarm Orchestration:** A central supervisor managing specialized sub-agents with Human-in-the-Loop (HITL) security barriers, real-time context injection, and concurrent sub-agent execution via virtual threads.
2. **Deterministic Declarative Workflows (DAGs):** A LangGraph / Pregel-inspired graph execution engine that traverses declarative JSON workflows, enforces state transitions across synchronization barriers, and safely merges partial updates through typed channel reducers.

---

## 🏛️ System Architecture

```mermaid
graph TD
    User([User CLI / Terminal]) <--> AgentMain[AgentMain Orchestrator Entrypoint]
    AgentMain --> Orchestrator[Swarm Orchestrator (WorkerAgent)]
    
    subgraph Dynamic Swarm Execution Engine
        Orchestrator --> DelegationFacade[DelegationFacade]
        DelegationFacade --> OrchestrationService[OrchestrationService]
        OrchestrationService -->|Virtual Threads| SubAgents[Specialized Sub-Agents]
        SubAgents --> Architect[System Architect]
        SubAgents --> Developer[Lead Developer]
        SubAgents --> DevOps[DevOps Engineer]
        SubAgents --> Researcher[Web Researcher]
        SubAgents --> GUITester[GUI Automation Agent]
    end

    subgraph Declarative Workflow Engine
        WorkflowGraph[WorkflowGraph (DAG)] --> WorkflowEngine[WorkflowEngine]
        WorkflowEngine --> StateUpdateParser[StateUpdateParser]
        WorkflowEngine <--> WorkflowState[WorkflowState (Channels & Reducers)]
        WorkflowEngine -->|Step Dispatch| SubAgents
    end

    subgraph Shared State & Memory
        BlackboardService[(BlackboardService / TTL State)]
        ChromaStore[(Local Vector Store / ChromaDB)]
        Architect <--> BlackboardService
        Developer <--> BlackboardService
        Researcher <--> BlackboardService
    end

    subgraph Enterprise Tools & Security Boundary
        TerminalFacade[TerminalToolsFacade]
        VisionFacade[VisionToolsFacade]
        GitToolsFacade[GitToolsFacade]
        WebToolsFacade[WebToolsFacade]
        Security[SecurityInterceptor (HITL Gate)]
        RobotActuator[java.awt.Robot Desktop Actuator]
        OSProcess[CommandExecutorService]
        FileService[FileSystemService (.bak backups)]
    end

    TerminalFacade --> Security
    VisionFacade --> Security
    GitToolsFacade --> Security
    Security -->|Authorized| RobotActuator
    Security -->|Authorized| OSProcess
    Security -->|Authorized| FileService
```

For in-depth architectural design decisions, refer to [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

---

## 👥 Specialized Agent Manifest

Agents are declaratively specified in [`agents.json`](agents.json) with strict tool boundaries:

| Agent ID | Persona | Assigned Capabilities | Core Tools |
| :--- | :--- | :--- | :--- |
| **`architect`** | System Architect | Architectural analysis, codebase traversal, and system design | `listDirectory`, `readFileLines`, `searchDirectory`, `writeTemporaryData`, `readFromNamespacedBlackboard` |
| **`developer`** | Lead Developer | Production code authoring, regex patching, automated backups | `writeFile`, `replaceLinesInFile`, `regexReplaceInFile`, `storeFact`, `searchMemory` |
| **`devops`** | DevOps Engineer | Git version control, branch management, build execution | `getGitStatus`, `getGitDiff`, `commitChanges` (HITL gated), `executeCommand` |
| **`researcher`** | Web Specialist | Internet research, DOM extraction, and on-the-fly semantic RAG | `searchWeb`, `readWebPage`, `analyzeWebPage`, `clickWebElement`, `fillWebInput` |
| **`gui_tester`** | GUI Actuator | Desktop actuation using Set-of-Marks 10x10 grid overlay | `inspectScreen`, `clickGridId`, `clickGridOffset`, `typeText`, `pressHotkey` |

---

## 📦 Package Organization

```
com.agent
├── AgentMain.java                       # Swarm CLI bootstrap & graceful shutdown hooks
├── WorkerAgent.java                     # Universal AI service interface (LangChain4j)
├── config/                              # Configuration records (AgentConfig, OllamaConfig)
├── factory/                             # Factory layer (OllamaModelFactory, SubAgentFactory)
├── registry/                            # Central tool lookup registry (ToolRegistry)
├── orchestration/
│   ├── DelegationFacade.java            # Orchestrator tool facade (@Tool delegateTask/delegateParallel)
│   ├── OrchestratorPromptBuilder.java   # Dynamic manager prompt generator with tool manifests
│   ├── services/
│   │   └── OrchestrationService.java    # Virtual thread parallel execution & retry resilience
│   └── workflow/
│       ├── model/                       # DAG schemas (WorkflowGraph, WorkflowNode, WorkflowEdge)
│       ├── services/                    # JSON workflow parser & DAG validator (WorkflowParserService)
│       ├── state/                       # Typed state reducers (WorkflowState, OverwriteReducer, AppendListReducer)
│       └── engine/                      # Deterministic traverser & state parser (WorkflowEngine, StateUpdateParser)
└── tools/
    ├── context/                         # Distributed blackboard with TTL cleanup (BlackboardService, ContextToolsFacade)
    ├── git/                             # Native process Git manager (GitExecutionService, GitToolsFacade)
    ├── memory/                          # ChromaDB semantic vector search (LongTermMemoryService, MemoryToolsFacade)
    ├── terminal/                        # Terminal execution, NIO file traversal, and HITL gate (TerminalToolsFacade)
    ├── vision/                          # Desktop vision and Set-of-Marks grid actuation (VisionToolsFacade)
    └── web/                             # Playwright headless browser, Jsoup distillation, DDG search, and in-memory RAG
```

---

## ⚙️ Prerequisites

1. **Java Development Kit (JDK):** Version 21 or higher.
2. **Apache Maven:** Version 3.8 or higher.
3. **Ollama:** Local LLM runtime listening on `http://localhost:11434`.
   - Recommended models:
     ```bash
     ollama run llama3.1
     ollama run llama3.2-vision   # Optional: For desktop visual inspection
     ```
4. **ChromaDB (Optional):** Local vector store listening on `http://localhost:8000`.

---

## 🚀 Quick Start

### 1. Build the Standalone Fat JAR
Use the included Windows batch script or Maven to compile and package the shaded application:
```cmd
build.bat
```
*(Or run `mvn clean package`)*. This produces the all-inclusive Fat JAR in `target/computer-agent-1.0.0-SNAPSHOT.jar`.

### 2. Run the Swarm CLI
Launch the interactive CLI orchestrator:
```cmd
swarm.bat
```
*(Or run `java -jar target/computer-agent-1.0.0-SNAPSHOT.jar`)*.

---

## 💡 Example CLI Prompts

### Codebase Exploration & Architectural Analysis
```
User > explain me about this project working E:\computer-agent\src\main\java\com\agent
```
*The Orchestrator automatically delegates to `architect`, who inspects the directory structure with `listDirectory` and `readFileLines` to generate an architectural breakdown.*

### Desktop GUI & Application Actuation
```
User > Can you open notepad and type Hello World
```
*The Orchestrator delegates to `gui_tester`, which triggers `pressHotkey("win")`, `typeText("notepad")`, and `pressHotkey("enter")` through the Human-In-The-Loop safety gate.*

### Autonomous Code Modification & Git Versioning
```
User > Developer, add a new unit test for FileSystemService. DevOps, review the git diff and commit the changes.
```
*The Orchestrator leverages `delegateParallel` or sequential delegation: `developer` updates the test file, and `devops` runs `getGitStatus` and `getGitDiff`, prompting the user for approval via `commitChanges`.*

---

## 🔄 Declarative Workflows (DAGs)

Declarative workflows allow defining multi-agent execution sequences as deterministic Directed Acyclic Graphs in JSON without relying on unbounded LLM delegation.

### Sample Workflow: `src/main/resources/workflows/research_pipeline.json`
```json
{
  "workflowId": "research_pipeline",
  "startNodeId": "node_1",
  "nodes": [
    {
      "nodeId": "node_1",
      "agentId": "researcher",
      "taskInstruction": "Research Java 21 features."
    },
    {
      "nodeId": "node_2",
      "agentId": "architect",
      "taskInstruction": "Summarize the research into an architecture doc."
    },
    {
      "nodeId": "node_3",
      "agentId": "developer",
      "taskInstruction": "Write the final document to disk."
    }
  ],
  "edges": [
    { "sourceNode": "node_1", "targetNode": "node_2" },
    { "sourceNode": "node_2", "targetNode": "node_3" },
    { "sourceNode": "node_3", "targetNode": "__END__" }
  ]
}
```

### Executing a Workflow Programmatically
```java
// 1. Parse and validate the DAG schema
WorkflowParserService parser = new WorkflowParserService();
WorkflowGraph graph = parser.parseFromResource("workflows/research_pipeline.json");

// 2. Configure state channels with typed reducers
WorkflowState state = new WorkflowState();
state.registerReducer("notes", new AppendListReducer<String>());
state.registerReducer("status", new OverwriteReducer<>());

// 3. Execute via the sequential traverser
WorkflowEngine engine = new WorkflowEngine(graph, subAgentFactory, state);
WorkflowState finalState = engine.execute(Map.of("project_name", "Operation Phoenix"));

// 4. Access final merged state
List<String> accumulatedNotes = finalState.get("notes", List.class);
```

---

## 🛡️ Security & Human-In-The-Loop (HITL)

The agent operates under an explicit principle of least privilege. Destructive operations cannot execute silently:
- **Terminal Execution:** Every raw shell command (`executeCommand`) requires explicit terminal authorization `[Y/N]`.
- **Git Commits:** Git commits (`commitChanges`) display the exact diff and commit message, requiring user approval.
- **Desktop Actuation:** Mouse clicks, wheel scrolling, and keyboard keystrokes (`java.awt.Robot`) are gated by the `SecurityInterceptor`.
- **Atomic File Backups:** Modifying files (`writeFile`, `replaceLinesInFile`, `regexReplaceInFile`) automatically generates `.bak` recovery snapshots before applying changes.

---

## 🧪 Verification & Testing

The project maintains a zero-failure unit and integration test suite:
```cmd
mvn test
```
*Current test suite: **148 tests passing, 0 failures, 0 errors** covering all enterprise packages, DAG validation, and channel reducers.*

---

## 📄 License
This project is licensed under the Apache License, Version 2.0.