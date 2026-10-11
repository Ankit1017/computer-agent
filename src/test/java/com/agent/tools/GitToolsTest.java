package com.agent.tools;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Scanner;

import static org.assertj.core.api.Assertions.assertThat;

class GitToolsTest {

    private void runCommand(Path workingDir, String... args) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(args)
                .directory(workingDir.toFile())
                .redirectErrorStream(true)
                .start();
        process.waitFor();
    }

    private void initGitRepo(Path tempDir) throws IOException, InterruptedException {
        runCommand(tempDir, "git", "init");
        runCommand(tempDir, "git", "config", "user.name", "TestUser");
        runCommand(tempDir, "git", "config", "user.email", "test@example.com");
    }

    @Test
    @DisplayName("Should return short git status showing untracked files")
    void shouldReturnShortGitStatus(@TempDir Path tempDir) throws Exception {
        initGitRepo(tempDir);
        Files.writeString(tempDir.resolve("sample.txt"), "hello world\n");

        Scanner scanner = new Scanner(new ByteArrayInputStream("".getBytes()));
        GitTools gitTools = new GitTools(scanner, tempDir);

        String status = gitTools.getGitStatus();
        assertThat(status).contains("?? sample.txt");
    }

    @Test
    @DisplayName("Should truncate git diff when exceeding 3000 characters")
    void shouldTruncateGitDiffWhenExceedingLimit(@TempDir Path tempDir) throws Exception {
        initGitRepo(tempDir);
        Path file = tempDir.resolve("large.txt");
        Files.writeString(file, "initial\n");
        runCommand(tempDir, "git", "add", ".");
        runCommand(tempDir, "git", "commit", "-m", "init");

        // Write > 3000 chars of additions
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            sb.append("added line with number: ").append(i).append("\n");
        }
        Files.writeString(file, sb.toString());

        Scanner scanner = new Scanner(new ByteArrayInputStream("".getBytes()));
        GitTools gitTools = new GitTools(scanner, tempDir);

        String diff = gitTools.getGitDiff("large.txt");
        assertThat(diff).contains("[DIFF TRUNCATED TO PRESERVE CONTEXT]");
        assertThat(diff.length()).isLessThanOrEqualTo(3000 + "\n...[DIFF TRUNCATED TO PRESERVE CONTEXT]".length() + 50);
    }

    @Test
    @DisplayName("Should commit changes when user enters 'Y'")
    void shouldCommitChangesWhenApproved(@TempDir Path tempDir) throws Exception {
        initGitRepo(tempDir);
        Files.writeString(tempDir.resolve("test.txt"), "content\n");

        Scanner scanner = new Scanner(new ByteArrayInputStream("Y\n".getBytes()));
        GitTools gitTools = new GitTools(scanner, tempDir);

        String result = gitTools.commitChanges("feat: initial commit");
        assertThat(result).contains("initial commit");

        String status = gitTools.getGitStatus();
        assertThat(status).contains("[Clean: no modified, added, or untracked files]");
    }

    @Test
    @DisplayName("Should reject commit when user enters 'N'")
    void shouldRejectCommitWhenDenied(@TempDir Path tempDir) throws Exception {
        initGitRepo(tempDir);
        Files.writeString(tempDir.resolve("test.txt"), "content\n");

        Scanner scanner = new Scanner(new ByteArrayInputStream("N\n".getBytes()));
        GitTools gitTools = new GitTools(scanner, tempDir);

        String result = gitTools.commitChanges("feat: rejected commit");
        assertThat(result).isEqualTo("User denied git commit.");

        String status = gitTools.getGitStatus();
        assertThat(status).contains("?? test.txt");
    }

    @Test
    @DisplayName("Should validate parameters for diff and commit")
    void shouldValidateParameters() {
        Scanner scanner = new Scanner(new ByteArrayInputStream("".getBytes()));
        GitTools gitTools = new GitTools(scanner);

        assertThat(gitTools.getGitDiff("")).isEqualTo("Error: File path cannot be empty.");
        assertThat(gitTools.commitChanges("")).isEqualTo("Error: Commit message cannot be empty.");
    }
}
