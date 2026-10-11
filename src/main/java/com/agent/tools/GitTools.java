package com.agent.tools;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Scanner;
import java.util.concurrent.TimeUnit;

/**
 * Dedicated Git version control tools for the autonomous agent.
 * Prevents context exhaustion by truncating diff outputs and enforces
 * Human-In-The-Loop (HITL) safety verification for commits.
 */
public class GitTools {

    private static final Logger log = LoggerFactory.getLogger(GitTools.class);
    private static final int MAX_DIFF_CHARACTERS = 3000;
    private static final long PROCESS_TIMEOUT_SECONDS = 30;

    private final Scanner consoleScanner;
    private final Path workingDir;

    public GitTools(Scanner consoleScanner) {
        this(consoleScanner, Paths.get(".").toAbsolutePath().normalize());
    }

    public GitTools(Scanner consoleScanner, Path workingDir) {
        this.consoleScanner = Objects.requireNonNull(consoleScanner, "consoleScanner must not be null");
        this.workingDir = Objects.requireNonNull(workingDir, "workingDir must not be null");
    }

    /**
     * Gets the current Git status showing modified, added, and untracked files in short format.
     *
     * @return short git status output
     */
    @Tool("Get the current Git status showing modified, added, and untracked files.")
    public String getGitStatus() {
        log.info("Tool Call: getGitStatus()");
        GitResult result = executeGitCommand("status", "--short");

        if (result.exitCode != 0) {
            return "Error: Failed to get git status (exit code " + result.exitCode + "): " + result.output;
        }

        if (result.output.isBlank()) {
            return "[Clean: no modified, added, or untracked files]";
        }

        return result.output;
    }

    /**
     * Gets the Git diff for a specific file to inspect line changes.
     * Truncates the output to 3,000 characters to prevent context window overflow.
     *
     * @param filePath specific file path to diff
     * @return truncated diff output
     */
    @Tool("Get the Git diff for a specific file to see what lines changed. Do not use this without a specific file path.")
    public String getGitDiff(@P("specific file path to inspect diff") String filePath) {
        if (filePath == null || filePath.isBlank()) {
            return "Error: File path cannot be empty.";
        }

        log.info("Tool Call: getGitDiff(filePath='{}')", filePath);
        GitResult result = executeGitCommand("diff", "--", filePath.trim());

        if (result.exitCode != 0) {
            return "Error: Failed to get git diff (exit code " + result.exitCode + "): " + result.output;
        }

        if (result.output.isBlank()) {
            return "[No diff found for: " + filePath.trim() + "]";
        }

        String diff = result.output;
        if (diff.length() > MAX_DIFF_CHARACTERS) {
            return diff.substring(0, MAX_DIFF_CHARACTERS) + "\n...[DIFF TRUNCATED TO PRESERVE CONTEXT]";
        }

        return diff;
    }

    /**
     * Stages all changes and commits them to Git with the provided message after HITL approval.
     *
     * @param commitMessage descriptive commit message
     * @return commit output or denial confirmation
     */
    @Tool("Stage all changes and commit them to the Git repository with a message.")
    public String commitChanges(@P("descriptive commit message") String commitMessage) {
        if (commitMessage == null || commitMessage.isBlank()) {
            return "Error: Commit message cannot be empty.";
        }

        log.warn("HITL Intercept: Agent requested git commit with message: '{}'", commitMessage);

        System.out.println("\n[SECURITY] Agent requested to commit with message: " + commitMessage);
        System.out.print("Allow commit? (Y/N): ");
        System.out.flush();

        String confirmation = "";
        if (consoleScanner.hasNextLine()) {
            confirmation = consoleScanner.nextLine().trim();
        }

        if (!"Y".equalsIgnoreCase(confirmation)) {
            log.info("HITL Intercept: User rejected git commit ('{}')", confirmation);
            return "User denied git commit.";
        }

        log.info("HITL Intercept: User approved git commit. Staging and committing changes...");

        // 1. Stage all changes
        GitResult addResult = executeGitCommand("add", ".");
        if (addResult.exitCode != 0) {
            log.error("git add failed: {}", addResult.output);
            return "Error: Failed to stage changes: " + addResult.output;
        }

        // 2. Commit staged changes
        GitResult commitResult = executeGitCommand("commit", "-m", commitMessage);
        if (commitResult.exitCode != 0) {
            log.error("git commit failed: {}", commitResult.output);
            return "Error: Failed to commit changes: " + commitResult.output;
        }

        return commitResult.output;
    }

    private GitResult executeGitCommand(String... args) {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.addAll(Arrays.asList(args));

        try {
            ProcessBuilder processBuilder = new ProcessBuilder(command);
            if (workingDir != null) {
                processBuilder.directory(workingDir.toFile());
            }
            processBuilder.redirectErrorStream(true);

            Process process = processBuilder.start();

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append(System.lineSeparator());
                }
            }

            boolean finished = process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return new GitResult(-1, "Error: Git command timed out after " + PROCESS_TIMEOUT_SECONDS + " seconds.");
            }

            return new GitResult(process.exitValue(), output.toString().trim());

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new GitResult(-1, "Error: Git process interrupted: " + e.getMessage());
        } catch (Exception e) {
            log.error("Failed to execute git command: " + command, e);
            return new GitResult(-1, "Error: Failed to execute git command: " + e.getMessage());
        }
    }

    private static class GitResult {
        final int exitCode;
        final String output;

        GitResult(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output;
        }
    }
}
