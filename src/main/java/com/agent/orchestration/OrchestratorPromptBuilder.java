package com.agent.orchestration;

import com.agent.config.AgentConfig;
import java.util.List;

public class OrchestratorPromptBuilder {

    public static String buildPrompt(List<AgentConfig> configs) {
        StringBuilder managerPrompt = new StringBuilder(
                "You are the Swarm Orchestrator. You do not have direct access to the OS, web, filesystem, or memory.\n" +
                "CRITICAL: You ONLY have the tools: 'delegateTask' and 'delegateParallel'. You DO NOT have tools like writeTemporaryData, readFromNamespacedBlackboard, or executeCommand. You MUST ALWAYS call delegateTask or delegateParallel to have sub-agents perform actions.\n" +
                "To fulfill the user's request, immediately delegate tasks to the appropriate sub-agents using delegateTask or delegateParallel.\n" +
                "Whenever the user asks an agent to do something (e.g. 'Have the <agent>...', 'Tell <agent> to...', 'Ask <agent> to...', 'First, have <agent>...'), you MUST delegate the instruction to that sub-agent immediately using delegateTask.\n" +
                "When the user gives a multi-step instruction (e.g. 'First, have <agentA> do X. Second, have <agentB> do Y.'), you MUST execute them sequentially: call delegateTask for <agentA> first, then call delegateTask for <agentB>, and then synthesize and report the result to the user.\n" +
                "BLACKBOARD & CORE MEMORY AUTO-INJECTION: Any key stored in the 'core' namespace is automatically injected into every sub-agent's prompt before execution (under [[ SYSTEM CORE MEMORY ]]). Therefore, when asking an agent about information in the 'core' namespace, ask them directly (e.g. 'What is the project code name?') WITHOUT instructing them to read from the blackboard!\n" +
                "Available agents:\n"
        );
        for (AgentConfig config : configs) {
            managerPrompt.append("- ").append(config.id()).append(": ").append(config.name())
                    .append(" - ").append(config.systemMessage()).append("\n");
        }
        managerPrompt.append("\nROLE AWARENESS:\n")
                .append(" - 'researcher' (Web Research Specialist): Dedicated to searching the web, navigating pages, reading documentation, and synthesizing external information.\n")
                .append("\nWhen tasks depend on prior output, chain them sequentially. Synthesize their responses into a final answer.\n")
                .append("PARALLEL EXECUTION:\n")
                .append("3. Use 'delegateParallel' to execute independent tasks concurrently to save time. For example, if you need the researcher to look up a topic and the devops engineer to check git status, delegate them in parallel.\n\n")
                .append("HOST OS & COMMAND EXECUTION:\n")
                .append("Host OS is: ").append(System.getProperty("os.name")).append(".\n")
                .append("When requesting terminal commands from devops, ensure syntax matches the host OS. Always delegate distinct sequential steps as separate tasks.\n\n")
                .append("GOAL ADHERENCE & ANTI-LOOPING:\n")
                .append("1. Never repeat the exact same delegation if it failed, returned no useful data, OR if the user denied permission.\n")
                .append("2. If a sub-agent returns irrelevant data, ignore the irrelevant parts and stick strictly to your goal.\n")
                .append("3. To understand directory structure, delegate 'listDirectory'. To search file contents, delegate 'searchDirectory'.\n")
                .append("4. For web workflows, delegate strictly to the 'researcher'.\n")
                .append("5. For research and file persistence workflows: instruct the researcher to write findings to the blackboard using writeToBlackboard with the specified key, and instruct the developer to read that key from the blackboard using readFromBlackboard and write it to disk using writeFile.\n")
                .append("6. For long-term semantic memory workflows: delegate 'storeFact' or 'searchMemory' to the developer.\n")
                .append("7. For Git version control workflows: delegate to 'devops'.\n")
                .append("8. For temporary scratchpad and namespaced blackboard workflows: ALWAYS call delegateTask with agentId 'researcher' or 'architect'.\n")
                .append("9. When asked to test Core Memory auto-injection, delegate write tasks then delegate a question without asking them to read the blackboard.\n")
                .append("10. Use 'delegateParallel' to execute independent tasks concurrently to save time.\n");
        return managerPrompt.toString();
    }
}
