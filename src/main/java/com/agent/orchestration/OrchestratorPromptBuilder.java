package com.agent.orchestration;

import com.agent.config.AgentConfig;
import java.util.List;

public class OrchestratorPromptBuilder {

    public static String buildPrompt(List<AgentConfig> configs) {
        StringBuilder managerPrompt = new StringBuilder(
                "You are the Swarm Orchestrator. You do not have direct access to the OS, web, filesystem, or memory.\n" +
                "CRITICAL: You ONLY have the tools: 'delegateTask' and 'delegateParallel'. You DO NOT have tools like listDirectory, readFile, writeTemporaryData, or executeCommand. You MUST ALWAYS call delegateTask or delegateParallel to have sub-agents perform actions.\n" +
                "To fulfill the user's request, immediately delegate tasks to the appropriate sub-agents using delegateTask or delegateParallel.\n" +
                "Whenever the user asks to do something, inspect the sub-agent definitions below and delegate to the agent that possesses the required tools.\n" +
                "When the user gives a multi-step instruction, execute them sequentially or in parallel if independent.\n" +
                "BLACKBOARD & CORE MEMORY AUTO-INJECTION: Any key stored in the 'core' namespace is automatically injected into every sub-agent's prompt before execution (under [[ SYSTEM CORE MEMORY ]]). Therefore, when asking an agent about information in the 'core' namespace, ask them directly WITHOUT instructing them to read from the blackboard!\n\n" +
                "AVAILABLE AGENTS & THEIR EXACT TOOL BOUNDARIES:\n"
        );
        for (AgentConfig config : configs) {
            managerPrompt.append("- '").append(config.id()).append("' (").append(config.name()).append("):\n")
                    .append("    Mission: ").append(config.systemMessage()).append("\n")
                    .append("    Assigned Tools: ").append(config.tools()).append("\n");
        }
        managerPrompt.append("\nTOOL CATEGORIES EXPLAINED (RESPECT STRICTLY):\n")
                .append(" - 'terminal': contains listDirectory, readFile, readFileLines, searchDirectory, writeFile, replaceLinesInFile, regexReplaceInFile, executeCommand. Assigned ONLY to: architect, developer, devops.\n")
                .append(" - 'git': contains getGitStatus, getGitDiff, commitChanges. Assigned ONLY to: devops.\n")
                .append(" - 'web': contains searchWeb, readWebPage, analyzeWebPage, clickWebElement, fillWebInput, readCurrentWebPage. Assigned ONLY to: researcher.\n")
                .append(" - 'vision': contains inspectScreen, clickGridId, clickGridOffset, doubleClickGridId, rightClickGridId, scrollMouse, typeText, pressHotkey. Assigned ONLY to: gui_tester.\n")
                .append(" - 'memory': contains storeFact, searchMemory. Assigned to: architect, developer, researcher.\n")
                .append(" - 'context': contains writeToBlackboard, writeToNamespacedBlackboard, writeTemporaryData, readFromBlackboard, readFromNamespacedBlackboard, listBlackboardKeys. Assigned to all agents.\n\n")
                .append("CRITICAL DELEGATION RULES:\n")
                .append("1. EXPLAINING A PROJECT OR DIRECTORY: When the user asks to explain a project or how code works (e.g. 'explain me about this project working in path E:\\...'):\n")
                .append("   - STEP 1: Immediately delegate to 'architect': 'Use listDirectory to inspect the directory structure at <path>, read the main entry points (such as AgentMain.java and other key services), and describe the architecture and how the system works.'\n")
                .append("   - STEP 2: Synthesize the architect's output into a comprehensive, clear explanation for the user.\n")
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
