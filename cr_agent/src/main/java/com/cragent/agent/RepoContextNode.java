package com.cragent.agent;

import com.cragent.cli.GitEnvironment;
import com.cragent.config.Settings;
import com.cragent.trace.TraceRecorder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class RepoContextNode {
    private final Settings settings;
    private final TraceRecorder trace;

    public RepoContextNode(Settings settings, TraceRecorder trace) {
        this.settings = settings;
        this.trace = trace;
    }

    public Map<String, Object> forDiff(String repo, Map<String, Object> triage, Path explicitRepoPath) {
        trace.record("strategy_start", Map.of("strategy", "Shared Repo Context", "mode", "diff"));
        Map<String, Object> result = new LinkedHashMap<>();
        Path repoPath = explicitRepoPath;
        if (repoPath == null) {
            repoPath = GitEnvironment.findLocalRepository(repo);
        }
        if (repoPath == null || !Files.exists(repoPath)) {
            result.put("status", "unavailable");
            result.put("reason", "No local repository is available for shared repo context.");
            result.put("lsp_context", Map.of("enabled", settings.lspEnabled(), "status", "unavailable"));
            result.put("static_checks", List.of());
            result.put("changed_manifest", List.of());
            trace.record("strategy_end", Map.of("strategy", "Shared Repo Context", "result", result));
            return result;
        }
        try {
            RepoAuditIndexer.AuditIndex fullIndex = new RepoAuditIndexer(settings).index(repoPath);
            Set<String> changed = listOfMaps(triage.get("changed_files")).stream()
                    .map(file -> String.valueOf(file.get("filename")))
                    .collect(Collectors.toCollection(HashSet::new));
            List<Map<String, Object>> changedManifest = fullIndex.files().stream()
                    .filter(file -> changed.contains(file.path()))
                    .map(RepoAuditIndexer.AuditFile::manifest)
                    .toList();
            Map<String, Object> workspace = settings.lspEnabled()
                    ? new LspAnalyzer(settings).workspaceContext(repoPath, fullIndex)
                    : Map.of("enabled", false, "status", "disabled");
            List<Map<String, Object>> checks = settings.repoAuditRunChecks()
                    ? new RepoStaticChecks().run(repoPath, fullIndex.stack())
                    : List.of();
            result.put("status", workspace.getOrDefault("status", "unknown"));
            result.put("repo_path", repoPath.toString());
            result.put("manifest_summary", Map.of(
                    "files_total", fullIndex.files().size() + fullIndex.skipped().size(),
                    "reviewable_files", fullIndex.files().size(),
                    "skipped", fullIndex.skipped().size(),
                    "stack", fullIndex.stack(),
                    "directories", fullIndex.directories()
            ));
            result.put("changed_manifest", changedManifest);
            result.put("static_checks", checks);
            result.put("lsp_context", Map.of(
                    "enabled", settings.lspEnabled(),
                    "status", workspace.getOrDefault("status", "unknown"),
                    "servers", workspace.getOrDefault("servers", List.of()),
                    "installations", workspace.getOrDefault("installations", List.of()),
                    "diagnostics", workspace.getOrDefault("diagnostics", Map.of()),
                    "errors", workspace.getOrDefault("errors", List.of()),
                    "symbols", filterSymbolsForChangedFiles(workspace.get("symbols_preview"), changed)
            ));
        } catch (Exception e) {
            result.put("status", "failed");
            result.put("error", e.getMessage());
            result.put("lsp_context", Map.of("enabled", settings.lspEnabled(), "status", "failed", "error", e.getMessage()));
            result.put("static_checks", List.of());
            result.put("changed_manifest", List.of());
        }
        trace.record("strategy_end", Map.of("strategy", "Shared Repo Context", "result", result));
        return result;
    }

    public Map<String, Object> lspOnlyForDiff(String repo, Map<String, Object> triage, Path explicitRepoPath) {
        Map<String, Object> repoContext = forDiff(repo, triage, explicitRepoPath);
        Object lsp = repoContext.get("lsp_context");
        return lsp instanceof Map<?, ?> map ? new LinkedHashMap<>(toStringObjectMap(map)) : new LinkedHashMap<>();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> filterSymbolsForChangedFiles(Object symbols, Set<String> changed) {
        if (!(symbols instanceof List<?> list) || changed.isEmpty()) {
            return List.of();
        }
        return list.stream()
                .filter(Map.class::isInstance)
                .map(item -> (Map<String, Object>) item)
                .filter(item -> changed.contains(String.valueOf(item.get("path"))))
                .limit(200)
                .toList();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> listOfMaps(Object value) {
        if (value instanceof List<?> list) {
            return list.stream().filter(Map.class::isInstance).map(item -> (Map<String, Object>) item).collect(Collectors.toList());
        }
        return List.of();
    }

    private static Map<String, Object> toStringObjectMap(Map<?, ?> map) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            out.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return out;
    }
}
