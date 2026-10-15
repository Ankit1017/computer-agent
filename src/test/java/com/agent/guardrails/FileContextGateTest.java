package com.agent.guardrails;

import com.agent.tools.terminal.security.SecurityInterceptor;
import com.agent.telemetry.InMemoryTraceExporter;
import com.agent.telemetry.SpanStatus;
import com.agent.telemetry.SwarmSpan;
import com.agent.telemetry.SwarmTelemetry;
import com.agent.telemetry.TelemetryAttributes;
import com.agent.tools.terminal.TerminalToolsFacade;
import com.agent.tools.terminal.services.CommandExecutorService;
import com.agent.tools.terminal.services.FileSystemService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Scanner;

import static org.assertj.core.api.Assertions.assertThat;

class FileContextGateTest {

    private FileSystemService fileSystemService;
    private TerminalToolsFacade facade;

    @BeforeEach
    void setUp() {
        SwarmTelemetry.reset();
        FileContextGate.reset();
        fileSystemService = new FileSystemService();
        fileSystemService.setEnforceReadBeforeWrite(true);

        SecurityInterceptor security = new SecurityInterceptor(new Scanner(new ByteArrayInputStream("Y\nY\nY\nY\nY\n".getBytes())));
        CommandExecutorService commandService = new CommandExecutorService(10, 5000);
        facade = new TerminalToolsFacade(security, commandService, fileSystemService);
    }

    @AfterEach
    void tearDown() {
        SwarmTelemetry.reset();
        FileContextGate.reset();
    }

    @AfterAll
    static void tearDownAll() {
        SwarmTelemetry.reset();
        FileContextGate.reset();
    }

    @Test
    @DisplayName("Scenario 1: New file creation pass-through without prior read + unified diff")
    void testNewFileCreation_allowedWithoutRead(@TempDir Path tempDir) throws IOException {
        Path newFile = tempDir.resolve("new_module.py");
        assertThat(Files.exists(newFile)).isFalse();

        String content = "def hello():\n    print('Hello World')\n";
        String result = fileSystemService.writeFile(newFile.toString(), content);

        assertThat(result).contains("Successfully wrote");
        assertThat(result).contains("--- a/new_module.py");
        assertThat(result).contains("+++ b/new_module.py");
        assertThat(result).contains("+def hello():");
        assertThat(result).contains("+    print('Hello World')");
        assertThat(Files.exists(newFile)).isTrue();
        assertThat(Files.readString(newFile)).isEqualTo(content);
    }

    @Test
    @DisplayName("Scenario 2: Unread existing file is blocked on writeFile, replaceLines, and regexReplace")
    void testUnreadExistingFile_blockedWithReadBeforeWrite(@TempDir Path tempDir) throws IOException {
        Path existing = tempDir.resolve("config.yaml");
        String originalContent = "server:\n  port: 8080\n  host: localhost\n";
        Files.writeString(existing, originalContent);

        // A. writeFile on unread existing file -> BLOCKED
        String writeResult = fileSystemService.writeFile(existing.toString(), "server:\n  port: 9090\n");
        assertThat(writeResult).startsWith("[GUARDRAIL BLOCKED: READ_BEFORE_WRITE]");
        assertThat(writeResult).contains("Existing file 'config.yaml' cannot be modified before reading its contents");
        assertThat(Files.readString(existing)).isEqualTo(originalContent);
        assertThat(Files.exists(tempDir.resolve("config.yaml.bak"))).isFalse();

        // B. replaceLines on unread existing file -> BLOCKED
        String replaceResult = fileSystemService.replaceLines(existing.toString(), 2, 2, "  port: 9090");
        assertThat(replaceResult).startsWith("[GUARDRAIL BLOCKED: READ_BEFORE_WRITE]");
        assertThat(Files.readString(existing)).isEqualTo(originalContent);
        assertThat(Files.exists(tempDir.resolve("config.yaml.bak"))).isFalse();

        // C. regexReplaceInFile on unread existing file -> BLOCKED
        String regexResult = fileSystemService.regexReplaceInFile(existing.toString(), "port: 8080", "port: 9090");
        assertThat(regexResult).startsWith("[GUARDRAIL BLOCKED: READ_BEFORE_WRITE]");
        assertThat(Files.readString(existing)).isEqualTo(originalContent);
        assertThat(Files.exists(tempDir.resolve("config.yaml.bak"))).isFalse();
    }

    @Test
    @DisplayName("Scenario 3: Read-then-write succeeds, creates .bak, and returns unified diff")
    void testReadThenWrite_succeedsWithUnifiedDiff(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("app.properties");
        String initialContent = "db.host=127.0.0.1\ndb.port=5432\ndb.user=admin\n";
        Files.writeString(file, initialContent);

        // 1. Read file to capture context snapshot
        String readLinesOutput = fileSystemService.readFileLines(file.toString(), 1, 3);
        assertThat(readLinesOutput).contains("db.port=5432");

        // 2. Perform replaceLines
        String replaceResult = fileSystemService.replaceLines(file.toString(), 2, 2, "db.port=5433");
        assertThat(replaceResult).contains("Successfully replaced lines 2 to 2");
        assertThat(replaceResult).contains("Original backed up to .bak");

        // Assert Unified Diff contains diff hunk
        assertThat(replaceResult).contains("--- a/app.properties");
        assertThat(replaceResult).contains("+++ b/app.properties");
        assertThat(replaceResult).contains("-db.port=5432");
        assertThat(replaceResult).contains("+db.port=5433");

        // Assert disk contents & backup
        assertThat(Files.readString(file)).contains("db.port=5433");
        Path backup = tempDir.resolve("app.properties.bak");
        assertThat(Files.exists(backup)).isTrue();
        assertThat(Files.readString(backup)).isEqualTo(initialContent);
    }

    @Test
    @DisplayName("Scenario 4: Sequential writes succeed without re-reading due to snapshot auto-refresh")
    void testSequentialWrites_withoutReReading(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("pipeline.sh");
        Files.writeString(file, "#!/bin/bash\necho step 1\necho step 2\n");

        // Initial Read
        fileSystemService.readFile(file.toString());

        // First Write
        String res1 = fileSystemService.replaceLines(file.toString(), 2, 2, "echo step 1 modified");
        assertThat(res1).contains("Successfully replaced lines 2 to 2");
        assertThat(res1).contains("+echo step 1 modified");

        // Second Write immediately without calling readFile again -> MUST succeed!
        String res2 = fileSystemService.replaceLines(file.toString(), 3, 3, "echo step 2 modified");
        assertThat(res2).contains("Successfully replaced lines 3 to 3");
        assertThat(res2).contains("+echo step 2 modified");

        // Third Write (regex) immediately -> MUST succeed!
        String res3 = fileSystemService.regexReplaceInFile(file.toString(), "#!/bin/bash", "#!/usr/bin/env bash");
        assertThat(res3).contains("Successfully applied regex replacement");
        assertThat(res3).contains("+#!/usr/bin/env bash");

        assertThat(Files.readAllLines(file)).containsExactly("#!/usr/bin/env bash", "echo step 1 modified", "echo step 2 modified");
    }

    @Test
    @DisplayName("Scenario 5: External stale modification trips STALE_FILE_CONTEXT until refreshed by read")
    void testExternalStaleModification_blockedUntilRefreshed(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("service.conf");
        Files.writeString(file, "timeout=30\nretries=3\n");

        // 1. Agent reads file
        fileSystemService.readFileLines(file.toString(), 1, 2);

        // 2. An external process or git pull changes the file on disk behind agent's back!
        Files.writeString(file, "timeout=60\nretries=5\nexternal_flag=true\n");

        // 3. Agent attempts to write -> MUST BE BLOCKED as STALE_FILE_CONTEXT!
        String writeResult = fileSystemService.writeFile(file.toString(), "timeout=30\nretries=4\n");
        assertThat(writeResult).startsWith("[GUARDRAIL BLOCKED: STALE_FILE_CONTEXT]");
        assertThat(writeResult).contains("File 'service.conf' has been modified externally since it was last read.");

        // File remains as modified by external process
        assertThat(Files.readString(file)).contains("external_flag=true");

        // 4. Agent calls readFileLines again to refresh its context
        fileSystemService.readFileLines(file.toString(), 1, 3);

        // 5. Now write MUST succeed
        String updatedResult = fileSystemService.writeFile(file.toString(), "timeout=60\nretries=4\nexternal_flag=true\n");
        assertThat(updatedResult).contains("Successfully wrote");
        assertThat(updatedResult).contains("-retries=5");
        assertThat(updatedResult).contains("+retries=4");
        assertThat(Files.readString(file)).contains("retries=4");
    }

    @Test
    @DisplayName("Scenario 6: OpenTelemetry span attribution when FileContextGate blocks a write")
    void testTelemetrySpanAttribution_onGateBlock(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("untracked.txt");
        Files.writeString(file, "some content\n");

        // Execute via facade inside an active agent.invoke span
        try (SwarmSpan agentSpan = SwarmTelemetry.startSpan("agent.invoke")) {
            String facadeResult = facade.writeFile(file.toString(), "new content\n");
            assertThat(facadeResult).startsWith("[GUARDRAIL BLOCKED: READ_BEFORE_WRITE]");
        }

        InMemoryTraceExporter exporter = SwarmTelemetry.getInMemoryExporter();
        List<SwarmSpan> toolSpans = exporter.getSpansByName("tool.execute");
        assertThat(toolSpans).isNotEmpty();

        SwarmSpan writeSpan = toolSpans.get(0);
        assertThat(writeSpan.getAttribute(TelemetryAttributes.GUARDRAIL_BLOCKED)).isEqualTo(true);
        assertThat(writeSpan.getAttribute(TelemetryAttributes.GUARDRAIL_REASON)).isEqualTo(FileContextGate.REASON_READ_BEFORE_WRITE);
        assertThat(writeSpan.getStatus()).isEqualTo(SpanStatus.ERROR);
        assertThat(writeSpan.getEvents()).anyMatch(e -> "guardrail.file_gate_blocked".equals(e.name()));
    }

    @Test
    @DisplayName("Scenario 7: UnifiedDiffGenerator standalone verification with context and line caps")
    void testUnifiedDiffGenerator_standalone() {
        String oldText = "line 1\nline 2\nline 3\nline 4\nline 5\n";
        String newText = "line 1\nline 2 modified\nline 3\nline 4\nline 5\n";

        String diff = UnifiedDiffGenerator.generateDiff("Sample.java", oldText, newText);
        assertThat(diff).contains("--- a/Sample.java");
        assertThat(diff).contains("+++ b/Sample.java");
        assertThat(diff).contains("@@ -1,5 +1,5 @@");
        assertThat(diff).contains("-line 2");
        assertThat(diff).contains("+line 2 modified");

        // Line Cap Truncation test
        StringBuilder hugeOld = new StringBuilder();
        StringBuilder hugeNew = new StringBuilder();
        for (int i = 1; i <= 100; i++) {
            hugeOld.append("old line ").append(i).append("\n");
            hugeNew.append("new line ").append(i).append("\n");
        }

        String cappedDiff = UnifiedDiffGenerator.generateDiff("Huge.txt", hugeOld.toString(), hugeNew.toString(), 20);
        assertThat(cappedDiff).contains("... [DIFF TRUNCATED AT 20 LINES] ...");
    }
}
