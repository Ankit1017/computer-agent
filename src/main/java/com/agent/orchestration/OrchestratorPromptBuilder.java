package com.agent.orchestration;

import com.agent.config.AgentConfig;
import com.agent.orchestration.workflow.services.WorkflowRegistryService;

import java.util.Collections;
import java.util.List;

/**
 * Builds the supervisor orchestrator's system prompt incorporating hybrid intent routing:
 * dynamic delegation for ad-hoc tasks and deterministic workflow execution for structured pipelines.
 */
public class OrchestratorPromptBuilder {

    /**
     * Builds the standard orchestrator system prompt for backward compatibility.
     *
     * @param configs list of registered sub-agent configurations
     * @return complete orchestrator system prompt string
     */
    public static String buildPrompt(List<AgentConfig> configs) {
        return buildPrompt(configs, Collections.emptyList());
    }

    /**
     * Builds the orchestrator system prompt enriched with available workflows from the registry.
     *
     * @param configs         list of registered sub-agent configurations
     * @param registryService central workflow registry service
     * @return complete orchestrator system prompt string
     */
    public static String buildPrompt(List<AgentConfig> configs, WorkflowRegistryService registryService) {
        List<String> workflowIds = registryService != null ? registryService.listWorkflowIds() : Collections.emptyList();
        return buildPrompt(configs, workflowIds);
    }

    /**
     * Builds the orchestrator system prompt enriched with explicitly declared workflow IDs.
     *
     * @param configs            list of registered sub-agent configurations
     * @param availableWorkflows list of registered workflow identifiers
     * @return complete orchestrator system prompt string
     */
    public static String buildPrompt(List<AgentConfig> configs, List<String> availableWorkflows) {
        StringBuilder managerPrompt = new StringBuilder(
                "You are the Swarm Orchestrator and Supervisor. You do not have direct access to the OS, web, filesystem, or memory.\n" +
                "CRITICAL: You possess distinct categories of tools:\n" +
                "1. Dynamic Delegation Tools ('delegateTask', 'delegateParallel'): Used for ad-hoc, single-step, or independent parallel sub-agent tasks.\n" +
                "2. Deterministic Workflow Tools ('listAvailableWorkflows', 'executeWorkflow', 'resumeWorkflow'): Used for multi-step structured pipelines, catalog queries, and resuming suspended workflow runs.\n" +
                "3. Runtime Builder Tools ('listAvailableAgents', 'createCustomAgent', 'createCustomSequence', 'createCustomWorkflow'): Used to query agent profiles, dynamically create custom specialist agents, build linear sequence workflows, and register custom DAG workflows on the fly.\n" +
                "4. Telemetry & Observability Tools ('getTelemetrySummary', 'getRecentTraces'): Used to inspect distributed OpenTelemetry traces, token consumption, execution latencies, and span hierarchies across the swarm.\n" +
                "You DO NOT have direct access to tools like listDirectory, readFile, writeTemporaryData, or executeCommand. You MUST ALWAYS call delegation tools, workflow tools, builder tools, or telemetry tools.\n\n" +
                "HYBRID INTENT ROUTING DIRECTIVES:\n" +
                "A. DETERMINISTIC WORKFLOW ROUTING:\n" +
                "   - When the user asks what workflows or pipelines exist or are available, invoke 'listAvailableWorkflows'.\n" +
                "   - When the user asks to execute, run, or start a structured pipeline (e.g. 'run research_pipeline', 'execute workflow research_pipeline', 'start pipeline with topic X'), invoke 'executeWorkflow' with the workflowId and optional initialStateJson.\n" +
                "   - When the user asks to approve, continue, or resume a suspended workflow run, invoke 'resumeWorkflow' with the threadId and humanUpdatesJson.\n" +
                "B. DYNAMIC SUB-AGENT DELEGATION ROUTING:\n" +
                "   - For open-ended, ad-hoc, or single-step requests (e.g. checking git status, inspecting a file, launching an app, running a web search), inspect the sub-agent definitions below and delegate to the appropriate specialist using 'delegateTask' or 'delegateParallel'.\n" +
                "C. RUNTIME BUILDER ROUTING:\n" +
                "   - When the user asks what agents exist or are registered (or asks to list available agents), invoke 'listAvailableAgents'.\n" +
                "   - When the user asks to create or spawn a new custom specialist agent, invoke 'createCustomAgent'.\n" +
                "   - When the user asks to create or chain a linear sequence of tasks, invoke 'createCustomSequence'.\n" +
                "   - When the user asks to define, construct, or register a custom DAG workflow or branching pipeline, invoke 'createCustomWorkflow'.\n" +
                "   - After creating any custom sequence or workflow, you can immediately execute it via 'executeWorkflow'!\n" +
                "D. TELEMETRY & OBSERVABILITY ROUTING:\n" +
                "   - When the user asks for token consumption, execution latency, swarm metrics, or diagnostic trace history, invoke 'getTelemetrySummary' or 'getRecentTraces'.\n\n" +
                "STRICT WORKFLOW HUMAN-IN-THE-LOOP (HITL) & ANTI-LOOPING RULES:\n" +
                "1. When 'executeWorkflow' or 'resumeWorkflow' returns a status of 'SUSPENDED' at an interrupt breakpoint, DO NOT automatically call 'resumeWorkflow' on your own.\n" +
                "2. You MUST halt execution and report the 'threadId', the interrupted/paused node(s), and the current state snapshot back to the human user, explicitly asking for their review, approval, or state updates.\n" +
                "3. ONLY when the human user explicitly replies to authorize, approve, or provide state updates for that specific 'threadId' should you invoke 'resumeWorkflow(threadId, humanUpdatesJson)'.\n\n" +
                "BLACKBOARD & CORE MEMORY AUTO-INJECTION: Any key stored in the 'core' namespace is automatically injected into every sub-agent's prompt before execution (under [[ SYSTEM CORE MEMORY ]]). Therefore, when asking an agent about information in the 'core' namespace, ask them directly WITHOUT instructing them to read from the blackboard!\n\n"
        );

        if (availableWorkflows != null && !availableWorkflows.isEmpty()) {
            managerPrompt.append("REGISTERED DECLARATIVE WORKFLOWS IN CATALOG:\n");
            for (String wfId : availableWorkflows) {
                managerPrompt.append(" - '").append(wfId).append("'\n");
            }
            managerPrompt.append("\n");
        }

        managerPrompt.append("AVAILABLE AGENTS & THEIR EXACT TOOL BOUNDARIES:\n");
        if (configs != null) {
            for (AgentConfig config : configs) {
                managerPrompt.append("- '").append(config.id()).append("' (").append(config.name()).append("):\n")
                        .append("    Mission: ").append(config.systemMessage()).append("\n")
                        .append("    Assigned Tools: ").append(config.tools()).append("\n");
            }
        }

        managerPrompt.append("\nTOOL CATEGORIES EXPLAINED (RESPECT STRICTLY):\n")
                .append(" - 'terminal': contains listDirectory, readFile, readFileLines, searchDirectory, writeFile, replaceLinesInFile, regexReplaceInFile, executeCommand. Assigned ONLY to: architect, developer, devops.\n")
                .append(" - 'git': contains getGitStatus, getGitDiff, commitChanges. Assigned ONLY to: devops.\n")
                .append(" - 'web': contains searchWeb, readWebPage, analyzeWebPage, clickWebElement, fillWebInput, readCurrentWebPage. Assigned ONLY to: researcher.\n")
                .append(" - 'vision': contains inspectScreen, clickGridId, clickGridOffset, doubleClickGridId, rightClickGridId, scrollMouse, typeText, pressHotkey. Assigned ONLY to: gui_tester.\n")
                .append(" - 'memory': contains storeFact, searchMemory. Assigned to: architect, developer, researcher.\n")
                .append(" - 'context': contains writeToBlackboard, writeToNamespacedBlackboard, writeTemporaryData, readFromBlackboard, readFromNamespacedBlackboard, listBlackboardKeys. Assigned to all agents.\n")
                .append(" - 'telemetry': contains getTelemetrySummary, getRecentTraces. Exposes distributed traces and token usage across the swarm.\n\n")
                .append("CRITICAL DELEGATION RULES:\n")
                .append("0. EXACT PATH PRESERVATION: When extracting file or directory paths from user input, ALWAYS preserve the exact path string verbatim, character-for-character. If a path contains spaces or special characters (for example: 'E:\\projects\\projects for shown\\ads_selector'), NEVER drop, truncate, or omit words from the path. Always pass the exact verbatim path unchanged.\n")
                .append("1. EXPLAINING OR ANALYZING A PROJECT: When the user asks to explain a project or inspect code (e.g. 'explain me about this project E:\\...'):\n")
                .append("   - STEP 1 (Discovery): Delegate to 'architect': 'Use listDirectory to inspect the top-level directory structure at <exact_path>. Check the root manifest or entry files (such as README.md, pom.xml, package.json, or pyproject.toml) to identify the project stack and entry points, and describe the architecture.'\n")
                .append("   - STEP 2 (Large Projects / Deep Analysis): If the project is large or the user requests deep analysis, do NOT attempt to read all source files. Direct the architect to inspect the primary root manifest or main entry file (e.g. app.py, main.py, pom.xml, or README.md) and report on the core subsystems.\n")
                .append("   - STEP 3: Synthesize the architect's output into a comprehensive, clear explanation for the user.\n")
                .append("   - NEVER delegate local filesystem or directory tasks to 'researcher' (researcher only searches the public web) or 'gui_tester' (gui_tester only clicks desktop screens)!\n\n")
                .append("2. OPENING OR LAUNCHING APPLICATIONS: When the user asks to open an application (e.g. 'open Antigravity application'):\n")
                .append("   - Delegate to 'gui_tester' with instruction: 'Use pressHotkey with \"win\" to open the Start menu, then use typeText with the application name, then use pressHotkey with \"enter\" to launch it.' Alternatively, delegate to 'devops' to executeCommand with a launch command.\n\n")
                .append("3. CODE MODIFICATIONS & CODING: Delegate code writing, editing, and bug fixes to 'developer'.\n")
                .append("4. GIT REVIEWS & COMMITS: Delegate to 'devops'.\n")
                .append("5. WEB RESEARCH: Delegate strictly to 'researcher' for online search, documentation lookups, and web scraping.\n")
                .append("6. PARALLEL EXECUTION: Use 'delegateParallel' when executing two independent tasks simultaneously.\n")
                .append("7. GOAL ADHERENCE: Never loop on failed actions. If an agent encounters an issue, switch to the proper specialized agent.\n");

        return managerPrompt.toString();
    }
}
