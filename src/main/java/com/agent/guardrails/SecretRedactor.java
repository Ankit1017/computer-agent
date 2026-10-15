package com.agent.guardrails;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * High-performance, zero-dependency secret and credential redaction engine.
 * Automatically masks API keys, cloud tokens, database connection credentials,
 * PEM private keys, bearer tokens, and sensitive key-value pairs across telemetry and disk persistence.
 */
public final class SecretRedactor {

    // 1. PEM Private Keys
    private static final Pattern PEM_PRIVATE_KEY = Pattern.compile(
            "-----BEGIN [A-Z0-9 ]*PRIVATE KEY-----[\\s\\S]*?-----END [A-Z0-9 ]*PRIVATE KEY-----"
    );

    // 2. Database Connection URI Passwords
    private static final Pattern DB_URI_PASSWORD = Pattern.compile(
            "(?i)((?:postgres|postgresql|mysql|mariadb|mongodb(?:\\+srv)?|redis)://[^:/@\\s]+:)(?!\\Q[REDACTED\\E)([^@\\s]+)(@)"
    );

    // 3. Bearer & Authorization Tokens
    private static final Pattern BEARER_TOKEN = Pattern.compile(
            "(?i)(Bearer\\s+)(?!\\Q[REDACTED\\E)[A-Za-z0-9._~+/=-]{12,}"
    );

    // 4. GitHub Tokens (PATs & Fine-grained tokens)
    private static final Pattern GITHUB_TOKEN = Pattern.compile(
            "\\b(gh[pousr]_[A-Za-z0-9_]{20,}|github_pat_[A-Za-z0-9_]{20,})\\b"
    );

    // 5. AWS Access Key IDs
    private static final Pattern AWS_KEY = Pattern.compile(
            "\\b((?:AKIA|ASIA)[0-9A-Z]{14,18})\\b"
    );

    // 6. AI & Cloud API Keys (OpenAI, Anthropic, Google, Slack)
    private static final Pattern OPENAI_ANTHROPIC_KEY = Pattern.compile(
            "\\b(sk-[A-Za-z0-9_-]{16,})\\b"
    );
    private static final Pattern GOOGLE_API_KEY = Pattern.compile(
            "\\b(AIza[0-9A-Za-z_-]{30,40})\\b"
    );
    private static final Pattern SLACK_TOKEN = Pattern.compile(
            "\\b(xox[baprs]-[0-9A-Za-z-]{10,})\\b"
    );

    // 7. Key-Value Secret Assignments in JSON, YAML, .env & Shell
    private static final Pattern JSON_SECRET_ASSIGNMENT = Pattern.compile(
            "(?i)(\"(?:[a-zA-Z0-9_]*_)?(?:password|passwd|pwd|secret|client_secret|api_key|apikey|access_token|auth_token|private_key|secret_key)\"\\s*:\\s*\")(?!\\Q[REDACTED\\E)([^\"\\r\\n]+)(\")"
    );
    private static final Pattern ENV_SECRET_ASSIGNMENT = Pattern.compile(
            "(?i)(\\b(?:[a-zA-Z0-9_]*_)?(?:password|passwd|pwd|secret|client_secret|api_key|apikey|access_token|auth_token|private_key|secret_key)\\s*=\\s*)(['\"]?)(?!\\Q[REDACTED\\E)([^'\"\\r\\n\\s]+)\\2"
    );
    private static final Pattern YAML_SECRET_ASSIGNMENT = Pattern.compile(
            "(?i)(\\b(?:[a-zA-Z0-9_]*_)?(?:password|passwd|pwd|secret|client_secret|api_key|apikey|access_token|auth_token|private_key|secret_key)\\s*:\\s*)(['\"]?)(?!\\Q[REDACTED\\E)([^'\"\\r\\n\\s]+)\\2"
    );

    private static final Set<String> SENSITIVE_KEY_NAMES = Set.of(
            "password", "passwd", "pwd", "secret", "client_secret",
            "api_key", "apikey", "access_token", "auth_token",
            "private_key", "secret_key"
    );

    private SecretRedactor() {}

    /**
     * Redacts known secrets from the input string, returning the masked string.
     */
    public static String redact(String input) {
        if (input == null || input.isEmpty()) {
            return input;
        }
        return redactDetailed(input).redactedText();
    }

    /**
     * Redacts known secrets from the input string with detailed diagnostic metrics.
     */
    public static RedactionResult redactDetailed(String input) {
        if (input == null || input.isEmpty()) {
            return RedactionResult.unchanged(input);
        }

        String current = input;
        int totalRedactions = 0;
        List<String> types = new ArrayList<>();

        // 1. PEM Private Keys
        PatternResult pemRes = applyReplacement(current, PEM_PRIVATE_KEY, "[REDACTED:PRIVATE_KEY]");
        if (pemRes.count() > 0) {
            totalRedactions += pemRes.count();
            types.add("PRIVATE_KEY");
            current = pemRes.text();
        }

        // 2. Database URI Passwords
        PatternResult dbRes = applyReplacement(current, DB_URI_PASSWORD, "$1[REDACTED:DB_PASSWORD]$3");
        if (dbRes.count() > 0) {
            totalRedactions += dbRes.count();
            types.add("DB_PASSWORD");
            current = dbRes.text();
        }

        // 3. Bearer & Authorization Tokens
        PatternResult bearerRes = applyReplacement(current, BEARER_TOKEN, "$1[REDACTED:BEARER_TOKEN]");
        if (bearerRes.count() > 0) {
            totalRedactions += bearerRes.count();
            types.add("BEARER_TOKEN");
            current = bearerRes.text();
        }

        // 4. GitHub Tokens
        PatternResult ghRes = applyReplacement(current, GITHUB_TOKEN, "[REDACTED:GITHUB_TOKEN]");
        if (ghRes.count() > 0) {
            totalRedactions += ghRes.count();
            types.add("GITHUB_TOKEN");
            current = ghRes.text();
        }

        // 5. AWS Access Key IDs
        PatternResult awsRes = applyReplacement(current, AWS_KEY, "[REDACTED:AWS_KEY]");
        if (awsRes.count() > 0) {
            totalRedactions += awsRes.count();
            types.add("AWS_KEY");
            current = awsRes.text();
        }

        // 6. AI & Cloud API Keys
        int apiKeyCount = 0;
        PatternResult aiRes = applyReplacement(current, OPENAI_ANTHROPIC_KEY, "[REDACTED:API_KEY]");
        if (aiRes.count() > 0) {
            apiKeyCount += aiRes.count();
            current = aiRes.text();
        }
        PatternResult googleRes = applyReplacement(current, GOOGLE_API_KEY, "[REDACTED:API_KEY]");
        if (googleRes.count() > 0) {
            apiKeyCount += googleRes.count();
            current = googleRes.text();
        }
        PatternResult slackRes = applyReplacement(current, SLACK_TOKEN, "[REDACTED:API_KEY]");
        if (slackRes.count() > 0) {
            apiKeyCount += slackRes.count();
            current = slackRes.text();
        }
        if (apiKeyCount > 0) {
            totalRedactions += apiKeyCount;
            types.add("API_KEY");
        }

        // 7. Key-Value Secret Assignments
        int secretAssignmentCount = 0;
        PatternResult jsonRes = applyReplacement(current, JSON_SECRET_ASSIGNMENT, "$1[REDACTED:SECRET]$3");
        if (jsonRes.count() > 0) {
            secretAssignmentCount += jsonRes.count();
            current = jsonRes.text();
        }
        PatternResult envRes = applyReplacement(current, ENV_SECRET_ASSIGNMENT, "$1$2[REDACTED:SECRET]$2");
        if (envRes.count() > 0) {
            secretAssignmentCount += envRes.count();
            current = envRes.text();
        }
        PatternResult yamlRes = applyReplacement(current, YAML_SECRET_ASSIGNMENT, "$1$2[REDACTED:SECRET]$2");
        if (yamlRes.count() > 0) {
            secretAssignmentCount += yamlRes.count();
            current = yamlRes.text();
        }
        if (secretAssignmentCount > 0) {
            totalRedactions += secretAssignmentCount;
            types.add("SECRET");
        }

        if (totalRedactions == 0) {
            return RedactionResult.unchanged(input);
        }

        return new RedactionResult(current, true, totalRedactions, Collections.unmodifiableList(types));
    }

    /**
     * Recursively traverses and redacts sensitive entries in nested Maps and Collections.
     */
    public static Map<String, Object> redactMap(Map<String, Object> input) {
        if (input == null || input.isEmpty()) {
            return input;
        }

        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : input.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            copy.put(key, redactObject(key, value));
        }
        return Collections.unmodifiableMap(copy);
    }

    @SuppressWarnings("unchecked")
    private static Object redactObject(String key, Object value) {
        if (value == null) {
            return null;
        }

        if (value instanceof String str) {
            if (str.startsWith("[REDACTED:")) {
                return str;
            }
            RedactionResult res = redactDetailed(str);
            if (res.modified()) {
                return res.redactedText();
            }
            if (isSensitiveKey(key)) {
                return "[REDACTED:SECRET]";
            }
            return str;
        }

        if (value instanceof Map<?, ?> map) {
            return redactMap((Map<String, Object>) map);
        }
        if (value instanceof List<?> list) {
            return redactList(list);
        }

        if (isSensitiveKey(key)) {
            return "[REDACTED:SECRET]";
        }

        return value;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> redactList(List<?> list) {
        if (list == null || list.isEmpty()) {
            return (List<Object>) list;
        }

        List<Object> copy = new ArrayList<>(list.size());
        for (Object item : list) {
            if (item instanceof String str) {
                copy.add(redact(str));
            } else if (item instanceof Map<?, ?> map) {
                copy.add(redactMap((Map<String, Object>) map));
            } else if (item instanceof List<?> nestedList) {
                copy.add(redactList(nestedList));
            } else {
                copy.add(item);
            }
        }
        return Collections.unmodifiableList(copy);
    }

    public static boolean isSensitiveKey(String key) {
        if (key == null || key.isBlank()) {
            return false;
        }
        String lower = key.toLowerCase().replace("-", "_").trim();
        if (SENSITIVE_KEY_NAMES.contains(lower)) {
            return true;
        }
        for (String sensitive : SENSITIVE_KEY_NAMES) {
            if (lower.endsWith("_" + sensitive)) {
                return true;
            }
        }
        return false;
    }

    private static PatternResult applyReplacement(String input, Pattern pattern, String replacement) {
        Matcher m = pattern.matcher(input);
        if (!m.find()) {
            return new PatternResult(input, 0);
        }

        StringBuilder sb = new StringBuilder();
        int count = 0;
        do {
            count++;
            m.appendReplacement(sb, replacement);
        } while (m.find());
        m.appendTail(sb);

        return new PatternResult(sb.toString(), count);
    }

    private record PatternResult(String text, int count) {}
}
