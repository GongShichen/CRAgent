package com.cragent.agent;

import com.cragent.trace.TraceRecorder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

public final class ReviewAnalysisNodes {
    private ReviewAnalysisNodes() {
    }

    public static Map<String, Object> sharedReviewStrategy(Map<String, Object> analysis) {
        return Map.of(
                "context_expansion", analysis.getOrDefault("context_expansion", Map.of()),
                "repo_context", analysis.getOrDefault("repo_context", Map.of()),
                "lsp_context", analysis.getOrDefault("lsp_context", Map.of()),
                "static_checks", analysis.getOrDefault("static_checks", List.of()),
                "risk_model", analysis.getOrDefault("risk_model", Map.of()),
                "regression_test_reasoning", analysis.getOrDefault("regression_test_reasoning", Map.of()),
                "evidence_validation", "Runtime validates file membership, line eligibility, duplicate findings, evidence, confidence, false-positive memory, LSP/static-check evidence, and severity calibration after model output."
        );
    }

    public static Map<String, Object> riskModel(Map<String, Object> triage, Map<String, Object> analysis, TraceRecorder trace) {
        trace.record("strategy_start", Map.of("strategy", "Risk Modeling"));
        List<Map<String, Object>> files = listOfMaps(triage.get("changed_files"));
        List<String> riskTypes = new ArrayList<>();
        List<String> reviewFocus = new ArrayList<>();
        boolean hasTests = false;
        boolean hasBehavior = false;
        for (Map<String, Object> file : files) {
            String name = String.valueOf(file.get("filename"));
            String lower = name.toLowerCase();
            String patch = String.valueOf(file.getOrDefault("patch", "")).toLowerCase();
            if (lower.contains("test") || lower.contains("spec")) {
                hasTests = true;
            }
            if (!docsOrConfigOnly(name) && !lower.contains("test")) {
                hasBehavior = true;
            }
            if (securityCoreFile(name) || containsAny(patch, "password", "token", "secret", "auth", "permission", "credential")) {
                addUnique(riskTypes, "security/auth");
                addUnique(reviewFocus, "trust boundaries, credential handling, auth/authz behavior");
            }
            if (containsAny(lower, "migration", "schema", "db/", "database") || containsAny(patch, "alter table", "create table", "drop table", "transaction")) {
                addUnique(riskTypes, "data/migration");
                addUnique(reviewFocus, "schema compatibility, transaction safety, rollback behavior");
            }
            if (containsAny(patch, "thread", "async", "await", "lock", "mutex", "synchronized", "goroutine", "channel", "executor")) {
                addUnique(riskTypes, "concurrency/async");
                addUnique(reviewFocus, "race conditions, cancellation, timeout, resource cleanup");
            }
            if (containsAny(lower, "api", "controller", "route", "handler", "graphql", "proto", "openapi") || containsAny(patch, "public ", "endpoint", "route", "request", "response")) {
                addUnique(riskTypes, "api/contract");
                addUnique(reviewFocus, "backward compatibility, validation, error semantics");
            }
            if (containsAny(lower, "package.json", "pom.xml", "build.gradle", "cargo.toml", "requirements.txt", "go.mod", "composer.json", "gemfile")) {
                addUnique(riskTypes, "dependency/build");
                addUnique(reviewFocus, "supply-chain risk, version compatibility, build/test behavior");
            }
        }
        if (Boolean.TRUE.equals(triage.get("docs_only"))) {
            addUnique(riskTypes, "docs-only");
        } else if (!hasBehavior && hasTests) {
            addUnique(riskTypes, "test-only");
        } else if (hasBehavior && !hasTests) {
            addUnique(riskTypes, "behavior-without-test-change");
            addUnique(reviewFocus, "regression risk and missing test coverage");
        }
        if (riskTypes.isEmpty()) {
            riskTypes.add("general-correctness");
            reviewFocus.add("correctness, error handling, maintainability");
        }
        Object lspContext = analysis.get("lsp_context");
        if (lspContext instanceof Map<?, ?> lspMap) {
            Object status = lspMap.get("status");
            if (status != null && !"disabled".equals(String.valueOf(status)) && !"unavailable".equals(String.valueOf(status))) {
                addUnique(reviewFocus, "LSP diagnostics, symbol boundaries, definitions, and references");
            }
        }
        String level = Boolean.TRUE.equals(triage.get("high_risk")) || riskTypes.stream().anyMatch(r -> r.contains("security") || r.contains("data")) ? "high"
                : (riskTypes.contains("behavior-without-test-change") || riskTypes.contains("api/contract") ? "medium" : "low");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("risk_level", level);
        result.put("risk_types", riskTypes);
        result.put("review_focus", reviewFocus);
        result.put("has_behavior_change", hasBehavior);
        result.put("has_test_change", hasTests);
        Object lspStatus = "unknown";
        if (lspContext instanceof Map<?, ?> lspMap && lspMap.containsKey("status")) {
            lspStatus = lspMap.get("status");
        }
        result.put("lsp_status", lspStatus);
        trace.record("strategy_end", Map.of("strategy", "Risk Modeling", "result", result));
        return result;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> regressionTestReasoning(Map<String, Object> triage, Map<String, Object> analysis, TraceRecorder trace) {
        trace.record("strategy_start", Map.of("strategy", "Regression/Test Reasoning"));
        List<Map<String, Object>> changedFiles = listOfMaps(triage.get("changed_files"));
        List<Map<String, Object>> related = (List<Map<String, Object>>) analysis.getOrDefault("related_tests", List.of());
        Map<String, Object> riskModel = (Map<String, Object>) analysis.getOrDefault("risk_model", Map.of());
        boolean hasBehavior = Boolean.TRUE.equals(riskModel.get("has_behavior_change"));
        boolean hasTestChange = Boolean.TRUE.equals(riskModel.get("has_test_change"));
        int relatedTestCount = related.stream()
                .map(item -> item.get("tests"))
                .filter(Map.class::isInstance)
                .map(Map.class::cast)
                .mapToInt(item -> intValue(item.get("count")))
                .sum();
        List<String> filesNeedingTests = changedFiles.stream()
                .map(file -> String.valueOf(file.get("filename")))
                .filter(name -> !docsOrConfigOnly(name) && !name.toLowerCase().contains("test"))
                .limit(12)
                .toList();
        boolean likelyGap = hasBehavior && !hasTestChange && relatedTestCount == 0;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("has_behavior_change", hasBehavior);
        result.put("has_test_change", hasTestChange);
        result.put("related_test_count", relatedTestCount);
        result.put("likely_test_gap", likelyGap);
        result.put("files_needing_test_consideration", filesNeedingTests);
        result.put("guidance", likelyGap
                ? "Only report a tests issue if the diff changes executable behavior and no related tests cover the changed path."
                : "Avoid generic missing-test comments unless a concrete untested behavior or regression path is visible.");
        trace.record("strategy_end", Map.of("strategy", "Regression/Test Reasoning", "result", result));
        return result;
    }

    public static Map<String, Object> repoAuditSyntheticTriage(RepoAuditIndexer.AuditIndex index) {
        List<Map<String, Object>> changedFiles = index.files().stream()
                .map(file -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("filename", file.path());
                    item.put("status", "repo_audit");
                    item.put("additions", file.lines());
                    item.put("deletions", 0);
                    item.put("patch", "");
                    return item;
                })
                .toList();
        Map<String, Object> triage = new LinkedHashMap<>();
        triage.put("target", "repo_audit");
        triage.put("changed_files", changedFiles);
        triage.put("docs_only", false);
        triage.put("high_risk", index.files().stream().anyMatch(RepoAuditIndexer.AuditFile::sensitive));
        triage.put("should_review", true);
        triage.put("human_required", false);
        triage.put("author", "repo_audit");
        return triage;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> repoAuditContextExpansion(RepoAuditIndexer.AuditIndex index, List<Map<String, Object>> manifest, Map<String, Object> lspContext) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("source", "repo_audit");
        result.put("dependency_manifests", index.files().stream().filter(RepoAuditIndexer.AuditFile::config).map(RepoAuditIndexer.AuditFile::manifest).toList());
        result.put("sensitive_paths", index.files().stream().filter(RepoAuditIndexer.AuditFile::sensitive).map(RepoAuditIndexer.AuditFile::path).toList());
        result.put("related_tests", index.files().stream().filter(RepoAuditIndexer.AuditFile::test).map(RepoAuditIndexer.AuditFile::manifest).toList());
        result.put("security_file_contents", index.files().stream().filter(RepoAuditIndexer.AuditFile::sensitive).limit(20)
                .map(file -> Map.of("path", file.path(), "content_preview", file.content().substring(0, Math.min(file.content().length(), 2000)))).toList());
        result.put("manifest_summary", manifest);
        Object symbols = lspContext.get("symbols_preview");
        result.put("lsp_symbols_preview", symbols instanceof List<?> list
                ? list.stream().filter(Map.class::isInstance).limit(200).map(item -> (Map<String, Object>) item).toList()
                : List.of());
        return result;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> listOfMaps(Object value) {
        if (value instanceof List<?> list) {
            return list.stream().filter(Map.class::isInstance).map(item -> (Map<String, Object>) item).collect(Collectors.toList());
        }
        return List.of();
    }

    private static boolean docsOrConfigOnly(String filename) {
        String lower = filename.toLowerCase();
        return lower.endsWith(".md") || lower.endsWith(".rst") || lower.endsWith(".txt") || lower.endsWith(".adoc")
                || lower.endsWith(".yml") || lower.endsWith(".yaml") || lower.endsWith(".json")
                || lower.endsWith(".toml") || lower.endsWith(".lock") || lower.contains("/docs/");
    }

    private static boolean securityCoreFile(String filename) {
        String lower = filename.toLowerCase();
        return lower.contains("auth") || lower.contains("security") || lower.contains("permission")
                || lower.contains("token") || lower.contains("password") || lower.contains("secret")
                || lower.contains("crypto") || lower.contains("session");
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

    private static void addUnique(List<String> list, String value) {
        if (!list.contains(value)) {
            list.add(value);
        }
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
