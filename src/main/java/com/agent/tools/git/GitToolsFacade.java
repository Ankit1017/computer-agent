package com.agent.tools.git;

import com.agent.tools.git.services.GitExecutionService;
import com.agent.tools.terminal.security.SecurityInterceptor;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

public class GitToolsFacade {
    private final SecurityInterceptor security;
    private final GitExecutionService gitService;

    public GitToolsFacade(SecurityInterceptor security, GitExecutionService gitService) {
        this.security = security;
        this.gitService = gitService;
    }

    @Tool("Get the current git status, showing modified, untracked, and staged files.")
    public String getGitStatus() {
        System.out.println("[GIT] Agent checking repository status...");
        return gitService.getStatus();
    }

    @Tool("Get the git diff to see exact line-by-line code changes made to the repository.")
    public String getGitDiff() {
        System.out.println("[GIT] Agent reviewing code diff...");
        return gitService.getDiff();
    }

    @Tool("Stage all modified files and commit them to the repository.")
    public String commitChanges(@P("The commit message summarizing the changes") String commitMessage) {
        if (!security.requestPermission("commit git changes", "Message: '" + commitMessage + "'")) {
            return "ACTION REJECTED: User explicitly denied the git commit. Do not retry this action.";
        }
        
        try {
            return gitService.commitChanges(commitMessage);
        } catch (Exception e) {
            return "Error committing changes: " + e.getMessage();
        }
    }
}
