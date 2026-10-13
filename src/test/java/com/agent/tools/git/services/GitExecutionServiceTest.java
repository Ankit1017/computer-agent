package com.agent.tools.git.services;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class GitExecutionServiceTest {

    private final GitExecutionService service = new GitExecutionService();

    @Test
    @DisplayName("Should return valid git status for current project repository")
    void shouldReturnValidGitStatus() {
        String status = service.getStatus();
        assertThat(status).satisfiesAnyOf(
                s -> assertThat(s).startsWith("Current Git Status"),
                s -> assertThat(s).isEqualTo("Working tree is clean. No changes to commit.")
        );
    }

    @Test
    @DisplayName("Should return valid git diff for current project repository")
    void shouldReturnValidGitDiff() {
        String diff = service.getDiff();
        assertThat(diff).satisfiesAnyOf(
                s -> assertThat(s).contains("CHANGES"),
                s -> assertThat(s).isEqualTo("No diff available. Working tree is clean.")
        );
    }

    @Test
    @DisplayName("Should return error message when directory is not a git repository")
    void shouldReturnErrorWhenNotGitRepo(@TempDir Path tempDir) {
        GitExecutionService invalidService = new GitExecutionService(tempDir.toFile());
        String status = invalidService.getStatus();
        assertThat(status).containsAnyOf("Error retrieving git status", "fatal: not a git repository");
    }

    @Test
    @DisplayName("Should return error when committing in a non-git directory")
    void shouldReturnErrorWhenCommittingInNonGitDir(@TempDir Path tempDir) {
        GitExecutionService invalidService = new GitExecutionService(tempDir.toFile());
        String result = invalidService.commitChanges("test commit");
        assertThat(result).containsAnyOf("Error committing changes", "fatal: not a git repository");
    }
}
