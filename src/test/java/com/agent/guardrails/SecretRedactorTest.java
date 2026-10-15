package com.agent.guardrails;

import com.agent.orchestration.workflow.checkpoint.JsonFileCheckpointer;
import com.agent.orchestration.workflow.checkpoint.WorkflowCheckpoint;
import com.agent.orchestration.workflow.checkpoint.WorkflowExecutionStatus;
import com.agent.telemetry.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class SecretRedactorTest {

    @BeforeEach
    void setUp() {
        SwarmTelemetry.reset();
    }

    @AfterEach
    void tearDown() {
        SwarmTelemetry.reset();
    }

    @Test
    @DisplayName("Scenario 1: AI, Cloud, GitHub, and AWS API keys must be masked with exact tags")
    void shouldRedactApiKeysAndCloudTokens() {
        String input = """
                Deploying cluster with credentials:
                OpenAI: sk-proj-abc1234567890def123456
                Anthropic: sk-ant-api03-abcdef1234567890
                Google: AIzaSyD-1234567890abcdef1234567890abc
                Slack: xoxb-1234567890-abcdef1234
                GitHub Classic: ghp_1234567890abcdef1234567890
                GitHub FineGrained: github_pat_1234567890abcdef1234567890
                AWS Root: AKIAIOSFODNN7EXAMPLE
                AWS STS: ASIASAMPLESTSKEY12
                """;

        RedactionResult result = SecretRedactor.redactDetailed(input);

        assertThat(result.modified()).isTrue();
        assertThat(result.redactionCount()).isEqualTo(8);
        assertThat(result.redactedTypes()).contains("API_KEY", "GITHUB_TOKEN", "AWS_KEY");

        String redacted = result.redactedText();
        assertThat(redacted).doesNotContain("sk-proj-");
        assertThat(redacted).doesNotContain("sk-ant-");
        assertThat(redacted).doesNotContain("AIzaSyD-");
        assertThat(redacted).doesNotContain("xoxb-");
        assertThat(redacted).doesNotContain("ghp_");
        assertThat(redacted).doesNotContain("github_pat_");
        assertThat(redacted).doesNotContain("AKIAIOSFODNN7EXAMPLE");
        assertThat(redacted).doesNotContain("ASIASAMPLESTSKEY12");

        assertThat(redacted).contains("OpenAI: [REDACTED:API_KEY]");
        assertThat(redacted).contains("Anthropic: [REDACTED:API_KEY]");
        assertThat(redacted).contains("Google: [REDACTED:API_KEY]");
        assertThat(redacted).contains("Slack: [REDACTED:API_KEY]");
        assertThat(redacted).contains("GitHub Classic: [REDACTED:GITHUB_TOKEN]");
        assertThat(redacted).contains("GitHub FineGrained: [REDACTED:GITHUB_TOKEN]");
        assertThat(redacted).contains("AWS Root: [REDACTED:AWS_KEY]");
        assertThat(redacted).contains("AWS STS: [REDACTED:AWS_KEY]");
    }

    @Test
    @DisplayName("Scenario 2: Bearer tokens, PEM private keys, and Database URI passwords must be redacted")
    void shouldRedactBearerPemAndDbPasswords() {
        String input = """
                Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.secretpayload.sig123456789
                Database 1: postgresql://dbadmin:SuperSecretPw!123@prod-db.internal:5432/main
                Database 2: mysql://root:MySqlPass999@localhost:3306/app
                Database 3: mongodb+srv://app_user:MongoSecret789@cluster0.mongodb.net/data
                Database 4: redis://default:RedisAuthPass00@redis.internal:6379/0
                
                -----BEGIN RSA PRIVATE KEY-----
                MIIEowIBAAKCAQEA04abc123...
                FAKE_BASE64_PRIVATE_KEY_DATA
                -----END RSA PRIVATE KEY-----
                """;

        RedactionResult result = SecretRedactor.redactDetailed(input);

        assertThat(result.modified()).isTrue();
        assertThat(result.redactedTypes()).contains("BEARER_TOKEN", "DB_PASSWORD", "PRIVATE_KEY");

        String redacted = result.redactedText();
        assertThat(redacted).contains("Authorization: Bearer [REDACTED:BEARER_TOKEN]");
        assertThat(redacted).doesNotContain("secretpayload");

        assertThat(redacted).contains("postgresql://dbadmin:[REDACTED:DB_PASSWORD]@prod-db.internal:5432/main");
        assertThat(redacted).doesNotContain("SuperSecretPw!123");

        assertThat(redacted).contains("mysql://root:[REDACTED:DB_PASSWORD]@localhost:3306/app");
        assertThat(redacted).doesNotContain("MySqlPass999");

        assertThat(redacted).contains("mongodb+srv://app_user:[REDACTED:DB_PASSWORD]@cluster0.mongodb.net/data");
        assertThat(redacted).doesNotContain("MongoSecret789");

        assertThat(redacted).contains("redis://default:[REDACTED:DB_PASSWORD]@redis.internal:6379/0");
        assertThat(redacted).doesNotContain("RedisAuthPass00");

        assertThat(redacted).contains("[REDACTED:PRIVATE_KEY]");
        assertThat(redacted).doesNotContain("FAKE_BASE64_PRIVATE_KEY_DATA");
    }

    @Test
    @DisplayName("Scenario 3: JSON, .env, and YAML key-value secret assignments must be redacted")
    void shouldRedactKeyValueSecretAssignments() {
        String json = "{\"username\": \"ankit\", \"password\": \"MyPassword123!\"}";
        String redactedJson = SecretRedactor.redact(json);
        assertThat(redactedJson).contains("\"username\": \"ankit\"");
        assertThat(redactedJson).contains("\"password\": \"[REDACTED:SECRET]\"");
        assertThat(redactedJson).doesNotContain("MyPassword123!");

        String env = """
                OPENAI_API_KEY=sk-1234567890abcdef12345678
                DB_PASSWORD=mySuperSecret123
                PORT=8080
                """;
        String redactedEnv = SecretRedactor.redact(env);
        assertThat(redactedEnv).contains("OPENAI_API_KEY=[REDACTED:API_KEY]");
        assertThat(redactedEnv).contains("DB_PASSWORD=[REDACTED:SECRET]");
        assertThat(redactedEnv).contains("PORT=8080");
        assertThat(redactedEnv).doesNotContain("mySuperSecret123");

        String yaml = """
                server:
                  client_secret: SuperSecretClientCode123
                  host: example.com
                """;
        String redactedYaml = SecretRedactor.redact(yaml);
        assertThat(redactedYaml).contains("client_secret: [REDACTED:SECRET]");
        assertThat(redactedYaml).contains("host: example.com");
        assertThat(redactedYaml).doesNotContain("SuperSecretClientCode123");

        // Idempotency: verify already redacted values are not re-redacted
        String alreadyRedacted = "{\"password\": \"[REDACTED:SECRET]\"}";
        RedactionResult rerun = SecretRedactor.redactDetailed(alreadyRedacted);
        assertThat(rerun.modified()).isFalse();
        assertThat(rerun.redactedText()).isEqualTo(alreadyRedacted);
    }

    @Test
    @DisplayName("Scenario 4: Deep Map and List traversal must scrub nested credentials")
    void shouldRedactDeepNestedMapAndList() {
        Map<String, Object> input = Map.of(
                "user", "alice",
                "password", "RawSecretPassword123",
                "config", Map.of(
                        "api_key", "sk-proj-xyz1234567890123",
                        "public_id", "pub-42"
                ),
                "tokens", List.of("ghp_1234567890abcdef1234567890", "harmless_token")
        );

        Map<String, Object> redacted = SecretRedactor.redactMap(input);

        assertThat(redacted.get("user")).isEqualTo("alice");
        assertThat(redacted.get("password")).isEqualTo("[REDACTED:SECRET]");

        @SuppressWarnings("unchecked")
        Map<String, Object> config = (Map<String, Object>) redacted.get("config");
        assertThat(config.get("api_key")).isEqualTo("[REDACTED:API_KEY]");
        assertThat(config.get("public_id")).isEqualTo("pub-42");

        @SuppressWarnings("unchecked")
        List<Object> tokens = (List<Object>) redacted.get("tokens");
        assertThat(tokens.get(0)).isEqualTo("[REDACTED:GITHUB_TOKEN]");
        assertThat(tokens.get(1)).isEqualTo("harmless_token");
    }

    @Test
    @DisplayName("Scenario 5: End-to-end Telemetry & Disk Trace Redaction")
    void shouldScrubTelemetryAndDiskTraces(@TempDir Path tempDir) throws IOException {
        Path traceFile = tempDir.resolve("swarm_traces.jsonl");
        JsonLinesTraceExporter fileExporter = new JsonLinesTraceExporter(traceFile);
        SwarmTelemetry.addExporter(fileExporter);

        String rawSecretArg = "{\"key\": \"sk-proj-1234567890abcdef123\", \"auth\": \"Bearer myTopSecretToken12345\"}";

        SwarmTelemetry.executeTool("deployCluster", rawSecretArg, () -> "Deployment initiated");

        // 1. Check in-memory span
        List<SwarmSpan> spans = SwarmTelemetry.getInMemoryExporter().getFinishedSpans();
        assertThat(spans).isNotEmpty();
        SwarmSpan lastSpan = spans.get(spans.size() - 1);

        assertThat(lastSpan.getAttributes().get(TelemetryAttributes.GUARDRAIL_REDACTED)).isEqualTo(true);
        assertThat(lastSpan.getAttributes().get(TelemetryAttributes.GUARDRAIL_REDACTION_COUNT)).isNotNull();

        String spanArgs = (String) lastSpan.getAttribute(TelemetryAttributes.TOOL_ARGUMENTS_JSON);
        assertThat(spanArgs).doesNotContain("sk-proj-");
        assertThat(spanArgs).doesNotContain("myTopSecretToken12345");
        assertThat(spanArgs).contains("[REDACTED:API_KEY]");
        assertThat(spanArgs).contains("[REDACTED:BEARER_TOKEN]");

        // 2. Check disk .jsonl file
        assertThat(Files.exists(traceFile)).isTrue();
        String fileContent = Files.readString(traceFile);
        assertThat(fileContent).doesNotContain("sk-proj-");
        assertThat(fileContent).doesNotContain("myTopSecretToken12345");
        assertThat(fileContent).contains("[REDACTED:API_KEY]");
        assertThat(fileContent).contains("[REDACTED:BEARER_TOKEN]");

        // 3. Check TelemetryToolsFacade
        TelemetryToolsFacade facade = new TelemetryToolsFacade();
        String tracesJson = facade.getRecentTraces(5);
        assertThat(tracesJson).doesNotContain("sk-proj-");
        assertThat(tracesJson).doesNotContain("myTopSecretToken12345");
        assertThat(tracesJson).contains("[REDACTED:API_KEY]");
        assertThat(tracesJson).contains("[REDACTED:BEARER_TOKEN]");

        SwarmTelemetry.removeExporter(fileExporter);
    }

    @Test
    @DisplayName("Scenario 6: Disk Checkpoint Redaction via JsonFileCheckpointer")
    void shouldRedactCheckpointsBeforePersistingToDisk(@TempDir Path tempDir) throws IOException {
        JsonFileCheckpointer checkpointer = new JsonFileCheckpointer(tempDir);

        String threadId = "test-thread-sec-1";
        Map<String, Object> stateWithSecrets = Map.of(
                "token", "ghp_1234567890abcdef1234567890",
                "api_key", "sk-proj-secretkey1234567890",
                "password", "RawSuperSecretPassword123",
                "public_data", "safe_value"
        );

        WorkflowCheckpoint checkpoint = new WorkflowCheckpoint(
                threadId,
                "workflow-sec",
                1,
                Set.of("nodeA"),
                stateWithSecrets,
                System.currentTimeMillis(),
                WorkflowExecutionStatus.RUNNING,
                Set.of(),
                null,
                List.of()
        );

        checkpointer.save(checkpoint);

        // Read the persisted JSON from disk directly
        Path threadDir = tempDir.resolve(threadId);
        Path checkpointFile = threadDir.resolve("checkpoint_step_0001.json");
        assertThat(Files.exists(checkpointFile)).isTrue();

        String rawJsonOnDisk = Files.readString(checkpointFile);
        assertThat(rawJsonOnDisk).doesNotContain("ghp_1234567890abcdef1234567890");
        assertThat(rawJsonOnDisk).doesNotContain("sk-proj-secretkey1234567890");
        assertThat(rawJsonOnDisk).doesNotContain("RawSuperSecretPassword123");

        assertThat(rawJsonOnDisk).contains("[REDACTED:GITHUB_TOKEN]");
        assertThat(rawJsonOnDisk).contains("[REDACTED:API_KEY]");
        assertThat(rawJsonOnDisk).contains("[REDACTED:SECRET]");
        assertThat(rawJsonOnDisk).contains("safe_value");

        // Reload via checkpointer and verify sanitized state is restored
        WorkflowCheckpoint loaded = checkpointer.loadLatest(threadId).orElseThrow();
        assertThat(loaded.stateData().get("token")).isEqualTo("[REDACTED:GITHUB_TOKEN]");
        assertThat(loaded.stateData().get("api_key")).isEqualTo("[REDACTED:API_KEY]");
        assertThat(loaded.stateData().get("password")).isEqualTo("[REDACTED:SECRET]");
        assertThat(loaded.stateData().get("public_data")).isEqualTo("safe_value");
    }
}
