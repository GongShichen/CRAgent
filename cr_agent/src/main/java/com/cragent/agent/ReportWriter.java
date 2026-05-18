package com.cragent.agent;

import com.cragent.model.AgentRunResult;
import com.cragent.model.ReviewIssue;
import com.cragent.util.Jsons;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public class ReportWriter {
    private final Path reportDir;

    public ReportWriter(Path reportDir) {
        this.reportDir = reportDir;
    }

    public Path write(AgentRunResult result, Map<String, Object> target, Map<String, Object> draft) throws IOException {
        Files.createDirectories(reportDir);
        String filename = result.sessionId + "-" + safeName(result.repo) + ".md";
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
        out.append("- Generated at: ").append(Instant.now()).append("\n");
        out.append("- Session: `").append(result.sessionId).append("`\n");
        out.append("- Repository: `").append(result.repo).append("`\n");
        appendTarget(out, target);
        out.append("- Status: `").append(result.status).append("`\n");
        out.append("- Dry run: `").append(result.dryRun).append("`\n");
        out.append("- Trace: `").append(result.tracePath).append("`\n\n");

        out.append("## Executive Summary\n\n").append(executiveSummary).append("\n\n");
        out.append("## Risk Assessment\n\n").append(riskAssessment).append("\n\n");
        out.append("## Test Assessment\n\n").append(testAssessment).append("\n\n");
        out.append("## Issues\n\n");
        if (result.issues.isEmpty()) {
            out.append("No actionable issues were found.\n\n");
        } else {
            for (ReviewIssue issue : result.issues) {
                out.append("- `").append(issue.severity).append("` `").append(issue.category).append("` ");
                out.append(nullToUnknown(issue.file)).append(":").append(issue.line == null ? "?" : issue.line).append(" - ");
                out.append(nullToEmpty(issue.body)).append("\n");
                if (issue.evidence != null && !issue.evidence.isBlank()) {
                    out.append("  - Evidence: ").append(issue.evidence).append("\n");
                }
                if (issue.impact != null && !issue.impact.isBlank()) {
                    out.append("  - Impact: ").append(issue.impact).append("\n");
                }
                if (issue.suggestion != null && !issue.suggestion.isBlank()) {
                    out.append("  - Suggestion: ").append(issue.suggestion).append("\n");
                }
            }
            out.append("\n");
        }

        out.append("## Actions\n\n");
        if (result.actions.isEmpty()) {
            out.append("No actions were taken.\n\n");
        } else {
            for (Map<String, Object> action : result.actions) {
                out.append("- `").append(action.getOrDefault("name", "action")).append("`");
                Object actionResult = action.get("result");
                if (actionResult != null) {
                    out.append(": ").append(actionResult);
                }
                out.append("\n");
            }
            out.append("\n");
        }

        out.append("## Recommendation\n\n").append(recommendation).append("\n\n");
        if (draft != null && !draft.isEmpty()) {
            out.append("## Report Draft JSON\n\n```json\n").append(Jsons.pretty(draft)).append("\n```\n");
        }
        return out.toString();
    }

    private static void appendTarget(StringBuilder out, Map<String, Object> target) {
        if (target == null || target.isEmpty()) {
            return;
        }
        for (Map.Entry<String, Object> entry : target.entrySet()) {
            out.append("- ").append(entry.getKey()).append(": `").append(entry.getValue()).append("`\n");
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

    private static String nullToUnknown(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
