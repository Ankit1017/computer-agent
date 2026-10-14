package com.agent.orchestration.workflow.engine;

import com.agent.orchestration.workflow.state.WorkflowState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Expression evaluation engine for state-driven dynamic routing in workflow DAGs,
 * inspired by LangGraph conditional edges.
 *
 * <p>Safely evaluates condition strings against the active {@link WorkflowState} without
 * external script engines or reflection vulnerabilities.
 *
 * <p>Supported syntax:
 * <ul>
 *   <li>Method invocations: {@code state.getValue("status").equals("error")}, {@code status.equalsIgnoreCase("approved")}</li>
 *   <li>Binary comparisons: {@code status == 'APPROVED'}, {@code retryCount < 3}, {@code hasErrors == true}</li>
 *   <li>Null checks: {@code result == null}, {@code result != null}</li>
 *   <li>Logical combinators: {@code cond1 && cond2}, {@code cond1 || cond2}</li>
 *   <li>Unary flags: {@code hasErrors}, {@code !hasErrors}</li>
 * </ul>
 */
public class ConditionEvaluator {

    private static final Logger log = LoggerFactory.getLogger(ConditionEvaluator.class);

    // Matches: state.getValue("key").equals("val") or key.equalsIgnoreCase('val')
    private static final Pattern METHOD_PATTERN = Pattern.compile(
            "^(?:state\\.(?:getValue|get)\\([\"']?([a-zA-Z0-9_.-]+)[\"']?\\)|([a-zA-Z0-9_.-]+))\\.(equals|equalsIgnoreCase|contains)\\([\"']?([^\"')]+)[\"']?\\)$",
            Pattern.CASE_INSENSITIVE
    );

    // Matches: key == 'value', key != 10, key >= 5.0
    private static final Pattern OPERATOR_PATTERN = Pattern.compile(
            "^(?:state\\.(?:getValue|get)\\([\"']?([a-zA-Z0-9_.-]+)[\"']?\\)|([a-zA-Z0-9_.-]+))\\s*(==|!=|>=|<=|>|<)\\s*(.+)$"
    );

    /**
     * Evaluates a condition string against the provided WorkflowState.
     *
     * @param condition expression to evaluate
     * @param state     active workflow state
     * @return true if the condition evaluates to true; false otherwise
     */
    public boolean evaluate(String condition, WorkflowState state) {
        if (condition == null || condition.isBlank()) {
            return false;
        }
        Map<String, Object> data = (state != null) ? state.getData() : Collections.emptyMap();
        return evaluate(condition, data);
    }

    /**
     * Evaluates a condition string against raw state data.
     *
     * @param condition expression to evaluate
     * @param data      key-value payload
     * @return true if the condition evaluates to true; false otherwise
     */
    public boolean evaluate(String condition, Map<String, Object> data) {
        if (condition == null || condition.isBlank()) {
            return false;
        }
        String trimmed = condition.trim();

        // 1. Logical OR (||)
        if (trimmed.contains("||")) {
            String[] clauses = trimmed.split("\\|\\|");
            for (String clause : clauses) {
                if (evaluateSingleClause(clause.trim(), data)) {
                    return true;
                }
            }
            return false;
        }

        // 2. Logical AND (&&)
        if (trimmed.contains("&&")) {
            String[] clauses = trimmed.split("&&");
            for (String clause : clauses) {
                if (!evaluateSingleClause(clause.trim(), data)) {
                    return false;
                }
            }
            return true;
        }

        return evaluateSingleClause(trimmed, data);
    }

    private boolean evaluateSingleClause(String clause, Map<String, Object> data) {
        if (clause.isBlank()) {
            return false;
        }

        // 1. Check Method Call Pattern: state.getValue("status").equals("error")
        Matcher methodMatcher = METHOD_PATTERN.matcher(clause);
        if (methodMatcher.matches()) {
            String key = methodMatcher.group(1) != null ? methodMatcher.group(1) : methodMatcher.group(2);
            String method = methodMatcher.group(3);
            String expected = methodMatcher.group(4);
            Object actual = data != null ? data.get(key) : null;

            if (actual == null) {
                return false;
            }

            String actualStr = actual.toString();
            return switch (method.toLowerCase()) {
                case "equals" -> actualStr.equals(expected);
                case "equalsignorecase" -> actualStr.equalsIgnoreCase(expected);
                case "contains" -> actualStr.contains(expected);
                default -> false;
            };
        }

        // 2. Check Operator Pattern: status == 'APPROVED'
        Matcher opMatcher = OPERATOR_PATTERN.matcher(clause);
        if (opMatcher.matches()) {
            String key = opMatcher.group(1) != null ? opMatcher.group(1) : opMatcher.group(2);
            String operator = opMatcher.group(3);
            String rightExpr = opMatcher.group(4).trim();
            Object actual = data != null ? data.get(key) : null;

            return evaluateOperator(actual, operator, rightExpr);
        }

        // 3. Unary check: !hasErrors or hasErrors
        if (clause.startsWith("!")) {
            String key = clause.substring(1).trim();
            Object val = data != null ? data.get(key) : null;
            return !isTruthy(val);
        }

        Object val = data != null ? data.get(clause) : null;
        return isTruthy(val);
    }

    private boolean evaluateOperator(Object actual, String operator, String rightExpr) {
        // Strip outer quotes if string literal: 'value' or "value"
        String rawRight = unquote(rightExpr);

        // Null comparisons
        if ("null".equalsIgnoreCase(rawRight)) {
            return switch (operator) {
                case "==" -> actual == null;
                case "!=" -> actual != null;
                default -> false;
            };
        }

        if (actual == null) {
            return "!=".equals(operator);
        }

        // Boolean comparisons
        if ("true".equalsIgnoreCase(rawRight) || "false".equalsIgnoreCase(rawRight)) {
            boolean expectedBool = Boolean.parseBoolean(rawRight);
            boolean actualBool = isTruthy(actual);
            return switch (operator) {
                case "==" -> actualBool == expectedBool;
                case "!=" -> actualBool != expectedBool;
                default -> false;
            };
        }

        // Numeric comparisons
        if (isNumeric(rawRight) && isNumeric(actual.toString())) {
            double actualNum = Double.parseDouble(actual.toString());
            double expectedNum = Double.parseDouble(rawRight);
            return switch (operator) {
                case "==" -> actualNum == expectedNum;
                case "!=" -> actualNum != expectedNum;
                case ">"  -> actualNum > expectedNum;
                case ">=" -> actualNum >= expectedNum;
                case "<"  -> actualNum < expectedNum;
                case "<=" -> actualNum <= expectedNum;
                default -> false;
            };
        }

        // String comparisons
        String actualStr = actual.toString();
        return switch (operator) {
            case "==" -> actualStr.equals(rawRight);
            case "!=" -> !actualStr.equals(rawRight);
            default -> false;
        };
    }

    private static String unquote(String str) {
        if (str == null) return "";
        String s = str.trim();
        if ((s.startsWith("'") && s.endsWith("'")) || (s.startsWith("\"") && s.endsWith("\""))) {
            if (s.length() >= 2) {
                return s.substring(1, s.length() - 1);
            }
        }
        return s;
    }

    private static boolean isNumeric(String str) {
        if (str == null || str.isBlank()) return false;
        try {
            Double.parseDouble(str.trim());
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static boolean isTruthy(Object val) {
        if (val == null) {
            return false;
        }
        if (val instanceof Boolean b) {
            return b;
        }
        if (val instanceof Number n) {
            return n.doubleValue() != 0.0;
        }
        String s = val.toString().trim();
        return !s.isBlank() && !"false".equalsIgnoreCase(s) && !"0".equals(s);
    }
}
