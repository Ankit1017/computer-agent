# Computer Use AI Agent: Enterprise Architecture & System Specification

## 1. Executive Architectural Overview
The **Computer Use AI Agent** is a production-grade, local Multi-Agent Swarm engineered in **Java 21** using **LangChain4j**, **Ollama (`llama3.1`, `llama3.2-vision`)**, and **Virtual Threads (Project Loom)**. 

The system implements a decoupled, event-driven, Human-In-The-Loop (HITL) architecture enabling autonomous desktop and operating system actuation without compromising system security or context-window stability.

```mermaid
graph TD
    User([User CLI / Terminal]) <--> AgentMain[AgentMain Orchestrator Entrypoint]
    AgentMain --> Orchestrator[Swarm Orchestrator (WorkerAgent)]
    
    subgraph Swarm Execution Engine
        Orchestrator --> DelegationTool[DelegationTool]
        DelegationTool -->|Virtual Thread Per Task| SubAgents[Specialized Sub-Agents]
        SubAgents --> Architect[System Architect]
        SubAgents --> Developer[Lead Developer]
        SubAgents --> DevOps[DevOps Engineer]
        SubAgents --> GUITester[GUI Automation Agent]
    end

    subgraph Shared State & Memory
        ContextTools[(ContextTools Blackboard)]
        ChromaStore[(Local Vector Store / ChromaDB)]
        Architect <--> ContextTools
        Developer <--> ContextTools
        DevOps <--> ContextTools
        GUITester <--> ContextTools
    end

    subgraph Enterprise Tools & Facades
        TerminalFacade[TerminalToolsFacade]
        VisionFacade[VisionToolsFacade]
        GitToolsFacade[GitTools]
        WebToolsFacade[WebTools]
    end

    subgraph Security & Actuation Boundary
        Security[SecurityInterceptor (HITL Gate)]
        RobotActuator[java.awt.Robot Desktop Actuator]
        OSProcess[CommandExecutorService (OS Shell)]
        FileService[FileSystemService (Atomic .bak)]
    end

    SubAgents --> TerminalFacade
    SubAgents --> VisionFacade
    SubAgents --> GitToolsFacade
    SubAgents --> WebToolsFacade

    TerminalFacade --> Security
    VisionFacade --> Security
    GitToolsFacade --> Security

    Security -->|Authorized| RobotActuator
    Security -->|Authorized| OSProcess
    Security -->|Authorized| FileService
```

---

## 2. Multi-Agent Swarm Orchestration Engine

### 2.1 Dynamic Worker Agent Factory (`SubAgentFactory`)
- **Universal Contract:** All agents implement the unified [`WorkerAgent`](file:///e:/computer-agent/src/main/java/com/agent/WorkerAgent.java) interface:
  ```java
  public interface WorkerAgent {
      String execute(String task);
  }
  ```
- **Configuration-Driven Manifest (`agents.json`):** Dynamic agent definitions define scoped responsibilities and tool boundaries:
  - `architect`: System planning, AST structural exploration, package mapping (`web`, `memory`, `terminal`, `context`).
  - `developer`: Surgical file creation, regex patching, and refactoring (`terminal`, `context`).
  - `devops`: Build lifecycle, test verification, git auditing, and releases (`terminal`, `git`, `context`).
  - `gui_tester`: Desktop vision inspection, SoM grid localization, mouse/keyboard actuation (`vision`, `context`).
- **Context Isolation:** Each sub-agent maintains an isolated `MessageWindowChatMemory` sliding window, completely preventing conversational bloat from contaminating the root orchestrator.

### 2.2 Concurrency & Thread-Safe State Sharing
- **Virtual Threads Execution (`DelegationTool`):** Independent tasks dispatched via `delegateParallel` execute on lightweight Java 21 Virtual Threads (`Executors.newVirtualThreadPerTaskExecutor()`), synthesizing outputs concurrently with zero thread contention.
- **The Shared Blackboard (`ContextTools`):** Cross-agent communication occurs over a thread-safe `ConcurrentHashMap<String, String>` blackboard (`writeToBlackboard`, `readFromBlackboard`). Large schemas, keys, or architectural drafts pass between agents by reference without overflowing LLM context windows.

---

## 3. Enterprise Tool Facades & Filesystem Hardening

### 3.1 Terminal & Filesystem Layer (`TerminalToolsFacade`)
The terminal layer is decoupled into specialized services following Clean Architecture:
1. **`FileSystemService`:**
   - **Atomic Backups:** Automatically creates `.bak` snapshot copies via `Files.copy` before performing any destructive write or line-replacement.
   - **5MB Payload Ceiling:** Strictly rejects payloads exceeding 5,242,880 bytes to guard against memory exhaustion.
   - **Binary Probing Safeguard:** Reads the initial 1024 bytes of any file for null bytes (`0x00`) to reject compiled binaries (e.g. fat JARs, class files) before ingestion.
   - **Grep Clone (`searchDirectory`):** Regex-enabled recursive crawler with case-insensitive Pattern matching, extension filtering, AST parser stubs, and a 50-match global context ceiling.
   - **Path Auto-Resolution:** Automatically searches the repository if an agent specifies an unqualified filename, preventing path misdirection.
2. **`CommandExecutorService`:**
   - Wraps commands in host OS shells (`cmd.exe /c` / `powershell` on Windows, `/bin/sh -c` on Unix).
   - Uses Virtual Thread stream gobblers to drain stdout/stderr and terminates processes exceeding 60-second timeouts.
3. **Method Overloading Architecture:**
   - Explicitly provides 1-arg, 2-arg, and 3-arg `@Tool` overloads for `listDirectory` and `searchDirectory` to prevent LangChain4j `DefaultToolExecutor.coerceArgument` NullPointerExceptions when optional arguments are omitted.

---

## 4. Human-In-The-Loop (HITL) Security Architecture

### 4.1 Security Boundary Enforcement (`SecurityInterceptor`)
- **Zero-Bypass Gate:** Destructive operations (process execution, file overwrites, line splicing, mouse clicks, key presses, and git commits) must pass through `SecurityInterceptor.requestPermission(action, target)`.
- **Anti-Looping & Terminal State Machine:**
  - If a user denies an action (`N`), the interceptor logs the denial and returns an explicit `ACTION REJECTED: User explicitly denied...` payload.
  - Sub-agent and Orchestrator system prompts treat rejection as an immutable terminal state, preventing infinite 100-step retry loops.
- **Mockito Security Isolation:** Mathematical proof tests in `TerminalToolsFacadeTest` verify that when permissions are denied, target execution services are strictly never invoked (`verify(service, never()).methodCall()`).

---

## 5. Set-of-Marks (SoM) Vision & GUI Actuation

### 5.1 Visual Localization (`VisionToolsFacade`)
- **10x10 Set-of-Marks (SoM) Grid Overlay:**
  - Captures high-resolution screen buffers via `java.awt.Robot.createScreenCapture`.
  - Overlays a dynamic 100-cell grid with contrasting borders and center-aligned numeric markers (`0` to `99`).
  - Emits compressed JPEG payloads to `llama3.2-vision` for semantic coordinate reasoning.

### 5.2 Precision Desktop Actuation
- **Precision Sub-Grid Offset Targeting (`clickGridOffset`):**
  - Accepts normalized offset percentages (`offsetPercentX`, `offsetPercentY`) clamped to `[0.0, 1.0]`.
  - Calculates sub-pixel offsets within a specific grid cell, allowing sub-pixel UI precision without raw coordinate hallucinations.
- **Full Mouse & Keyboard Suite:**
  - `doubleClickGridId`: Dispatches rapid dual left-clicks.
  - `rightClickGridId`: Dispatches context-menu clicks (`BUTTON3_DOWN_MASK`).
  - `scrollMouse`: Actuates mouse wheel notches (positive down, negative up).
  - `pressHotkey`: Sequentially presses composite keys (`ctrl`, `alt`, `shift`, `esc`) and guarantees reverse-order release inside a `try/finally` block to prevent sticky key locks.
  - `typeText`: Writes text via the system clipboard with an automated 5-attempt retry loop and backoff to bypass Windows clipboard handle locks.

---

## 6. Distribution & Operational Metrics

- **Standalone CLI Packaging (`swarm.bat`):** Packaged via `maven-shade-plugin` with `ServicesResourceTransformer` and `ManifestResourceTransformer` into an executable Fat JAR (`target/computer-agent-1.0.0-SNAPSHOT.jar`).
- **Graceful Lifecycle Shutdown:** JVM shutdown hooks safely terminate background Virtual Thread executors and release vector store connections upon `SIGINT` / `Ctrl+C`.
- **Test Suite Health:** 92 automated tests across unit, security isolation, concurrency, and facade integration executing in <40 seconds with zero regressions.
