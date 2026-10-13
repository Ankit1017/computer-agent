package com.agent.tools.git.services;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

public class GitExecutionService {
    
    private final File workingDirectory;

    public GitExecutionService() {
        // Default to the current project directory
        this.workingDirectory = new File(System.getProperty("user.dir"));
    }

    public GitExecutionService(File workingDirectory) {
        this.workingDirectory = workingDirectory != null ? workingDirectory : new File(System.getProperty("user.dir"));
    }

    public String getStatus() {
        try {
            String status = executeCommand("git", "status", "-s");
            if (status.isBlank()) {
                return "Working tree is clean. No changes to commit.";
            }
            return "Current Git Status (Short format):\n" + status;
        } catch (Exception e) {
            return "Error retrieving git status: " + e.getMessage();
        }
    }

    public String getDiff() {
        try {
            String unstagedDiff = executeCommand("git", "diff");
            String stagedDiff = executeCommand("git", "diff", "--cached");
            
            StringBuilder sb = new StringBuilder();
            if (!stagedDiff.isBlank()) {
                sb.append("--- STAGED CHANGES ---\n").append(stagedDiff).append("\n\n");
            }
            if (!unstagedDiff.isBlank()) {
                sb.append("--- UNSTAGED CHANGES ---\n").append(unstagedDiff).append("\n\n");
            }
            if (sb.isEmpty()) {
                return "No diff available. Working tree is clean.";
            }
            
            String finalDiff = sb.toString();
            // Truncate to protect context window if the diff is massive
            if (finalDiff.length() > 10000) {
                return finalDiff.substring(0, 10000) + "\n\n... [DIFF TRUNCATED due to length] ...";
            }
            return finalDiff;
        } catch (Exception e) {
            return "Error retrieving git diff: " + e.getMessage();
        }
    }

    public String commitChanges(String commitMessage) {
        if (commitMessage == null || commitMessage.isBlank()) {
            return "Error: Commit message cannot be empty.";
        }
        try {
            // First, stage all changes
            String addOutput = executeCommand("git", "add", ".");
            
            // Second, execute the commit
            String commitOutput = executeCommand("git", "commit", "-m", commitMessage.trim());
            
            return "Successfully committed changes.\nCommit Output:\n" + commitOutput;
        } catch (Exception e) {
            return "Error committing changes: " + e.getMessage();
        }
    }

    /**
     * Helper method to execute shell commands securely and capture output.
     */
    private String executeCommand(String... command) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(workingDirectory);
        builder.redirectErrorStream(true); // Merge stderr into stdout

        Process process = builder.start();
        
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
        }

        boolean finished = process.waitFor(10, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new RuntimeException("Command timed out after 10 seconds.");
        }

        return output.toString().trim();
    }
}
