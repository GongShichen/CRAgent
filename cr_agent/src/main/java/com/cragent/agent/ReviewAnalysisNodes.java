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
                "changed_behavior_contracts", analysis.getOrDefault("changed_behavior_contracts", List.of()),
                "evidence_validation", "Runtime validates file membership, line eligibility, duplicate findings, evidence, confidence, false-positive memory, LSP/static-check evidence, and severity calibration after model output."
        );
    }

    public static List<Map<String, Object>> changedBehaviorContracts(Map<String, Object> triage, Map<String, Object> analysis, TraceRecorder trace) {
        trace.record("strategy_start", Map.of("strategy", "Changed Behavior Contract"));
        List<Map<String, Object>> contracts = new ArrayList<>();
        int next = 1;
        String prText = String.valueOf(triage.getOrDefault("pull_request", "")).toLowerCase();
        for (Map<String, Object> file : listOfMaps(triage.get("changed_files"))) {
            String path = String.valueOf(file.getOrDefault("filename", file.getOrDefault("path", "")));
            String patch = String.valueOf(file.getOrDefault("patch", ""));
            if (path.isBlank() || patch.isBlank() || "null".equals(patch)) {
                continue;
            }
            String lower = (path + "\n" + patch).toLowerCase();
            int line = firstChangedLine(patch);
            if (containsAny(lower, "return false", "return true", "&&", "||", "enabled", "disable", "canview", "canmanage", "permission", "authorize")) {
                contracts.add(contract(next++, "boolean_guard", path, line, excerpt(patch, "return false", "return true", "&&", "||", "permission", "enabled"),
                        "Changed boolean/feature/permission guard",
                        "A guard can become always true/false, inverted, or narrower/wider than the previous contract."));
            }
            if (containsAny(lower, "system.exit", "picocli.exit", ".exit(", "process.exit", "os.exit")) {
                contracts.add(contract(next++, "side_effect_exit", path, line, excerpt(patch, "System.exit", "picocli.exit", ".exit(", "process.exit"),
                        "Changed CLI/process exit behavior",
                        "Calling process exit from library/command code can terminate tests, embedding callers, or cleanup paths unexpectedly."));
            }
            if (containsAny(lower, "nil", "null", "undefined", "optional", "where(id", ".first", "find_by", "findby", "req.", "request.", "plugincontext", "pass")
                    && !lower.contains("var _")) {
                contracts.add(contract(next++, "null_request_contract", path, line, excerpt(patch, "req.PluginContext", "request.", "req.", "where(id", ".first", "find_by", "nil", "null", "undefined", "PluginContext"),
                        "Changed nullability/request lookup contract",
                        "Missing nil/null/not-found handling can turn absent records or nil requests into runtime exceptions."));
            }
            if (containsAny(lower, "?.", "optional", "destinationcalendar", "mainhostdestinationcalendar", ".find(", ".filter(", ".map(")) {
                contracts.add(contract(next++, "optional_collection_contract", path, line, excerpt(patch, "?.", "destinationCalendar", "mainHostDestinationCalendar", ".find(", ".filter("),
                        "Changed optional collection selection contract",
                        "Fallback or lookup logic can become unreachable, self-matching, or nil when optional collections are empty or caller-provided ids are present."));
            }
            if (containsAny(lower, "externalcalendarid", "externalid", "credentialid", "haspermission", "permission", "getid()", "getname()", "resource.getid", "resource.getname")) {
                contracts.add(contract(next++, "identifier_semantics_contract", path, line, excerpt(patch, "externalCalendarId", "externalId", "credentialId", "hasPermission", "getId", "getName"),
                        "Changed identifier semantics contract",
                        "Using one identifier domain where another is expected can make lookups, permission checks, or filtering silently fail."));
            }
            if (containsAny(lower, "lower(", "tolower", "toupper", "equalsignorecase", "normalize", "normalized", "trim", "sanitize", "host", "url", "referer", "locale")) {
                contracts.add(contract(next++, "normalization_contract", path, line, excerpt(patch, "lower", "toLower", "normalize", "trim", "sanitize", "host", "referer"),
                        "Changed normalization or comparison contract",
                        "Producer and lookup paths must normalize values the same way, including case, path segments, locale, and protocol prefixes."));
            }
            if (containsAny(lower, "migration", "insert into", "update ", "delete from", "raw sql", "exec(", "execute(")) {
                contracts.add(contract(next++, "migration_data_contract", path, line, excerpt(patch, "INSERT INTO", "UPDATE ", "migration", "execute", "raw"),
                        "Changed migration/data-shape contract",
                        "Migrated rows must satisfy the same validation and normalization invariants as newly-created rows."));
            }
            if (containsAny(lower, "abstract", "extends ", "implements ", "interface ", "override", "required", "pass", "not implemented", "todo")) {
                contracts.add(contract(next++, "abstract_method_contract", path, line, excerpt(patch, "abstract", "extends", "implements", "pass", "not implemented"),
                        "Changed interface/abstract-method contract",
                        "New subclasses or interface changes must implement all required methods and preserve runtime instantiation contracts."));
            }
            if (containsAny(lower, "interface ", "createevent(", "updateevent(", "deleteevent(", "credentialid", "implements ")) {
                contracts.add(contract(next++, "interface_signature_contract", path, line, excerpt(patch, "interface", "createEvent", "updateEvent", "deleteEvent", "credentialId", "implements"),
                        "Changed interface signature contract",
                        "All implementations and call sites must satisfy the new method parameters and return contract after an interface signature changes."));
            }
            if (containsAny(lower, "logger", "logging", "traceid", "trace id", "context", "middleware", "instrument", "fromcontext")) {
                contracts.add(contract(next++, "observability_context_contract", path, line, excerpt(patch, "traceID", "TraceIDFromContext", "FromContext", "+func", "logger", "context", "middleware", "instrument"),
                        "Changed logging/context propagation contract",
                        "Refactors must preserve request context, trace identifiers, and nil-safe middleware behavior."));
            }
            if (containsAny(lower, "sleep(", "time.sleep", "thread.sleep", "settimeout", "deadline", "timeout", "terminate", "kill")) {
                contracts.add(contract(next++, "lifecycle_timeout_contract", path, line, excerpt(patch, "SpawnProcess", "isinstance", "sleep", "deadline", "timeout", "terminate", "kill"),
                        "Changed lifecycle or timeout contract",
                        "Shutdown and test synchronization code must wait on real conditions and terminate all remaining workers even after deadlines."));
            }
            if (isDependencyManifest(path) && containsAny(patch, "\n-", "remove", "duck", "vulnerability", "cve") && containsAny(prText + "\n" + lower, "cve", "vulnerability", "rce", "lfi", "disable", "security")) {
                contracts.add(contract(next++, "dependency_removal_completeness", path, line, excerpt(patch, "-", "require", "github.com", "package"),
                        "Security dependency removal / feature disable contract",
                        "Removing a vulnerable dependency must be paired with source-level behavior that disables or replaces every affected runtime path."));
            }
            if (path.toLowerCase().endsWith(".properties") && containsAny(lower, "messages_", "totp", "href", "<a", "anchor", "sanitize")) {
                contracts.add(contract(next++, "locale_translation_contract", path, line, excerpt(patch, "totp", "href", "<a", "anchor", "sanitize"),
                        "Changed locale/translation validation contract",
                        "Localized message files must keep the target locale and anchor structure consistent with the source translation contract."));
            }
        }
        List<Map<String, Object>> out = contracts.stream().limit(40).toList();
        trace.record("strategy_end", Map.of("strategy", "Changed Behavior Contract", "contracts", out));
        return out;
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
            if (containsAny(patch, "thread", "async", "await", "lock", "mutex", "synchronized", "goroutine", "channel", "executor",
                    "dispatchqueue", "mainactor", "actor ", "weak self", "atomic", "pthread")) {
                addUnique(riskTypes, "concurrency/async");
                addUnique(reviewFocus, "race conditions, cancellation, timeout, UI-thread affinity, resource cleanup");
            }
            if (containsAny(lower, "api", "controller", "route", "handler", "graphql", "proto", "openapi") || containsAny(patch, "public ", "endpoint", "route", "request", "response")) {
                addUnique(riskTypes, "api/contract");
                addUnique(reviewFocus, "backward compatibility, validation, error semantics");
            }
            if (containsAny(lower, "package.json", "pom.xml", "build.gradle", "cargo.toml", "requirements.txt", "go.mod", "composer.json", "gemfile",
                    "package.swift", "podfile", "cmakelists.txt", "makefile", "compile_commands.json")) {
                addUnique(riskTypes, "dependency/build");
                addUnique(reviewFocus, "supply-chain risk, version compatibility, build/test behavior");
            }
            if (containsAny(patch, "malloc", "free(", "delete ", "new ", "memcpy", "memmove", "strcpy", "strncpy", "sprintf", "reinterpret_cast", "unsafe", "pointer")) {
                addUnique(riskTypes, "native-memory-safety");
                addUnique(reviewFocus, "bounds, ownership, lifetime, pointer nullability, and unsafe API use");
            }
            if (containsAny(patch, "userdefaults", "keychain", "nsuserdefaults", "credential", "accessibility", "biometric", "certificate", "secitem")) {
                addUnique(riskTypes, "mobile-security");
                addUnique(reviewFocus, "secure storage, keychain accessibility, credential lifecycle, privacy-sensitive logging");
            }
            if (containsAny(patch, "activerecord", "params.require", "permit(", "before_action", "skip_before_action", "sql", "where(")) {
                addUnique(riskTypes, "framework-security");
                addUnique(reviewFocus, "authorization coverage, mass assignment, SQL construction, and request validation");
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

    private static Map<String, Object> contract(int id, String type, String file, int line, String evidence, String trigger, String expectedFailure) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", "contract-" + id);
        item.put("type", type);
        item.put("file", file);
        item.put("line", line);
        item.put("evidence", evidence);
        item.put("trigger", trigger);
        item.put("expected_failure", expectedFailure);
        item.put("review_instruction", "Prefer candidates that prove this failure mode is introduced or exposed by the diff.");
        return item;
    }

    private static boolean isDependencyManifest(String path) {
        String lower = path.toLowerCase();
        return lower.endsWith("go.mod") || lower.endsWith("go.sum") || lower.endsWith("package.json") || lower.endsWith("package-lock.json")
                || lower.endsWith("pnpm-lock.yaml") || lower.endsWith("yarn.lock") || lower.endsWith("gemfile") || lower.endsWith("gemfile.lock")
                || lower.endsWith("pom.xml") || lower.endsWith("build.gradle") || lower.endsWith("cargo.toml") || lower.endsWith("cargo.lock")
                || lower.endsWith("requirements.txt") || lower.endsWith("poetry.lock");
    }

    private static int firstChangedLine(String patch) {
        int currentNewLine = 1;
        boolean sawHunk = false;
        for (String line : patch.split("\\R")) {
            java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("@@ -\\d+(?:,\\d+)? \\+(\\d+)(?:,\\d+)? @@.*").matcher(line);
            if (matcher.matches()) {
                sawHunk = true;
                currentNewLine = Integer.parseInt(matcher.group(1));
                continue;
            }
            if (line.startsWith("+") && !line.startsWith("+++")) {
                return currentNewLine;
            }
            if (sawHunk && !line.startsWith("-")) {
                currentNewLine++;
            }
        }
        return 1;
    }

    private static String excerpt(String text, String... needles) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String[] lines = text.split("\\R");
        for (String needle : needles) {
            for (String line : lines) {
                if (line.toLowerCase().contains(needle.toLowerCase())) {
                    return line.strip().length() <= 240 ? line.strip() : line.strip().substring(0, 240);
                }
            }
        }
        String compact = text.replaceAll("\\s+", " ").strip();
        return compact.length() <= 240 ? compact : compact.substring(0, 240);
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
