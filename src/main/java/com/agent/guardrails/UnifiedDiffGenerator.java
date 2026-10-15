package com.agent.guardrails;

import java.util.*;

/**
 * Lightweight, zero-dependency Unified Hunk Diff Generator.
 * Compares two strings line-by-line and outputs clean unified diff hunks
 * (--- a/..., +++ b/..., @@ -start,count +start,count @@) with surrounding context lines
 * and a safety cap to protect LLM context windows.
 */
public class UnifiedDiffGenerator {

    public static final int DEFAULT_MAX_DIFF_LINES = 60;
    public static final int CONTEXT_LINES = 3;

    private UnifiedDiffGenerator() {}

    /**
     * Generates a unified diff string between oldContent and newContent.
     *
     * @param fileName   display name of the file (e.g. "Main.java")
     * @param oldContent original content before modification
     * @param newContent new content after modification
     * @return formatted unified diff block
     */
    public static String generateDiff(String fileName, String oldContent, String newContent) {
        return generateDiff(fileName, oldContent, newContent, DEFAULT_MAX_DIFF_LINES);
    }

    /**
     * Generates a unified diff string with a configurable maximum line cap.
     */
    public static String generateDiff(String fileName, String oldContent, String newContent, int maxDiffLines) {
        String resolvedName = (fileName != null && !fileName.isBlank()) ? fileName : "file";
        List<String> oldLines = splitLines(oldContent);
        List<String> newLines = splitLines(newContent);

        StringBuilder sb = new StringBuilder();
        sb.append("--- a/").append(resolvedName).append("\n");
        sb.append("+++ b/").append(resolvedName).append("\n");

        // Case 1: Identical contents
        if (oldLines.equals(newLines)) {
            sb.append("@@ -0,0 +0,0 @@\n");
            return sb.toString().trim();
        }

        // Case 2: New file creation (oldLines empty)
        if (oldLines.isEmpty()) {
            int count = newLines.size();
            sb.append("@@ -0,0 +1,").append(count).append(" @@\n");
            int linesWritten = 0;
            for (String line : newLines) {
                if (linesWritten >= maxDiffLines) {
                    sb.append("... [DIFF TRUNCATED AT ").append(maxDiffLines).append(" LINES] ...\n");
                    break;
                }
                sb.append("+").append(line).append("\n");
                linesWritten++;
            }
            return sb.toString().trim();
        }

        // Case 3: Complete file deletion (newLines empty)
        if (newLines.isEmpty()) {
            int count = oldLines.size();
            sb.append("@@ -1,").append(count).append(" +0,0 @@\n");
            int linesWritten = 0;
            for (String line : oldLines) {
                if (linesWritten >= maxDiffLines) {
                    sb.append("... [DIFF TRUNCATED AT ").append(maxDiffLines).append(" LINES] ...\n");
                    break;
                }
                sb.append("-").append(line).append("\n");
                linesWritten++;
            }
            return sb.toString().trim();
        }

        // Case 4: General modification: find common prefix and suffix
        int m = oldLines.size();
        int n = newLines.size();

        int prefix = 0;
        while (prefix < m && prefix < n && oldLines.get(prefix).equals(newLines.get(prefix))) {
            prefix++;
        }

        int suffix = 0;
        while (suffix < (m - prefix) && suffix < (n - prefix)
                && oldLines.get(m - 1 - suffix).equals(newLines.get(n - 1 - suffix))) {
            suffix++;
        }

        int middleOldSize = m - prefix - suffix;
        int middleNewSize = n - prefix - suffix;

        // Compute LCS on middle chunk
        int[][] dp = new int[middleOldSize + 1][middleNewSize + 1];
        for (int i = 0; i < middleOldSize; i++) {
            for (int j = 0; j < middleNewSize; j++) {
                if (oldLines.get(prefix + i).equals(newLines.get(prefix + j))) {
                    dp[i + 1][j + 1] = dp[i][j] + 1;
                } else {
                    dp[i + 1][j + 1] = Math.max(dp[i + 1][j], dp[i][j + 1]);
                }
            }
        }

        // Backtrack to build middle edit operations
        List<DiffLine> middleDiff = new ArrayList<>();
        int i = middleOldSize;
        int j = middleNewSize;
        while (i > 0 || j > 0) {
            if (i > 0 && j > 0 && oldLines.get(prefix + i - 1).equals(newLines.get(prefix + j - 1))) {
                middleDiff.add(new DiffLine(DiffType.EQUAL, oldLines.get(prefix + i - 1)));
                i--;
                j--;
            } else if (j > 0 && (i == 0 || dp[i][j - 1] >= dp[i - 1][j])) {
                middleDiff.add(new DiffLine(DiffType.INSERT, newLines.get(prefix + j - 1)));
                j--;
            } else if (i > 0) {
                middleDiff.add(new DiffLine(DiffType.DELETE, oldLines.get(prefix + i - 1)));
                i--;
            }
        }
        Collections.reverse(middleDiff);

        // Include surrounding context lines (up to CONTEXT_LINES)
        int contextBeforeCount = Math.min(CONTEXT_LINES, prefix);
        int contextAfterCount = Math.min(CONTEXT_LINES, suffix);

        int oldStart = (prefix - contextBeforeCount) + 1;
        int newStart = (prefix - contextBeforeCount) + 1;

        int oldCount = contextBeforeCount + middleOldSize + contextAfterCount;
        int newCount = contextBeforeCount + middleNewSize + contextAfterCount;

        sb.append("@@ -").append(oldStart).append(",").append(oldCount)
          .append(" +").append(newStart).append(",").append(newCount).append(" @@\n");

        int linesWritten = 0;

        // Context before
        for (int c = prefix - contextBeforeCount; c < prefix; c++) {
            if (linesWritten >= maxDiffLines) {
                sb.append("... [DIFF TRUNCATED AT ").append(maxDiffLines).append(" LINES] ...\n");
                return sb.toString().trim();
            }
            sb.append(" ").append(oldLines.get(c)).append("\n");
            linesWritten++;
        }

        // Middle edits
        for (DiffLine dl : middleDiff) {
            if (linesWritten >= maxDiffLines) {
                sb.append("... [DIFF TRUNCATED AT ").append(maxDiffLines).append(" LINES] ...\n");
                return sb.toString().trim();
            }
            char prefixChar = switch (dl.type) {
                case EQUAL -> ' ';
                case DELETE -> '-';
                case INSERT -> '+';
            };
            sb.append(prefixChar).append(dl.text).append("\n");
            linesWritten++;
        }

        // Context after
        for (int c = m - suffix; c < m - suffix + contextAfterCount; c++) {
            if (linesWritten >= maxDiffLines) {
                sb.append("... [DIFF TRUNCATED AT ").append(maxDiffLines).append(" LINES] ...\n");
                return sb.toString().trim();
            }
            sb.append(" ").append(oldLines.get(c)).append("\n");
            linesWritten++;
        }

        return sb.toString().trim();
    }

    private static List<String> splitLines(String text) {
        if (text == null || text.isEmpty()) {
            return Collections.emptyList();
        }
        String[] array = text.split("\r?\n", -1);
        if (array.length > 0 && array[array.length - 1].isEmpty() && (text.endsWith("\n") || text.endsWith("\r\n"))) {
            String[] trimmed = new String[array.length - 1];
            System.arraycopy(array, 0, trimmed, 0, array.length - 1);
            return Arrays.asList(trimmed);
        }
        return Arrays.asList(array);
    }

    private enum DiffType { EQUAL, DELETE, INSERT }
    private record DiffLine(DiffType type, String text) {}
}
