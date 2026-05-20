package com.cragent.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.cragent.model.ReviewIssue;
import com.cragent.model.ReviewResult;
import com.cragent.model.Severity;
import com.cragent.util.Jsons;

public final class ReviewResultParser {
    private ReviewResultParser() {
    }

    public static ReviewResult parse(String text) {
        if (text == null || text.isBlank()) {
            ReviewResult result = new ReviewResult();
            result.summary = "No review result returned.";
            result.shouldComment = false;
            return result;
        }
        String candidate = extractJson(text);
        try {
            JsonNode root = Jsons.parseNode(candidate);
            ReviewResult result = new ReviewResult();
            result.summary = root.path("summary").asText("No summary.");
            result.shouldComment = root.path("shouldComment").asBoolean(root.path("should_comment").asBoolean(true));
            result.shouldCreateFixPr = root.path("shouldCreateFixPr").asBoolean(root.path("should_create_fix_pr").asBoolean(false));
            result.shouldUpdateMemory = root.path("shouldUpdateMemory").asBoolean(root.path("should_update_memory").asBoolean(true));
            JsonNode issues = root.path("issues");
            if (issues.isArray()) {
                for (JsonNode item : issues) {
                    ReviewIssue issue = new ReviewIssue();
                    issue.severity = parseSeverity(item.path("severity").asText("medium"));
                    issue.category = item.path("category").asText("general");
                    issue.file = item.path("file").isMissingNode() || item.path("file").isNull() ? null : item.path("file").asText();
                    issue.line = item.path("line").isNumber() ? item.path("line").asInt() : null;
                    issue.body = item.path("body").asText(item.path("description").asText(""));
                    issue.evidence = item.path("evidence").isMissingNode() || item.path("evidence").isNull() ? null : item.path("evidence").asText();
                    issue.impact = item.path("impact").isMissingNode() || item.path("impact").isNull() ? null : item.path("impact").asText();
                    issue.suggestion = item.path("suggestion").isMissingNode() || item.path("suggestion").isNull() ? null : item.path("suggestion").asText();
                    issue.autoFixable = item.path("autoFixable").asBoolean(item.path("auto_fixable").asBoolean(false));
                    issue.fixCode = nullableNodeText(item, "fixCode");
                    if (issue.fixCode == null) {
                        issue.fixCode = nullableNodeText(item, "fix_code");
                    }
                    issue.confidence = item.path("confidence").asDouble(0.5);
                    issue.candidateScore = item.path("candidateScore").asDouble(item.path("candidate_score").asDouble(0.0));
                    issue.validationVerdict = item.path("validationVerdict").asText(item.path("validation_verdict").asText("UNVERIFIED"));
                    issue.validationReason = nullableNodeText(item, "validationReason");
                    if (issue.validationReason == null) {
                        issue.validationReason = nullableNodeText(item, "validation_reason");
                    }
                    issue.correctedLine = item.path("correctedLine").isNumber() ? item.path("correctedLine").asInt()
                            : (item.path("corrected_line").isNumber() ? item.path("corrected_line").asInt() : null);
                    result.issues.add(issue);
                }
            }
            return result;
        } catch (RuntimeException e) {
            ReviewResult recovered = parseMalformedJsonFallback(candidate);
            if (!recovered.issues.isEmpty() || !"No summary.".equals(recovered.summary)) {
                return recovered;
            }
            return parseMarkdownFallback(text, e.getMessage());
        }
    }

    private static String extractJson(String text) {
        int fence = text.indexOf("```");
        if (fence >= 0) {
            int start = text.indexOf('\n', fence);
            int end = text.lastIndexOf("```");
            if (start > 0 && end > start) {
                return text.substring(start + 1, end).trim();
            }
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1);
        }
        return text;
    }

    private static Severity parseSeverity(String value) {
        try {
            return Severity.valueOf(value.toLowerCase());
        } catch (IllegalArgumentException e) {
            return Severity.medium;
        }
    }

    private static ReviewResult parseMalformedJsonFallback(String text) {
        ReviewResult result = new ReviewResult();
        result.summary = stringField(text, "summary", "No summary.");
        result.shouldComment = booleanField(text, "shouldComment", booleanField(text, "should_comment", true));
        result.shouldCreateFixPr = booleanField(text, "shouldCreateFixPr", booleanField(text, "should_create_fix_pr", false));
        result.shouldUpdateMemory = booleanField(text, "shouldUpdateMemory", booleanField(text, "should_update_memory", true));
        String issues = arrayBody(text, "issues");
        if (issues == null || issues.isBlank()) {
            return result;
        }
        for (String raw : splitIssueObjects(issues)) {
            ReviewIssue issue = new ReviewIssue();
            issue.severity = parseSeverity(stringField(raw, "severity", "medium"));
            issue.category = stringField(raw, "category", "general");
            issue.file = nullableStringField(raw, "file");
            issue.line = integerField(raw, "line");
            issue.body = stringField(raw, "body", stringField(raw, "description", ""));
            issue.evidence = nullableStringField(raw, "evidence");
            issue.impact = nullableStringField(raw, "impact");
            issue.suggestion = nullableStringField(raw, "suggestion");
            issue.autoFixable = booleanField(raw, "autoFixable", booleanField(raw, "auto_fixable", false));
            issue.fixCode = nullableStringField(raw, "fixCode");
            if (issue.fixCode == null) {
                issue.fixCode = nullableStringField(raw, "fix_code");
            }
            issue.confidence = doubleField(raw, "confidence", 0.5);
            issue.candidateScore = doubleField(raw, "candidateScore", doubleField(raw, "candidate_score", 0.0));
            issue.validationVerdict = stringField(raw, "validationVerdict", stringField(raw, "validation_verdict", "UNVERIFIED"));
            issue.validationReason = nullableStringField(raw, "validationReason");
            if (issue.validationReason == null) {
                issue.validationReason = nullableStringField(raw, "validation_reason");
            }
            issue.correctedLine = integerField(raw, "correctedLine");
            if (issue.correctedLine == null) {
                issue.correctedLine = integerField(raw, "corrected_line");
            }
            if (issue.file != null && !issue.body.isBlank()) {
                result.issues.add(issue);
            }
        }
        return result;
    }

    private static String arrayBody(String text, String key) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\"" + key + "\"\\s*:\\s*\\[").matcher(text);
        if (!matcher.find()) {
            return null;
        }
        int start = matcher.end();
        int end = text.indexOf("\n  ],", start);
        if (end < 0) {
            end = text.indexOf("\n],", start);
        }
        if (end < 0) {
            end = text.lastIndexOf(']');
        }
        return end > start ? text.substring(start, end) : null;
    }

    private static java.util.List<String> splitIssueObjects(String issues) {
        java.util.List<String> out = new java.util.ArrayList<>();
        int depth = 0;
        int start = -1;
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < issues.length(); i++) {
            char c = issues.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (c == '\\' && inString) {
                escaped = true;
                continue;
            }
            if (c == '"') {
                inString = !inString;
                continue;
            }
            if (inString) {
                continue;
            }
            if (c == '{') {
                if (depth == 0) {
                    start = i;
                }
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0 && start >= 0) {
                    out.add(issues.substring(start, i + 1).trim());
                    start = -1;
                }
            }
        }
        return out;
    }

    private static String nullableStringField(String text, String key) {
        String value = stringField(text, key, null);
        return value == null || "null".equals(value) ? null : value;
    }

    private static String stringField(String text, String key, String fallback) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\"" + key + "\"\\s*:\\s*").matcher(text);
        if (!matcher.find()) {
            return fallback;
        }
        int pos = matcher.end();
        while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) {
            pos++;
        }
        if (pos >= text.length()) {
            return fallback;
        }
        if (text.startsWith("null", pos)) {
            return null;
        }
        if (text.charAt(pos) != '"') {
            int end = scalarEnd(text, pos);
            return cleanScalar(text.substring(pos, end));
        }
        int start = pos + 1;
        java.util.regex.Matcher nextField = java.util.regex.Pattern
                .compile("\"\\s*,\\s*(?:\\R\\s*)?\"(?:summary|severity|category|file|line|body|description|evidence|impact|suggestion|autoFixable|auto_fixable|fixCode|fix_code|confidence|candidateScore|candidate_score|validationVerdict|validation_verdict|validationReason|validation_reason|correctedLine|corrected_line|shouldComment|should_comment|shouldCreateFixPr|should_create_fix_pr|shouldUpdateMemory|should_update_memory|issues)\"\\s*:")
                .matcher(text);
        if (nextField.find(start)) {
            return unescape(text.substring(start, nextField.start()));
        }
        int end = text.lastIndexOf('"');
        if (end > start) {
            return unescape(text.substring(start, end));
        }
        return fallback;
    }

    private static Integer integerField(String text, String key) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\"" + key + "\"\\s*:\\s*(null|-?\\d+)").matcher(text);
        if (!matcher.find() || "null".equals(matcher.group(1))) {
            return null;
        }
        return Integer.parseInt(matcher.group(1));
    }

    private static String nullableNodeText(JsonNode node, String key) {
        JsonNode value = node.path(key);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }

    private static double doubleField(String text, String key, double fallback) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\"" + key + "\"\\s*:\\s*([01](?:\\.\\d+)?)").matcher(text);
        return matcher.find() ? Double.parseDouble(matcher.group(1)) : fallback;
    }

    private static boolean booleanField(String text, String key, boolean fallback) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\"" + key + "\"\\s*:\\s*(true|false)").matcher(text);
        return matcher.find() ? Boolean.parseBoolean(matcher.group(1)) : fallback;
    }

    private static int scalarEnd(String text, int start) {
        int comma = text.indexOf(',', start);
        int brace = text.indexOf('}', start);
        if (comma < 0) {
            return brace < 0 ? text.length() : brace;
        }
        if (brace < 0) {
            return comma;
        }
        return Math.min(comma, brace);
    }

    private static String cleanScalar(String value) {
        return value == null ? null : value.trim().replaceAll("^\"|\"$", "");
    }

    private static String unescape(String value) {
        return value
                .replace("\\n", "\n")
                .replace("\\\"", "\"")
                .replace("\\\\", "\\")
                .trim();
    }

    private static ReviewResult parseMarkdownFallback(String text, String error) {
        ReviewResult result = new ReviewResult();
        String lower = text.toLowerCase();
        if (lower.contains("request_changes")) {
            result.summary = "REQUEST_CHANGES: " + firstSentence(text);
        } else {
            result.summary = firstSentence(text);
        }
        if (lower.contains("security") || lower.contains("password") || lower.contains("漏洞")) {
            ReviewIssue issue = new ReviewIssue();
            issue.severity = lower.contains("critical") ? Severity.critical : (lower.contains("high") ? Severity.high : Severity.medium);
            issue.category = "security";
            issue.file = extractBetween(text, "**File:** `", "`");
            if (issue.file == null) {
                issue.file = extractBetween(text, "File:** `", "`");
            }
            issue.line = extractLine(text);
            issue.body = "Potential security issue found by model. Original structured JSON parsing failed: " + error;
            issue.suggestion = extractSuggestion(text);
            issue.confidence = extractConfidence(text);
            result.issues.add(issue);
            result.shouldComment = true;
        } else {
            result.shouldComment = false;
            result.summary = "Unable to parse structured review result: " + error;
        }
        return result;
    }

    private static String firstSentence(String text) {
        String cleaned = text.replaceAll("(?s)```.*?```", "").replaceAll("[#*_`|\\-]+", " ").replaceAll("\\s+", " ").trim();
        return cleaned.length() > 240 ? cleaned.substring(0, 240) : cleaned;
    }

    private static String extractBetween(String text, String prefix, String suffix) {
        int start = text.indexOf(prefix);
        if (start < 0) {
            return null;
        }
        start += prefix.length();
        int end = text.indexOf(suffix, start);
        if (end < 0) {
            return null;
        }
        return text.substring(start, end);
    }

    private static Integer extractLine(String text) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(?i)line[:*\\s]+(\\d+)").matcher(text);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : null;
    }

    private static String extractSuggestion(String text) {
        int idx = text.toLowerCase().indexOf("suggestion");
        if (idx < 0) {
            return null;
        }
        String tail = text.substring(idx).replaceAll("[#*_`|\\-]+", " ").replaceAll("\\s+", " ").trim();
        return tail.length() > 300 ? tail.substring(0, 300) : tail;
    }

    private static double extractConfidence(String text) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(?i)confidence[^0-9]*([01](?:\\.\\d+)?)").matcher(text);
        return matcher.find() ? Double.parseDouble(matcher.group(1)) : 0.5;
    }
}
