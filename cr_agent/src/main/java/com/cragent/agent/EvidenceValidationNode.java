package com.cragent.agent;

import com.cragent.model.ReviewIssue;
import com.cragent.model.ReviewResult;
import com.cragent.model.Severity;
import com.cragent.model.Phase;
import com.cragent.trace.TraceRecorder;
import com.cragent.util.Jsons;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

public final class EvidenceValidationNode {
    private EvidenceValidationNode() {
    }

    public static ReviewResult validateDiff(ReviewResult input, Map<String, Object> triage, Map<String, Object> analysis, TraceRecorder trace) {
        trace.record("strategy_start", Map.of("strategy", "Evidence Validation"));
        Map<String, Set<Integer>> changedLines = changedLinesByFile(triage.get("changed_files"));
        Set<String> changedFiles = changedLines.keySet();
        List<ReviewIssue> clean = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int originalCount = input.issues.size();
        for (ReviewIssue issue : input.issues) {
            if (issue == null || issue.file == null || issue.file.isBlank() || issue.body == null || issue.body.isBlank()) {
                continue;
            }
            if (!changedFiles.isEmpty() && !changedFiles.contains(issue.file)) {
                trace.record("issue_filtered", Map.of("reason", "file_not_changed", "file", issue.file, "body", issue.body));
                continue;
            }
            if (issue.confidence < 0.45) {
                trace.record("issue_filtered", Map.of("reason", "low_confidence", "file", issue.file, "confidence", issue.confidence));
                continue;
            }
            if (matchesFalsePositive(issue, analysis)) {
                trace.record("issue_filtered", Map.of("reason", "false_positive_memory", "file", issue.file, "body", issue.body));
                continue;
            }
            if (issue.line != null && !changedLineValid(changedLines.get(issue.file), issue.line)) {
                trace.record("issue_line_cleared", Map.of("reason", "line_not_in_diff", "file", issue.file, "line", issue.line));
                issue.line = null;
            }
            issue.category = normalizeCategory(issue.category);
            issue.severity = calibratedSeverity(issue, analysis);
            if (issue.evidence == null || issue.evidence.isBlank()) {
                issue.evidence = inferEvidence(issue, triage);
            }
            String key = issueKey(issue);
            if (seen.add(key)) {
                clean.add(issue);
            }
        }
        input.issues = clean;
        input.shouldComment = input.shouldComment && !clean.isEmpty();
        trace.record("strategy_end", Map.of(
                "strategy", "Evidence Validation",
                "input_issues", originalCount,
                "output_issues", clean.size(),
                "should_comment", input.shouldComment
        ));
        return input;
    }

    @SuppressWarnings("unchecked")
    public static List<ReviewIssue> validateRepo(List<ReviewIssue> input, RepoAuditIndexer.AuditIndex index, List<Map<String, Object>> checks,
                                                 Map<String, Object> lspContext, Map<String, Object> analysis, TraceRecorder trace) {
        trace.record("phase_start", Map.of("phase", Phase.EVIDENCE_VALIDATION.name(), "mode", "repo_audit"));
        Map<String, RepoAuditIndexer.AuditFile> files = index.files().stream().collect(Collectors.toMap(RepoAuditIndexer.AuditFile::path, f -> f, (a, b) -> a));
        List<Map<String, Object>> symbols = lspContext.get("symbols_preview") instanceof List<?> list
                ? list.stream().filter(Map.class::isInstance).map(item -> (Map<String, Object>) item).toList()
                : List.of();
        List<ReviewIssue> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        String checkText = Jsons.stringify(checks);
        for (ReviewIssue issue : input) {
            if (issue.file == null || !files.containsKey(issue.file) || issue.confidence < 0.45) {
                continue;
            }
            if (matchesFalsePositive(issue, analysis)) {
                trace.record("issue_filtered", Map.of("reason", "false_positive_memory", "file", issue.file, "body", issue.body));
                continue;
            }
            RepoAuditIndexer.AuditFile file = files.get(issue.file);
            if (issue.line != null && (issue.line < 1 || issue.line > Math.max(1, file.lines()))) {
                issue.line = null;
            }
            String evidence = issue.evidence == null ? "" : issue.evidence.strip();
            if (!evidence.isBlank() && !file.content().contains(evidence) && !checkText.contains(evidence)) {
                issue.confidence = Math.min(issue.confidence, 0.6);
            }
            boolean lineInsideKnownSymbol = symbols.stream()
                    .filter(symbol -> issue.file.equals(String.valueOf(symbol.get("path"))))
                    .anyMatch(symbol -> issue.line != null
                            && intValue(symbol.get("start_line")) <= issue.line
                            && intValue(symbol.get("end_line")) >= issue.line);
            if (!symbols.isEmpty() && issue.line != null && !lineInsideKnownSymbol && issue.confidence > 0.8) {
                issue.confidence = 0.8;
            }
            String key = issueKey(issue);
            if (seen.add(key)) {
                out.add(issue);
            }
        }
        trace.record("phase_end", Map.of("phase", Phase.EVIDENCE_VALIDATION.name(), "before", input.size(), "after", out.size()));
        return out;
    }

    private static Severity calibratedSeverity(ReviewIssue issue, Map<String, Object> analysis) {
        String combined = (issue.category + "\n" + issue.body + "\n" + issue.evidence + "\n" + issue.impact).toLowerCase();
        if (issue.confidence < 0.6 && (issue.severity == Severity.critical || issue.severity == Severity.high)) {
            return Severity.medium;
        }
        if (containsAny(combined, "credential", "password", "secret", "token", "auth bypass", "authorization bypass", "sql injection", "xss", "path traversal", "ssrf")) {
            return issue.confidence >= 0.8 ? Severity.high : Severity.medium;
        }
        if ("tests".equals(issue.category)) {
            return issue.severity == Severity.critical || issue.severity == Severity.high ? Severity.medium : issue.severity;
        }
        if (issue.severity == Severity.critical && !containsAny(combined, "exploitable", "data loss", "outage", "secret leak", "auth bypass")) {
            return Severity.high;
        }
        return issue.severity;
    }

    private static String inferEvidence(ReviewIssue issue, Map<String, Object> triage) {
        for (Map<String, Object> file : listOfMaps(triage.get("changed_files"))) {
            if (Objects.equals(issue.file, String.valueOf(file.get("filename")))) {
                Object patch = file.get("patch");
                if (patch != null) {
                    String text = String.valueOf(patch).replaceAll("\\s+", " ").trim();
                    return text.length() > 240 ? text.substring(0, 240) : text;
                }
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static boolean matchesFalsePositive(ReviewIssue issue, Map<String, Object> analysis) {
        Object memory = analysis.get("memory");
        if (!(memory instanceof Map<?, ?> memoryMap)) {
            return false;
        }
        Object rules = memoryMap.get("rules");
        if (!(rules instanceof List<?> list)) {
            return false;
        }
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> raw)) {
                continue;
            }
            Map<String, Object> rule = (Map<String, Object>) raw;
            if (!"false_positive".equals(String.valueOf(rule.get("type")))) {
                continue;
            }
            Map<String, Object> content = content(rule);
            List<String> filePatterns = stringList(content.getOrDefault("file_patterns", List.of()));
            if (filePatterns.stream().anyMatch(pattern -> globMatches(pattern, issue.file))) {
                String text = (issue.body + "\n" + issue.evidence + "\n" + issue.category).toLowerCase();
                String pattern = String.valueOf(content.getOrDefault("pattern", "")).toLowerCase();
                if (pattern.isBlank() || text.contains(firstToken(pattern)) || issue.file.toLowerCase().contains("test") || issue.file.toLowerCase().contains("migration")) {
                    return true;
                }
            }
        }
        return false;
    }

    private static Map<String, Object> content(Map<String, Object> record) {
        Object content = record.get("content");
        if (content instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                out.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            return out;
        }
        return Map.of();
    }

    private static Map<String, Set<Integer>> changedLinesByFile(Object changedFiles) {
        Map<String, Set<Integer>> result = new HashMap<>();
        for (Map<String, Object> file : listOfMaps(changedFiles)) {
            String filename = String.valueOf(file.get("filename"));
            String patch = String.valueOf(file.getOrDefault("patch", ""));
            result.put(filename, changedLinesFromPatch(patch));
        }
        return result;
    }

    private static Set<Integer> changedLinesFromPatch(String patch) {
        Set<Integer> lines = new HashSet<>();
        if (patch == null || patch.isBlank()) {
            return lines;
        }
        int currentNewLine = 0;
        boolean sawHunk = false;
        for (String line : patch.split("\\R")) {
            java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("@@ -\\d+(?:,\\d+)? \\+(\\d+)(?:,\\d+)? @@.*").matcher(line);
            if (matcher.matches()) {
                sawHunk = true;
                currentNewLine = Integer.parseInt(matcher.group(1));
                continue;
            }
            if (!sawHunk) {
                if (line.startsWith("+") && !line.startsWith("+++")) {
                    lines.add(1);
                }
                continue;
            }
            if (line.startsWith("+") && !line.startsWith("+++")) {
                lines.add(currentNewLine++);
            } else if (!line.startsWith("-")) {
                currentNewLine++;
            }
        }
        return lines;
    }

    private static boolean changedLineValid(Set<Integer> validLines, Integer line) {
        return line == null || (validLines != null && validLines.contains(line));
    }

    private static String normalizeCategory(String category) {
        String value = category == null ? "general" : category.toLowerCase();
        return switch (value) {
            case "security", "bug", "style", "performance", "maintainability", "tests", "general" -> value;
            case "logic", "correctness" -> "bug";
            case "test" -> "tests";
            default -> "general";
        };
    }

    private static String issueKey(ReviewIssue issue) {
        return String.join("|",
                nullToEmpty(issue.file),
                String.valueOf(issue.line == null ? "" : issue.line),
                nullToEmpty(issue.category),
                semanticPrefix(issue.body)
        ).toLowerCase();
    }

    private static String semanticPrefix(String text) {
        String normalized = nullToEmpty(text)
                .toLowerCase()
                .replaceAll("[^a-z0-9_./]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
        if (normalized.isBlank()) {
            return "";
        }
        String[] words = normalized.split(" ");
        int limit = Math.min(10, words.length);
        return String.join(" ", java.util.Arrays.copyOf(words, limit));
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static boolean containsAny(String text, String... needles) {
        String lower = text == null ? "" : text.toLowerCase();
        for (String needle : needles) {
            if (lower.contains(needle.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    private static boolean globMatches(String glob, String value) {
        String regex = glob.replace(".", "\\.").replace("*", ".*");
        return value.matches(regex) || value.contains(glob.replace("*", ""));
    }

    private static String firstToken(String value) {
        String[] parts = value.split("\\s+");
        return parts.length == 0 ? value : parts[0];
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> listOfMaps(Object value) {
        if (value instanceof List<?> list) {
            return list.stream().filter(Map.class::isInstance).map(item -> (Map<String, Object>) item).collect(Collectors.toList());
        }
        return List.of();
    }

    private static List<String> stringList(Object value) {
        if (value instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        return List.of();
    }

    private static int intValue(Object value) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Exception e) {
            return 0;
        }
    }
}
