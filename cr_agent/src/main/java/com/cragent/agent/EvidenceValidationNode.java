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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public final class EvidenceValidationNode {
    private EvidenceValidationNode() {
    }

    public static ReviewResult validateDiff(ReviewResult input, Map<String, Object> triage, Map<String, Object> analysis, TraceRecorder trace) {
        trace.record("strategy_start", Map.of("strategy", "Evidence Validation"));
        Map<String, DiffEvidence> diffEvidence = diffEvidenceByFile(triage.get("changed_files"));
        Set<String> changedFiles = diffEvidence.keySet();
        List<ReviewIssue> clean = new ArrayList<>();
        List<ReviewIssue> memoryFiltered = new ArrayList<>();
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
            issue.category = normalizeCategory(issue.category);
            DiffEvidence evidence = diffEvidence.get(issue.file);
            EvidenceScore score = scoreDiffEvidence(issue, evidence, analysis);
            if (issue.line != null && !score.lineValid()) {
                Integer inferredLine = inferLineFromEvidence(issue, evidence);
                if (inferredLine != null) {
                    trace.record("issue_line_repaired", Map.of("file", issue.file, "from", issue.line, "to", inferredLine));
                    issue.line = inferredLine;
                    score = scoreDiffEvidence(issue, evidence, analysis);
                } else {
                    trace.record("issue_line_cleared", Map.of("reason", "line_not_in_diff", "file", issue.file, "line", issue.line));
                    issue.line = null;
                    score = scoreDiffEvidence(issue, evidence, analysis);
                }
            } else if (issue.line == null) {
                Integer inferredLine = inferLineFromEvidence(issue, evidence);
                if (inferredLine != null) {
                    issue.line = inferredLine;
                    score = scoreDiffEvidence(issue, evidence, analysis);
                }
            }
            issue.candidateScore = candidateScore(issue, score, analysis);
            issue.confidence = calibratedConfidence(issue.confidence, score);
            if (isLowValueStyleIssue(issue)) {
                trace.record("issue_filtered", Map.of("reason", "low_confidence", "file", issue.file, "confidence", issue.confidence));
                continue;
            }
            if (matchesFalsePositive(issue, analysis)) {
                trace.record("issue_filtered", Map.of("reason", "false_positive_memory", "file", issue.file, "body", issue.body));
                memoryFiltered.add(issue);
                continue;
            }
            issue.severity = calibratedSeverity(issue, analysis);
            if (issue.evidence == null || issue.evidence.isBlank()) {
                issue.evidence = inferEvidence(issue, triage, evidence);
            }
            String key = issueKey(issue);
            if (seen.add(key)) {
                clean.add(issue);
            } else {
                trace.record("issue_filtered", Map.of("reason", "duplicate", "file", issue.file, "body", issue.body));
            }
        }
        if (clean.isEmpty() && !memoryFiltered.isEmpty()) {
            memoryFiltered.stream()
                    .filter(EvidenceValidationNode::isRecoverableMemoryFilteredIssue)
                    .sorted((a, b) -> Double.compare(b.candidateScore, a.candidateScore))
                    .limit(2)
                    .forEach(issue -> {
                        issue.validationVerdict = "DEMOTE";
                        issue.validationReason = "False-positive memory matched, but the issue was retained because filtering would remove every candidate in this review.";
                        issue.candidateScore = Math.max(issue.candidateScore * 0.5, isHighSignalCategory(issue) ? 0.28 : 0.2);
                        clean.add(issue);
                        trace.record("issue_memory_soft_restored", Map.of("file", issue.file, "body", issue.body, "candidate_score", issue.candidateScore));
                    });
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
        List<ReviewIssue> memoryFiltered = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        String checkText = Jsons.stringify(checks);
        for (ReviewIssue issue : input) {
            if (issue.file == null || !files.containsKey(issue.file)) {
                continue;
            }
            if (matchesFalsePositive(issue, analysis)) {
                trace.record("issue_filtered", Map.of("reason", "false_positive_memory", "file", issue.file, "body", issue.body));
                memoryFiltered.add(issue);
                continue;
            }
            issue.category = normalizeCategory(issue.category);
            RepoAuditIndexer.AuditFile file = files.get(issue.file);
            if (issue.line != null && (issue.line < 1 || issue.line > Math.max(1, file.lines()))) {
                issue.line = null;
            }
            String evidence = issue.evidence == null ? "" : issue.evidence.strip();
            boolean evidenceInSource = !evidence.isBlank() && containsNormalized(file.content(), evidence);
            boolean evidenceInChecks = !evidence.isBlank() && containsNormalized(checkText, evidence);
            boolean lspSupported = lspMentions(lspContext, issue.file, issue.line);
            if (!evidence.isBlank() && !evidenceInSource && !evidenceInChecks) {
                issue.confidence = Math.min(issue.confidence, 0.6);
            }
            issue.candidateScore = repoCandidateScore(issue, evidenceInSource, evidenceInChecks, lspSupported);
            if (isLowValueStyleIssue(issue)) {
                trace.record("issue_filtered", Map.of("reason", "low_confidence", "file", issue.file, "confidence", issue.confidence));
                continue;
            }
            boolean lineInsideKnownSymbol = symbols.stream()
                    .filter(symbol -> issue.file.equals(String.valueOf(symbol.get("path"))))
                    .anyMatch(symbol -> issue.line != null
                            && intValue(symbol.get("start_line")) <= issue.line
                            && intValue(symbol.get("end_line")) >= issue.line);
            if (!symbols.isEmpty() && issue.line != null && !lineInsideKnownSymbol && issue.confidence > 0.8) {
                issue.confidence = 0.8;
            }
            issue.severity = calibratedSeverity(issue, analysis);
            String key = issueKey(issue);
            if (seen.add(key)) {
                out.add(issue);
            }
        }
        if (out.isEmpty() && !memoryFiltered.isEmpty()) {
            memoryFiltered.stream()
                    .filter(EvidenceValidationNode::isRecoverableMemoryFilteredIssue)
                    .sorted((a, b) -> Double.compare(b.candidateScore, a.candidateScore))
                    .limit(2)
                    .forEach(issue -> {
                        issue.validationVerdict = "DEMOTE";
                        issue.validationReason = "False-positive memory matched, but the issue was retained because filtering would remove every candidate in this review.";
                        issue.candidateScore = Math.max(issue.candidateScore * 0.5, isHighSignalCategory(issue) ? 0.28 : 0.2);
                        out.add(issue);
                        trace.record("issue_memory_soft_restored", Map.of("file", issue.file, "body", issue.body, "candidate_score", issue.candidateScore));
                    });
        }
        trace.record("phase_end", Map.of("phase", Phase.EVIDENCE_VALIDATION.name(), "before", input.size(), "after", out.size()));
        return out;
    }

    private record DiffEvidence(Set<Integer> changedLines, Map<Integer, String> addedLines, String patchText) {
    }

    private record EvidenceScore(double total, boolean lineValid, boolean evidenceMatchesChangedLine,
                                 boolean evidenceMatchesPatch, boolean staticOrLspSupported, boolean hasImpact,
                                 boolean hasSuggestion) {
    }

    private static EvidenceScore scoreDiffEvidence(ReviewIssue issue, DiffEvidence evidence, Map<String, Object> analysis) {
        boolean lineValid = evidence != null && changedLineValid(evidence.changedLines(), issue.line);
        boolean evidenceMatchesChangedLine = evidenceMatchesAddedLine(issue, evidence);
        boolean evidenceMatchesPatch = evidenceMatchesPatch(issue, evidence);
        boolean staticOrLspSupported = staticOrLspSupports(analysis, issue);
        boolean hasImpact = issue.impact != null && issue.impact.strip().length() >= 12;
        boolean hasSuggestion = issue.suggestion != null && issue.suggestion.strip().length() >= 8;
        double score = 0.0;
        if (lineValid) score += 0.35;
        if (evidenceMatchesChangedLine) score += 0.35;
        else if (evidenceMatchesPatch) score += 0.2;
        if (staticOrLspSupported) score += 0.15;
        if (hasImpact) score += 0.1;
        if (hasSuggestion) score += 0.05;
        return new EvidenceScore(Math.min(1.0, score), lineValid, evidenceMatchesChangedLine,
                evidenceMatchesPatch, staticOrLspSupported, hasImpact, hasSuggestion);
    }

    private static double calibratedConfidence(double modelConfidence, EvidenceScore score) {
        double base = Math.max(0.0, Math.min(1.0, modelConfidence));
        double blended = (base * 0.55) + (score.total() * 0.45);
        if (!score.lineValid() && !score.evidenceMatchesChangedLine() && !score.staticOrLspSupported()) {
            blended = Math.min(blended, 0.52);
        }
        if (!score.hasImpact()) {
            blended = Math.min(blended, 0.72);
        }
        return Math.max(0.0, Math.min(1.0, blended));
    }

    private static double minimumConfidence(ReviewIssue issue) {
        if ("style".equals(issue.category) || "maintainability".equals(issue.category)) {
            return 0.68;
        }
        if ("tests".equals(issue.category)) {
            return 0.62;
        }
        return 0.55;
    }

    private static double candidateScore(ReviewIssue issue, EvidenceScore evidence, Map<String, Object> analysis) {
        double score = 0.18 + (Math.max(0.0, Math.min(1.0, issue.confidence)) * 0.28);
        score += evidence.total() * 0.32;
        double contractAlignment = contractAlignmentScore(issue, analysis);
        score += contractAlignment * 0.24;
        if (isHighSignalCategory(issue)) {
            score += 0.12;
        }
        if ("tests".equals(issue.category) && concreteTestGap(issue, analysis)) {
            score += 0.1;
        }
        if (riskModelSupports(issue, analysis)) {
            score += 0.1;
        }
        if ("style".equals(issue.category) || "maintainability".equals(issue.category)) {
            score -= 0.12;
        }
        if (contractAlignment == 0.0 && unrelatedPlausibleFinding(issue)) {
            score -= 0.14;
            issue.validationReason = appendReason(issue.validationReason, "Candidate was demoted because it is plausible but weakly tied to a changed behavior contract.");
        } else if (contractAlignment >= 0.45) {
            issue.validationReason = appendReason(issue.validationReason, "Candidate aligns with a changed behavior contract.");
        }
        return Math.max(0.0, Math.min(1.0, score));
    }

    private static double repoCandidateScore(ReviewIssue issue, boolean evidenceInSource, boolean evidenceInChecks, boolean lspSupported) {
        double score = 0.15 + Math.max(0.0, Math.min(1.0, issue.confidence)) * 0.35;
        if (evidenceInSource) score += 0.2;
        if (evidenceInChecks) score += 0.15;
        if (lspSupported) score += 0.15;
        if (isHighSignalCategory(issue)) score += 0.1;
        if ("style".equals(issue.category) || "maintainability".equals(issue.category)) score -= 0.1;
        return Math.max(0.0, Math.min(1.0, score));
    }

    private static boolean isLowValueStyleIssue(ReviewIssue issue) {
        return ("style".equals(issue.category) || "maintainability".equals(issue.category))
                && issue.confidence < 0.45
                && issue.candidateScore < 0.35;
    }

    private static boolean isHighSignalCategory(ReviewIssue issue) {
        return "security".equals(issue.category) || "bug".equals(issue.category) || "performance".equals(issue.category);
    }

    private static boolean isRecoverableMemoryFilteredIssue(ReviewIssue issue) {
        return isHighSignalCategory(issue) || "tests".equals(issue.category);
    }

    @SuppressWarnings("unchecked")
    private static boolean riskModelSupports(ReviewIssue issue, Map<String, Object> analysis) {
        Object risk = analysis.get("risk_model");
        if (!(risk instanceof Map<?, ?> map)) {
            return false;
        }
        Object types = map.get("risk_types");
        String text = String.valueOf(types).toLowerCase();
        return switch (issue.category) {
            case "security" -> text.contains("security") || text.contains("auth");
            case "bug" -> text.contains("api") || text.contains("data") || text.contains("concurrency") || text.contains("general");
            case "performance" -> text.contains("performance") || text.contains("concurrency");
            case "tests" -> Boolean.TRUE.equals(((Map<String, Object>) map).get("has_behavior_change"));
            default -> false;
        };
    }

    @SuppressWarnings("unchecked")
    private static boolean concreteTestGap(ReviewIssue issue, Map<String, Object> analysis) {
        if (!"tests".equals(issue.category)) {
            return false;
        }
        Object regression = analysis.get("regression_test_reasoning");
        if (!(regression instanceof Map<?, ?> map)) {
            return false;
        }
        boolean likelyGap = Boolean.TRUE.equals(map.get("likely_test_gap"));
        Object files = map.get("files_needing_test_consideration");
        boolean fileNamed = files instanceof List<?> list && list.stream().anyMatch(item -> Objects.equals(String.valueOf(item), issue.file));
        String text = (issue.body + "\n" + issue.evidence + "\n" + issue.impact).toLowerCase();
        return likelyGap && fileNamed && containsAny(text, "untested", "test", "regression", "coverage", "case");
    }

    private static double contractAlignmentScore(ReviewIssue issue, Map<String, Object> analysis) {
        List<Map<String, Object>> contracts = listOfMaps(analysis.get("changed_behavior_contracts"));
        if (contracts.isEmpty()) {
            return 0.0;
        }
        String issueText = (nullToEmpty(issue.category) + "\n" + nullToEmpty(issue.body) + "\n" + nullToEmpty(issue.evidence)
                + "\n" + nullToEmpty(issue.impact) + "\n" + nullToEmpty(issue.suggestion)).toLowerCase();
        double best = 0.0;
        for (Map<String, Object> contract : contracts) {
            if (!Objects.equals(String.valueOf(contract.getOrDefault("file", "")), issue.file)) {
                continue;
            }
            double score = 0.25;
            String type = String.valueOf(contract.getOrDefault("type", "")).toLowerCase();
            String trigger = String.valueOf(contract.getOrDefault("trigger", "")).toLowerCase();
            String failure = String.valueOf(contract.getOrDefault("expected_failure", "")).toLowerCase();
            String evidence = String.valueOf(contract.getOrDefault("evidence", "")).toLowerCase();
            if (sharesToken(issueText, type.replace('_', ' '))) score += 0.18;
            if (sharesToken(issueText, trigger)) score += 0.2;
            if (sharesToken(issueText, failure)) score += 0.25;
            if (!evidence.isBlank() && containsNormalized(issueText, evidence)) score += 0.12;
            best = Math.max(best, Math.min(1.0, score));
        }
        return best;
    }

    private static boolean unrelatedPlausibleFinding(ReviewIssue issue) {
        String text = (nullToEmpty(issue.body) + "\n" + nullToEmpty(issue.evidence) + "\n" + nullToEmpty(issue.impact)).toLowerCase();
        return containsAny(text, "unused import", "style", "unconventional", "consider", "could be", "may be", "n+1", "sql injection", "mass-assignment")
                && !containsAny(text, "nil", "null", "panic", "always", "false", "true", "normaliz", "case", "missing required", "trace", "context", "exit", "deadline", "terminate");
    }

    private static boolean sharesToken(String left, String right) {
        if (left == null || right == null || right.isBlank()) {
            return false;
        }
        for (String token : right.toLowerCase().split("[^a-z0-9_]+")) {
            if (token.length() >= 5 && left.contains(token)) {
                return true;
            }
        }
        return false;
    }

    private static String appendReason(String current, String addition) {
        if (addition == null || addition.isBlank()) {
            return current;
        }
        String base = current == null ? "" : current.strip();
        if (base.isBlank()) {
            return addition;
        }
        return base.endsWith(".") ? base + " " + addition : base + ". " + addition;
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

    private static String inferEvidence(ReviewIssue issue, Map<String, Object> triage, DiffEvidence diffEvidence) {
        if (diffEvidence != null && issue.line != null && diffEvidence.addedLines().containsKey(issue.line)) {
            return "+" + diffEvidence.addedLines().get(issue.line);
        }
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

    private static Map<String, DiffEvidence> diffEvidenceByFile(Object changedFiles) {
        Map<String, DiffEvidence> result = new HashMap<>();
        for (Map<String, Object> file : listOfMaps(changedFiles)) {
            String filename = String.valueOf(file.get("filename"));
            String patch = String.valueOf(file.getOrDefault("patch", ""));
            result.put(filename, parseDiffEvidence(patch));
        }
        return result;
    }

    private static DiffEvidence parseDiffEvidence(String patch) {
        Set<Integer> lines = new HashSet<>();
        Map<Integer, String> addedLines = new LinkedHashMap<>();
        if (patch == null || patch.isBlank()) {
            return new DiffEvidence(lines, addedLines, "");
        }
        int currentNewLine = 0;
        int syntheticLine = 1;
        boolean sawHunk = false;
        for (String line : patch.split("\\R")) {
            Matcher matcher = Pattern.compile("@@ -\\d+(?:,\\d+)? \\+(\\d+)(?:,\\d+)? @@.*").matcher(line);
            if (matcher.matches()) {
                sawHunk = true;
                currentNewLine = Integer.parseInt(matcher.group(1));
                continue;
            }
            if (!sawHunk) {
                if (line.startsWith("+") && !line.startsWith("+++")) {
                    lines.add(syntheticLine);
                    addedLines.put(syntheticLine, line.substring(1));
                    syntheticLine++;
                }
                continue;
            }
            if (line.startsWith("+") && !line.startsWith("+++")) {
                lines.add(currentNewLine);
                addedLines.put(currentNewLine, line.substring(1));
                currentNewLine++;
            } else if (!line.startsWith("-")) {
                currentNewLine++;
            }
        }
        return new DiffEvidence(lines, addedLines, patch);
    }

    private static boolean changedLineValid(Set<Integer> validLines, Integer line) {
        return line != null && validLines != null && validLines.contains(line);
    }

    private static Integer inferLineFromEvidence(ReviewIssue issue, DiffEvidence evidence) {
        if (evidence == null || evidence.addedLines().isEmpty()) {
            return null;
        }
        String needle = strongestEvidenceText(issue);
        if (needle.isBlank()) {
            return null;
        }
        String normalizedNeedle = normalizeEvidence(needle);
        for (Map.Entry<Integer, String> entry : evidence.addedLines().entrySet()) {
            String line = normalizeEvidence(entry.getValue());
            if (!line.isBlank() && (line.contains(normalizedNeedle) || normalizedNeedle.contains(line))) {
                return entry.getKey();
            }
        }
        return null;
    }

    private static boolean evidenceMatchesAddedLine(ReviewIssue issue, DiffEvidence evidence) {
        if (evidence == null || evidence.addedLines().isEmpty()) {
            return false;
        }
        String needle = normalizeEvidence(strongestEvidenceText(issue));
        if (needle.isBlank()) {
            return false;
        }
        return evidence.addedLines().values().stream()
                .map(EvidenceValidationNode::normalizeEvidence)
                .anyMatch(line -> !line.isBlank() && (line.contains(needle) || needle.contains(line)));
    }

    private static boolean evidenceMatchesPatch(ReviewIssue issue, DiffEvidence evidence) {
        if (evidence == null || evidence.patchText() == null || evidence.patchText().isBlank()) {
            return false;
        }
        return containsNormalized(evidence.patchText(), strongestEvidenceText(issue));
    }

    private static String strongestEvidenceText(ReviewIssue issue) {
        if (issue.evidence != null && issue.evidence.strip().length() >= 4) {
            return issue.evidence;
        }
        return "";
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
                nullToEmpty(issue.category),
                issue.line == null ? "" : String.valueOf(issue.line),
                duplicateAnchor(issue)
        ).toLowerCase();
    }

    private static String duplicateAnchor(ReviewIssue issue) {
        String evidence = normalizeEvidence(issue.evidence);
        if (!evidence.isBlank()) {
            return evidence.length() > 160 ? evidence.substring(0, 160) : evidence;
        }
        return semanticSubject(issue.body + " " + issue.impact) + "|" + semanticPrefix(issue.body);
    }

    private static String semanticSubject(String text) {
        String normalized = nullToEmpty(text).toLowerCase();
        Matcher matcher = Pattern.compile("[a-zA-Z_][a-zA-Z0-9_./:-]{2,}").matcher(normalized);
        List<String> terms = new ArrayList<>();
        while (matcher.find() && terms.size() < 4) {
            String term = matcher.group();
            if (!Set.of("the", "and", "for", "with", "this", "that", "from", "line", "file").contains(term)) {
                terms.add(term);
            }
        }
        return String.join(" ", terms);
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

    private static boolean containsNormalized(String haystack, String needle) {
        String normalizedNeedle = normalizeEvidence(needle);
        if (normalizedNeedle.isBlank()) {
            return false;
        }
        return normalizeEvidence(haystack).contains(normalizedNeedle);
    }

    private static String normalizeEvidence(String value) {
        if (value == null) {
            return "";
        }
        String text = value.strip();
        if (text.startsWith("+") || text.startsWith("-")) {
            text = text.substring(1).strip();
        }
        return text.replaceAll("\\s+", " ").strip().toLowerCase();
    }

    @SuppressWarnings("unchecked")
    private static boolean staticOrLspSupports(Map<String, Object> analysis, ReviewIssue issue) {
        Object checks = analysis.get("static_checks");
        if (checks != null && containsNormalized(Jsons.stringify(checks), issue.file)
                && containsNormalized(Jsons.stringify(checks), strongestEvidenceText(issue))) {
            return true;
        }
        Object lsp = analysis.get("lsp_context");
        if (lsp instanceof Map<?, ?> map) {
            return lspMentions((Map<String, Object>) map, issue.file, issue.line);
        }
        return false;
    }

    private static boolean lspMentions(Map<String, Object> lspContext, String file, Integer line) {
        if (lspContext == null || lspContext.isEmpty()) {
            return false;
        }
        String text = Jsons.stringify(lspContext);
        if (!text.contains(file)) {
            return false;
        }
        return line == null || text.contains("\"line\":" + line) || text.contains("\"start_line\":" + line) || text.contains("\"end_line\":" + line);
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
