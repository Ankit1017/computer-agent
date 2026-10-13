package com.agent.tools.git;

import com.agent.tools.git.services.GitExecutionService;
import com.agent.tools.terminal.security.SecurityInterceptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class GitToolsFacadeTest {

    private SecurityInterceptor security;
    private GitExecutionService gitService;
    private GitToolsFacade facade;

    @BeforeEach
    void setUp() {
        security = mock(SecurityInterceptor.class);
        gitService = mock(GitExecutionService.class);
        facade = new GitToolsFacade(security, gitService);
    }

    @Test
    @DisplayName("Should delegate getGitStatus to gitService")
    void shouldDelegateGetGitStatus() {
        when(gitService.getStatus()).thenReturn("On branch master\nnothing to commit");

        String result = facade.getGitStatus();

        assertThat(result).isEqualTo("On branch master\nnothing to commit");
        verify(gitService).getStatus();
        verifyNoInteractions(security);
    }

    @Test
    @DisplayName("Should delegate getGitDiff to gitService")
    void shouldDelegateGetGitDiff() {
        when(gitService.getDiff()).thenReturn("diff --git a/file b/file");

        String result = facade.getGitDiff();

        assertThat(result).isEqualTo("diff --git a/file b/file");
        verify(gitService).getDiff();
        verifyNoInteractions(security);
    }

    @Test
    @DisplayName("Should allow commit when security approves")
    void shouldAllowCommitWhenSecurityApproves() {
        when(security.requestPermission(eq("commit git changes"), anyString())).thenReturn(true);
        when(gitService.commitChanges("feat: test")).thenReturn("Committed successfully");

        String result = facade.commitChanges("feat: test");

        assertThat(result).isEqualTo("Committed successfully");
        verify(security).requestPermission(eq("commit git changes"), contains("feat: test"));
        verify(gitService).commitChanges("feat: test");
    }

    @Test
    @DisplayName("Should block commit when security denies")
    void shouldBlockCommitWhenSecurityDenies() {
        when(security.requestPermission(eq("commit git changes"), anyString())).thenReturn(false);

        String result = facade.commitChanges("feat: test");

        assertThat(result).startsWith("ACTION REJECTED: User explicitly denied the git commit.");
        verify(security).requestPermission(eq("commit git changes"), anyString());
        verifyNoInteractions(gitService);
    }

    @Test
    @DisplayName("Should return error message when gitService throws exception")
    void shouldHandleExceptionDuringCommit() {
        when(security.requestPermission(eq("commit git changes"), anyString())).thenReturn(true);
        when(gitService.commitChanges("feat: test")).thenThrow(new RuntimeException("Git failure"));

        String result = facade.commitChanges("feat: test");

        assertThat(result).isEqualTo("Error committing changes: Git failure");
        verify(gitService).commitChanges("feat: test");
    }
}
