package com.cragent.agent;

import com.cragent.model.AgentRunResult;
import com.cragent.model.ReviewIssue;
import com.cragent.util.Jsons;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

public class ReportWriter {
    private final Path reportDir;

    public ReportWriter(Path reportDir) {
        this.reportDir = reportDir;
    }

    public Path write(AgentRunResult result, Map<String, Object> target, Map<String, Object> draft) throws IOException {
        Files.createDirectories(reportDir);
        String suffix = target != null && "repo_audit".equals(String.valueOf(target.get("target"))) ? "-repo-audit" : "";
        String filename = result.sessionId + "-" + safeName(result.repo) + suffix + ".md";
        Path path = reportDir.resolve(filename);
        Files.writeString(path, render(result, target, draft), StandardCharsets.UTF_8);
        return path;
    }

    private String render(AgentRunResult result, Map<String, Object> target, Map<String, Object> draft) {
        String title = text(draft, "title", "Code Review Report: " + result.repo);
        String executiveSummary = text(draft, "executive_summary", result.summary);
        String riskAssessment = text(draft, "risk_assessment", "No additional risk assessment was generated.");
        String testAssessment = text(draft, "test_assessment", "No additional test assessment was generated.");
        String recommendation = text(draft, "recommendation", result.issues.isEmpty() ? "No blocking issues found." : "Review the issues listed below before merging.");

        StringBuilder out = new StringBuilder();
        out.append("# ").append(title).append("\n\n");
        appendRunMetadata(out, result, target);
        appendContextSummary(out, target);

        out.append("## Executive Summary\n\n").append(executiveSummary).append("\n\n");
        out.append("## Risk Assessment\n\n").append(riskAssessment).append("\n\n");
        out.append("## Test Assessment\n\n").append(testAssessment).append("\n\n");
        out.append("## Issues\n\n");
        if (result.issues.isEmpty()) {
            out.append("No actionable issues were found.\n\n");
        } else {
            appendIssueSummaryTable(out, result.issues);
            int index = 1;
            for (ReviewIssue issue : result.issues.stream().sorted(Comparator.comparingInt(ReportWriter::severityRank)).toList()) {
                appendIssue(out, index++, issue);
            }
            out.append("\n");
        }

        out.append("## Actions\n\n");
        if (result.actions.isEmpty()) {
            out.append("No actions were taken.\n\n");
        } else {
            for (Map<String, Object> action : result.actions) {
                out.append("### `").append(action.getOrDefault("name", "action")).append("`\n\n");
                appendJsonBlock(out, action, "json");
                out.append("\n");
            }
            out.append("\n");
        }

        out.append("## Recommendation\n\n").append(recommendation).append("\n\n");
        if (draft != null && !draft.isEmpty()) {
            appendDetailsJson(out, "Report Draft JSON", draft);
        }
        return out.toString();
    }

    private static void appendRunMetadata(StringBuilder out, AgentRunResult result, Map<String, Object> target) {
        out.append("## Run Metadata\n\n");
        out.append("| Field | Value |\n");
        out.append("| --- | --- |\n");
        row(out, "Generated at", "`" + Instant.now() + "`");
        row(out, "Session", "`" + result.sessionId + "`");
        row(out, "Repository", "`" + result.repo + "`");
        row(out, "Status", "`" + result.status + "`");
        row(out, "Dry run", "`" + result.dryRun + "`");
        row(out, "Trace", "`" + result.tracePath + "`");
        if (target != null) {
            for (Map.Entry<String, Object> entry : target.entrySet()) {
                if (isScalar(entry.getValue())) {
                    row(out, entry.getKey(), "`" + markdownEscape(String.valueOf(entry.getValue())) + "`");
                }
            }
        }
        out.append("\n");
    }

    @SuppressWarnings("unchecked")
    private static void appendContextSummary(StringBuilder out, Map<String, Object> target) {
        if (target == null || target.isEmpty()) {
            return;
        }
        out.append("## Context Summary\n\n");
        Object coverage = target.get("coverage_summary");
        if (coverage instanceof Map<?, ?> map) {
            out.append("### Coverage\n\n");
            out.append("| Metric | Value |\n");
            out.append("| --- | ---: |\n");
            row(out, "Files total", inlineValue(map.get("files_total")));
            row(out, "Reviewable files", inlineValue(map.get("reviewable_files")));
            row(out, "Slices total", inlineValue(map.get("slices_total")));
            Object statusCounts = map.get("status_counts");
            if (statusCounts instanceof Map<?, ?> statusMap) {
                for (Map.Entry<?, ?> entry : statusMap.entrySet()) {
                    row(out, "Status: " + entry.getKey(), inlineValue(entry.getValue()));
                }
            }
            Object skipReasons = map.get("skip_reasons");
            if (skipReasons instanceof Map<?, ?> skipMap && !skipMap.isEmpty()) {
                for (Map.Entry<?, ?> entry : skipMap.entrySet()) {
                    row(out, "Skipped: " + entry.getKey(), inlineValue(entry.getValue()));
                }
            }
            out.append("\n");
        }

        Object risk = target.get("risk_model");
        if (risk instanceof Map<?, ?> rawRisk) {
            out.append("### Risk Model\n\n");
            out.append("| Field | Value |\n");
            out.append("| --- | --- |\n");
            row(out, "Risk level", inlineValue(rawRisk.get("risk_level")));
            row(out, "LSP status", inlineValue(rawRisk.get("lsp_status")));
            row(out, "LSP symbol count", inlineValue(rawRisk.get("lsp_symbol_count")));
            row(out, "Stack", inlineValue(rawRisk.get("stack")));
            row(out, "Sensitive files", inlineValue(rawRisk.get("sensitive_files")));
            out.append("\n");
        }

        Object checks = target.get("static_checks");
        if (checks instanceof List<?> list && !list.isEmpty()) {
            out.append("### Static Checks\n\n");
            out.append("| Command | Status | Exit Code |\n");
            out.append("| --- | --- | ---: |\n");
            for (Object item : list) {
                if (item instanceof Map<?, ?> check) {
                    row(out, check.get("command"), check.get("status"), check.get("exit_code"));
                }
            }
            out.append("\n");
            for (Object item : list) {
                if (item instanceof Map<?, ?> check && check.get("output") != null) {
                    out.append("<details>\n<summary>`").append(markdownEscape(String.valueOf(check.get("command")))).append("` output</summary>\n\n");
                    appendFence(out, String.valueOf(check.get("output")).stripTrailing(), "text");
                    out.append("\n</details>\n\n");
                }
            }
        }

        Object lsp = target.get("lsp_context");
        if (lsp instanceof Map<?, ?> rawLsp) {
            out.append("### LSP\n\n");
            out.append("| Field | Value |\n");
            out.append("| --- | --- |\n");
            row(out, "Enabled", inlineValue(rawLsp.get("enabled")));
            row(out, "Status", inlineValue(rawLsp.get("status")));
            row(out, "Symbol count", inlineValue(rawLsp.get("symbol_count")));
            row(out, "Errors", inlineValue(rawLsp.get("errors")));
            out.append("\n");
        }

        Object languageSkills = target.get("language_skill_selection");
        if (languageSkills instanceof List<?> list && !list.isEmpty()) {
            out.append("### Language Skills\n\n");
            out.append("| Skill | Reason | Matched languages | Matched files |\n");
            out.append("| --- | --- | --- | --- |\n");
            for (Object item : list) {
                if (item instanceof Map<?, ?> skill) {
                    Object name = skill.get("name");
                    Object reason = skill.get("reason");
                    row(out,
                            "`" + markdownEscape(name == null ? "" : String.valueOf(name)) + "`",
                            markdownEscape(reason == null ? "" : String.valueOf(reason)),
                            inlineValue(skill.get("matched_languages")),
                            inlineValue(skill.get("matched_files")));
                }
            }
            out.append("\n");
        }

        Object contextEngine = target.get("context_engine");
        if (contextEngine instanceof Map<?, ?> context && !context.isEmpty()) {
            out.append("### Context Engine\n\n");
            Object summary = context.get("context_summary");
            if (summary instanceof Map<?, ?> summaryMap) {
                out.append("| Field | Value |\n");
                out.append("| --- | --- |\n");
                row(out, "Mode", inlineValue(summaryMap.get("mode")));
                row(out, "Indexed files", inlineValue(summaryMap.get("indexed_files")));
                row(out, "Candidate items", inlineValue(summaryMap.get("candidate_items")));
                row(out, "Selected items", inlineValue(summaryMap.get("selected_items")));
                row(out, "Compressed items", inlineValue(summaryMap.get("compressed_items")));
                row(out, "Top context types", inlineValue(summaryMap.get("top_context_types")));
                row(out, "Top retrieval channels", inlineValue(summaryMap.get("top_retrieval_channels")));
                out.append("\n");
            }
            Object ledger = context.get("context_ledger");
            if (ledger instanceof Map<?, ?> ledgerMap) {
                out.append("| Ledger | Value |\n");
                out.append("| --- | --- |\n");
                row(out, "RRF k", inlineValue(ledgerMap.get("rrf_k")));
                row(out, "Char budget", inlineValue(ledgerMap.get("char_budget")));
                row(out, "Max items", inlineValue(ledgerMap.get("max_items")));
                row(out, "Channel counts", inlineValue(ledgerMap.get("channel_counts")));
                row(out, "RRF top items", inlineValue(ledgerMap.get("rrf_top_items")));
                row(out, "Selected chars", inlineValue(ledgerMap.get("selected_chars")));
                row(out, "Selected item ids", inlineValue(ledgerMap.get("selected_item_ids")));
                row(out, "Compressed item ids", inlineValue(ledgerMap.get("compressed_item_ids")));
                out.append("\n");
            }
        }

        appendDetailsJson(out, "Full Context JSON", compactTarget(target));
    }

    private static Map<String, Object> compactTarget(Map<String, Object> target) {
        Map<String, Object> compact = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : target.entrySet()) {
            if ("shared_analysis".equals(entry.getKey())) {
                compact.put(entry.getKey(), entry.getValue());
            } else if (!isScalar(entry.getValue())) {
                compact.put(entry.getKey(), entry.getValue());
            }
        }
        return compact;
    }

    private static void appendIssueSummaryTable(StringBuilder out, List<ReviewIssue> issues) {
        out.append("| # | Severity | Category | Location | Finding |\n");
        out.append("| ---: | --- | --- | --- | --- |\n");
        int i = 1;
        for (ReviewIssue issue : issues.stream().sorted(Comparator.comparingInt(ReportWriter::severityRank)).toList()) {
            row(out,
                    i++,
                    "`" + nullToUnknown(issue.severity == null ? null : issue.severity.name()) + "`",
                    "`" + markdownEscape(nullToUnknown(issue.category)) + "`",
                    "`" + markdownEscape(location(issue)) + "`",
                    markdownEscape(firstSentence(redact(nullToEmpty(issue.body)), 140)));
        }
        out.append("\n");
    }

    private static void appendIssue(StringBuilder out, int index, ReviewIssue issue) {
        out.append("### ").append(index).append(". `")
                .append(issue.severity == null ? "unknown" : issue.severity.name())
                .append("` ")
                .append(markdownEscape(nullToUnknown(issue.category)))
                .append(" - `").append(markdownEscape(location(issue))).append("`\n\n");
        out.append(redact(nullToEmpty(issue.body))).append("\n\n");
        out.append("| Field | Value |\n");
        out.append("| --- | --- |\n");
        row(out, "Confidence", "`" + issue.confidence + "`");
        row(out, "Auto-fixable", "`" + issue.autoFixable + "`");
        out.append("\n");
        if (issue.evidence != null && !issue.evidence.isBlank()) {
            out.append("**Evidence**\n\n");
            appendFence(out, redact(issue.evidence.strip()), languageForPath(issue.file));
            out.append("\n");
        }
        if (issue.impact != null && !issue.impact.isBlank()) {
            out.append("**Impact**\n\n").append(redact(issue.impact)).append("\n\n");
        }
        if (issue.suggestion != null && !issue.suggestion.isBlank()) {
            out.append("**Suggestion**\n\n").append(redact(issue.suggestion)).append("\n\n");
        }
        if (issue.fixCode != null && !issue.fixCode.isBlank()) {
            out.append("**Suggested Patch**\n\n");
            appendFence(out, redact(issue.fixCode.strip()), languageForPath(issue.file));
            out.append("\n");
        }
    }

    private static String text(Map<String, Object> draft, String key, String fallback) {
        if (draft == null) {
            return fallback;
        }
        Object value = draft.get(key);
        if (value instanceof String text && !text.isBlank()) {
            return text;
        }
        if (value instanceof List<?> list && !list.isEmpty()) {
            StringBuilder out = new StringBuilder();
            for (Object item : list) {
                out.append("- ").append(item).append("\n");
            }
            return out.toString().stripTrailing();
        }
        return fallback;
    }

    private static String safeName(String repo) {
        return (repo == null || repo.isBlank() ? "unknown" : repo).replaceAll("[^A-Za-z0-9._-]+", "-");
    }

    private static void appendDetailsJson(StringBuilder out, String title, Object value) {
        if (value == null) {
            return;
        }
        out.append("<details>\n<summary>").append(markdownEscape(title)).append("</summary>\n\n");
        appendJsonBlock(out, value, "json");
        out.append("\n</details>\n\n");
    }

    private static void appendJsonBlock(StringBuilder out, Object value, String language) {
        appendFence(out, redact(Jsons.pretty(value)), language);
    }

    private static void appendFence(StringBuilder out, String content, String language) {
        String body = content == null ? "" : content;
        String fence = body.contains("```") ? "````" : "```";
        out.append(fence).append(language == null || language.isBlank() ? "" : language).append("\n");
        out.append(body).append("\n");
        out.append(fence).append("\n");
    }

    private static void row(StringBuilder out, Object... values) {
        StringJoiner joiner = new StringJoiner(" | ", "| ", " |\n");
        for (Object value : values) {
            joiner.add(value == null ? "" : markdownEscape(String.valueOf(value)));
        }
        out.append(joiner);
    }

    private static boolean isScalar(Object value) {
        return value == null || value instanceof String || value instanceof Number || value instanceof Boolean;
    }

    private static String inlineValue(Object value) {
        if (value == null) {
            return "";
        }
        if (isScalar(value)) {
            return "`" + markdownEscape(String.valueOf(value)) + "`";
        }
        if (value instanceof List<?> list) {
            if (list.isEmpty()) {
                return "`[]`";
            }
            return markdownEscape(list.stream().limit(8).map(String::valueOf).toList().toString()) + (list.size() > 8 ? " ..." : "");
        }
        if (value instanceof Map<?, ?> map) {
            return markdownEscape(map.toString());
        }
        return markdownEscape(String.valueOf(value));
    }

    private static String location(ReviewIssue issue) {
        return nullToUnknown(issue.file) + ":" + (issue.line == null ? "?" : issue.line);
    }

    private static int severityRank(ReviewIssue issue) {
        if (issue == null || issue.severity == null) {
            return 99;
        }
        return switch (issue.severity) {
            case critical -> 0;
            case high -> 1;
            case medium -> 2;
            case low -> 3;
            case info -> 4;
        };
    }

    private static String languageForPath(String path) {
        String lower = path == null ? "" : path.toLowerCase();
        if (lower.endsWith(".java")) return "java";
        if (lower.endsWith(".go")) return "go";
        if (lower.endsWith(".py")) return "python";
        if (lower.endsWith(".js")) return "javascript";
        if (lower.endsWith(".ts") || lower.endsWith(".tsx")) return "typescript";
        if (lower.endsWith(".rs")) return "rust";
        if (lower.endsWith(".swift")) return "swift";
        if (lower.endsWith(".rb") || lower.endsWith(".gemspec")) return "ruby";
        if (lower.endsWith(".c") || lower.endsWith(".h")) return "c";
        if (lower.endsWith(".cc") || lower.endsWith(".cpp") || lower.endsWith(".cxx")
                || lower.endsWith(".hh") || lower.endsWith(".hpp") || lower.endsWith(".hxx")) return "cpp";
        if (lower.endsWith(".m") || lower.endsWith(".mm")) return "objective-c";
        if (lower.endsWith(".json")) return "json";
        if (lower.endsWith(".xml")) return "xml";
        if (lower.endsWith(".yml") || lower.endsWith(".yaml")) return "yaml";
        if (lower.endsWith(".sh")) return "bash";
        if (lower.endsWith(".md")) return "markdown";
        return "text";
    }

    private static String firstSentence(String value, int maxChars) {
        if (value.length() <= maxChars) {
            return value;
        }
        return value.substring(0, Math.max(0, maxChars - 3)).stripTrailing() + "...";
    }

    private static String redact(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replaceAll("(?i)(api[_-]?key\\s*[:=]\\s*)[A-Za-z0-9._:/+\\-=]{12,}", "$1[REDACTED]")
                .replaceAll("(?i)(token\\s*[:=]\\s*)[A-Za-z0-9._:/+\\-=]{12,}", "$1[REDACTED]")
                .replaceAll("tp-[A-Za-z0-9]{12,}", "tp-[REDACTED]")
                .replaceAll("gh[pousr]_[A-Za-z0-9_]{20,}", "gh_[REDACTED]");
    }

    private static String markdownEscape(String value) {
        return value == null ? "" : value.replace("|", "\\|").replace("\n", "<br>");
    }

    private static String nullToUnknown(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
