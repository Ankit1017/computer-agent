package com.agent.tools.git.services;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GitExecutionServiceTest {

    private final GitExecutionService service = new GitExecutionService();

    @Test
    @DisplayName("Should return placeholder for getStatus")
    void shouldReturnPlaceholderStatus() {
        String status = service.getStatus();
        assertThat(status).contains("Placeholder: Git status");
    }

    @Test
    @DisplayName("Should return placeholder for getDiff")
    void shouldReturnPlaceholderDiff() {
        String diff = service.getDiff();
        assertThat(diff).contains("Placeholder: Git diff");
    }

    @Test
    @DisplayName("Should return placeholder for commitChanges with message")
    void shouldReturnPlaceholderCommit() {
        String result = service.commitChanges("Initial commit");
        assertThat(result).contains("Placeholder: Successfully committed changes with message: 'Initial commit'");
    }
}
