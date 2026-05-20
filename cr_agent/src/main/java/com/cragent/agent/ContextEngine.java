package com.cragent.agent;

import com.cragent.config.Settings;
import com.cragent.trace.TraceRecorder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public final class ContextEngine {
    private static final int DEFAULT_PACK_CHARS = 36_000;
    private static final int DEFAULT_REPO_PACK_CHARS = 48_000;

    private ContextEngine() {
    }

    public static Map<String, Object> forDiff(Settings settings, Map<String, Object> triage,
                                              Map<String, Object> analysis, TraceRecorder trace) {
        trace.record("context_engine_start", Map.of("mode", "diff"));
        List<Map<String, Object>> changedFiles = listOfMaps(triage.get("changed_files"));
        List<Map<String, Object>> index = diffIndex(changedFiles, analysis);
        List<Map<String, Object>> candidates = new ArrayList<>();
        AtomicInteger nextId = new AtomicInteger(1);
        for (Map<String, Object> file : changedFiles) {
            addChangedFileContext(candidates, nextId, file, analysis);
        }
        addExpansionContext(candidates, nextId, analysis);
        addLspContext(candidates, nextId, analysis.get("lsp_context"), changedPaths(changedFiles));
        addStaticContext(candidates, nextId, analysis);
        addRiskProbeContext(candidates, nextId, analysis);
        addMemoryContext(candidates, nextId, analysis);
        Map<String, Object> pack = pack(candidates, Math.max(DEFAULT_PACK_CHARS, settings.maxToolResultChars() * 2), false, settings, analysis, trace, "diff");
        Map<String, Object> result = result("diff", index, candidates, pack);
        trace.record("context_engine_end", Map.of("mode", "diff", "summary", result.get("context_summary")));
        return result;
    }

    public static Map<String, Object> forRepoAudit(Settings settings, RepoAuditIndexer.AuditIndex index,
                                                   List<Map<String, Object>> manifest, List<Map<String, Object>> checks,
                                                   Map<String, Object> risk, Map<String, Object> lspContext,
                                                   Map<String, Object> sharedAnalysis, TraceRecorder trace) {
        trace.record("context_engine_start", Map.of("mode", "repo_audit"));
        List<Map<String, Object>> contextIndex = repoIndex(index, manifest, lspContext);
        List<Map<String, Object>> candidates = new ArrayList<>();
        AtomicInteger nextId = new AtomicInteger(1);
        addRepoOverview(candidates, nextId, index, manifest, risk);
        addRepoRulesAndConfigs(candidates, nextId, index);
        addLspContext(candidates, nextId, lspContext, Set.of());
        addStaticList(candidates, nextId, checks);
        addExpansionContext(candidates, nextId, sharedAnalysis);
        Map<String, Object> pack = pack(candidates, DEFAULT_REPO_PACK_CHARS, false, settings, sharedAnalysis, trace, "repo_audit");
        Map<String, Object> result = result("repo_audit", contextIndex, candidates, pack);
        trace.record("context_engine_end", Map.of("mode", "repo_audit", "summary", result.get("context_summary")));
        return result;
    }

    public static Map<String, Object> forRepoBatch(Settings settings, List<RepoAuditIndexer.AuditSlice> batch,
                                                   List<Map<String, Object>> manifest, List<Map<String, Object>> checks,
                                                   Map<String, Object> risk, Map<String, Object> lspContext,
                                                   Map<String, Object> sharedAnalysis, TraceRecorder trace) {
        List<String> paths = batch.stream().map(RepoAuditIndexer.AuditSlice::path).distinct().toList();
        trace.record("context_engine_start", Map.of("mode", "repo_batch", "paths", paths));
        List<Map<String, Object>> contextIndex = batchIndex(batch, manifest, lspContext);
        List<Map<String, Object>> candidates = new ArrayList<>();
        AtomicInteger nextId = new AtomicInteger(1);
        for (RepoAuditIndexer.AuditSlice slice : batch) {
            addItem(candidates, nextId, "batch_slice", slice.path(), slice.startLine(), slice.endLine(),
                    "Current full-repo audit slice under review.",
                    "source", slice.content(), scoreForPath(slice.path(), risk, 0.78));
        }
        addLspContext(candidates, nextId, lspContext, Set.copyOf(paths));
        addStaticList(candidates, nextId, checks);
        addExpansionContext(candidates, nextId, sharedAnalysis);
        Map<String, Object> pack = pack(candidates, DEFAULT_PACK_CHARS, true, settings, sharedAnalysis, trace, "repo_batch");
        Map<String, Object> result = result("repo_batch", contextIndex, candidates, pack);
        trace.record("context_engine_end", Map.of("mode", "repo_batch", "summary", result.get("context_summary")));
        return result;
    }

    private static List<Map<String, Object>> diffIndex(List<Map<String, Object>> changedFiles, Map<String, Object> analysis) {
        Map<String, Object> risk = mapOf(analysis.get("risk_model"));
        return changedFiles.stream().map(file -> {
            String path = filePath(file);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("path", path);
            item.put("language", language(path));
            item.put("role", role(path));
            item.put("changed", true);
            item.put("risk_rank", riskRank(path, risk));
            item.put("additions", intValue(file.get("additions")));
            item.put("deletions", intValue(file.get("deletions")));
            item.put("changed_lines", changedLinesFromPatch(patch(file)).stream().limit(24).toList());
            item.put("symbols", symbolsForPath(analysis.get("lsp_context"), path));
            return item;
        }).sorted(Comparator.comparingInt(item -> intValue(item.get("risk_rank")))).toList();
    }

    private static List<Map<String, Object>> repoIndex(RepoAuditIndexer.AuditIndex index, List<Map<String, Object>> manifest, Map<String, Object> lspContext) {
        Map<String, List<Map<String, Object>>> symbolsByPath = symbolsByPath(lspContext);
        return manifest.stream().map(file -> {
            String path = String.valueOf(file.get("path"));
            Map<String, Object> item = new LinkedHashMap<>(file);
            item.put("role", role(path));
            item.put("risk_rank", riskRank(path, Map.of()));
            item.put("symbols", symbolsByPath.getOrDefault(path, List.of()).stream().limit(20).toList());
            return item;
        }).sorted(Comparator.comparingInt(item -> intValue(item.get("risk_rank")))).toList();
    }

    private static List<Map<String, Object>> batchIndex(List<RepoAuditIndexer.AuditSlice> batch, List<Map<String, Object>> manifest, Map<String, Object> lspContext) {
        Set<String> paths = batch.stream().map(RepoAuditIndexer.AuditSlice::path).collect(Collectors.toSet());
        List<Map<String, Object>> base = repoIndex(null, manifest.stream()
                .filter(item -> paths.contains(String.valueOf(item.get("path"))))
                .toList(), lspContext);
        Map<String, List<Map<String, Object>>> slicesByPath = batch.stream()
                .map(slice -> Map.<String, Object>of("path", slice.path(), "start_line", slice.startLine(), "end_line", slice.endLine()))
                .collect(Collectors.groupingBy(item -> String.valueOf(item.get("path")), LinkedHashMap::new, Collectors.toList()));
        for (Map<String, Object> item : base) {
            item.put("slices", slicesByPath.getOrDefault(String.valueOf(item.get("path")), List.of()));
        }
        return base;
    }

    private static void addChangedFileContext(List<Map<String, Object>> out, AtomicInteger nextId, Map<String, Object> file,
                                              Map<String, Object> analysis) {
        String path = filePath(file);
        String patch = patch(file);
        if (!patch.isBlank()) {
            addItem(out, nextId, "changed_hunk", path, firstChangedLine(patch), null,
                    "Primary changed diff hunk. Review findings should normally cite this or a context item connected to it.",
                    "diff", patch, 0.95);
        }
        for (Map<String, Object> probe : listOfMaps(analysis.get("risk_probes"))) {
            if (path.equals(String.valueOf(probe.get("file")))) {
                addItem(out, nextId, "risk_probe", path, intValue(probe.get("line")), null,
                        String.valueOf(probe.getOrDefault("rationale", "Risk probe for changed behavior.")),
                        "risk", String.valueOf(probe.getOrDefault("evidence", "")), 0.82);
            }
        }
    }

    private static void addExpansionContext(List<Map<String, Object>> out, AtomicInteger nextId, Map<String, Object> analysis) {
        Map<String, Object> expansion = mapOf(analysis.get("context_expansion"));
        for (Map<String, Object> item : listOfMaps(expansion.get("surrounding_contexts"))) {
            String path = String.valueOf(item.getOrDefault("filename", item.get("path")));
            addItem(out, nextId, "surrounding_context", path, null, null,
                    "Nearby source lines around a changed line.",
                    "source", item.get("context"), 0.76);
        }
        for (Map<String, Object> item : listOfMaps(expansion.get("related_tests"))) {
            String path = String.valueOf(item.getOrDefault("filename", item.get("path")));
            addItem(out, nextId, "related_tests", path, null, null,
                    "Related tests that may cover the changed behavior.",
                    "tests", item.get("tests"), 0.72);
        }
        for (Map<String, Object> item : listOfMaps(expansion.get("security_file_contents"))) {
            String path = String.valueOf(item.getOrDefault("filename", item.get("path")));
            addItem(out, nextId, "security_file_content", path, null, null,
                    "Security-sensitive file content loaded for trust-boundary analysis.",
                    "source", item.get("content"), 0.80);
        }
        Object manifests = expansion.get("dependency_manifests");
        if (manifests != null) {
            addItem(out, nextId, "dependency_manifests", "dependency-manifests", null, null,
                    "Dependency/build manifests for supply-chain and compatibility review.",
                    "config", manifests, 0.62);
        }
        Object sensitivePaths = expansion.get("sensitive_paths");
        if (sensitivePaths != null) {
            addItem(out, nextId, "sensitive_paths", "sensitive-paths", null, null,
                    "Security-sensitive paths discovered in the repository.",
                    "index", sensitivePaths, 0.58);
        }
    }

    private static void addLspContext(List<Map<String, Object>> out, AtomicInteger nextId, Object rawLsp, Set<String> preferredPaths) {
        Map<String, Object> lsp = mapOf(rawLsp);
        List<Map<String, Object>> symbols = new ArrayList<>();
        symbols.addAll(listOfMaps(lsp.get("symbols")));
        symbols.addAll(listOfMaps(lsp.get("symbols_preview")));
        for (Map<String, Object> symbol : dedupeSymbols(symbols)) {
            String path = String.valueOf(symbol.getOrDefault("path", symbol.getOrDefault("file", "")));
            if (!preferredPaths.isEmpty() && !preferredPaths.contains(path)) {
                continue;
            }
            addItem(out, nextId, "lsp_symbol", path, intValue(symbol.get("line")), null,
                    "LSP symbol boundary/definition available for context-aware review.",
                    "lsp", symbol, preferredPaths.contains(path) ? 0.74 : 0.54);
        }
        Object diagnostics = lsp.get("diagnostics");
        if (diagnostics != null) {
            addItem(out, nextId, "lsp_diagnostics", "lsp-diagnostics", null, null,
                    "LSP diagnostics collected from available language servers.",
                    "lsp", diagnostics, 0.70);
        }
    }

    private static void addStaticContext(List<Map<String, Object>> out, AtomicInteger nextId, Map<String, Object> analysis) {
        addStaticList(out, nextId, listOfMaps(analysis.get("static_checks")));
    }

    private static void addStaticList(List<Map<String, Object>> out, AtomicInteger nextId, List<Map<String, Object>> checks) {
        for (Map<String, Object> check : checks) {
            addItem(out, nextId, "static_check", String.valueOf(check.getOrDefault("command", "static-check")), null, null,
                    "Read-only static check result. Use as supporting evidence only when correlated with code.",
                    "tool", check, 0.66);
        }
    }

    private static void addRiskProbeContext(List<Map<String, Object>> out, AtomicInteger nextId, Map<String, Object> analysis) {
        for (Map<String, Object> probe : listOfMaps(analysis.get("risk_probes"))) {
            addItem(out, nextId, "risk_probe", String.valueOf(probe.getOrDefault("file", "risk-probe")),
                    intValue(probe.get("line")), null,
                    String.valueOf(probe.getOrDefault("rationale", "Risk probe generated from changed code.")),
                    "risk", probe, 0.78);
        }
    }

    private static void addMemoryContext(List<Map<String, Object>> out, AtomicInteger nextId, Map<String, Object> analysis) {
        Object memory = analysis.get("memory");
        if (memory != null) {
            addItem(out, nextId, "memory_rules", "memory", null, null,
                    "Repo/developer memory, false-positive rules, and historical review patterns.",
                    "memory", memory, 0.55);
        }
    }

    private static void addRepoOverview(List<Map<String, Object>> out, AtomicInteger nextId, RepoAuditIndexer.AuditIndex index,
                                        List<Map<String, Object>> manifest, Map<String, Object> risk) {
        Map<String, Object> overview = new LinkedHashMap<>();
        overview.put("stack", index.stack());
        overview.put("directories", index.directories());
        overview.put("files", manifest.size());
        overview.put("risk_model", risk);
        overview.put("sensitive_files", index.files().stream().filter(RepoAuditIndexer.AuditFile::sensitive).map(RepoAuditIndexer.AuditFile::path).limit(80).toList());
        overview.put("test_files", index.files().stream().filter(RepoAuditIndexer.AuditFile::test).map(RepoAuditIndexer.AuditFile::path).limit(80).toList());
        addItem(out, nextId, "repo_overview", "repository", null, null,
                "Repository-level orientation before progressive file review.",
                "index", overview, 0.86);
    }

    private static void addRepoRulesAndConfigs(List<Map<String, Object>> out, AtomicInteger nextId, RepoAuditIndexer.AuditIndex index) {
        List<Map<String, Object>> configs = index.files().stream()
                .filter(file -> file.config() || instructionFile(file.path()))
                .limit(80)
                .map(file -> Map.<String, Object>of(
                        "path", file.path(),
                        "language", file.language(),
                        "preview", truncate(file.content(), 2600)
                ))
                .toList();
        if (!configs.isEmpty()) {
            addItem(out, nextId, "repo_rules_and_configs", "repository-rules-configs", null, null,
                    "Project rules, build files, CI files, and configuration that shape review expectations.",
                    "config", configs, 0.73);
        }
    }

    private static Map<String, Object> pack(List<Map<String, Object>> candidates, int charBudget, boolean keepBatchSlices,
                                            Settings settings, Map<String, Object> analysis, TraceRecorder trace, String mode) {
        Map<String, List<Map<String, Object>>> channels = retrievalChannels(candidates, analysis);
        for (Map.Entry<String, List<Map<String, Object>>> entry : channels.entrySet()) {
            trace.record("context_retrieval_channel", Map.of(
                    "mode", mode,
                    "channel", entry.getKey(),
                    "count", entry.getValue().size(),
                    "top_item_ids", entry.getValue().stream().limit(12).map(item -> item.get("id")).toList()
            ));
        }
        List<Map<String, Object>> ordered = rrfFuse(candidates, channels, Math.max(1, settings.contextRrfK()), keepBatchSlices);
        trace.record("context_rrf_fusion", Map.of(
                "mode", mode,
                "rrf_k", settings.contextRrfK(),
                "candidate_items", candidates.size(),
                "top_items", ordered.stream().limit(20).map(ContextEngine::rrfSummary).toList()
        ));
        List<Map<String, Object>> selected = new ArrayList<>();
        List<Map<String, Object>> compressed = new ArrayList<>();
        int used = 0;
        for (Map<String, Object> item : ordered) {
            int size = intValue(item.get("chars"));
            boolean mandatory = keepBatchSlices && "batch_slice".equals(item.get("type"));
            if (mandatory || (selected.size() < Math.max(1, settings.contextMaxItems()) && (used + size <= charBudget || selected.size() < 4))) {
                item.put("pack_decision", mandatory ? "selected_mandatory" : "selected_rrf");
                selected.add(item);
                used += Math.min(size, charBudget);
            } else {
                item.put("pack_decision", "compressed_budget");
                compressed.add(summaryItem(item));
            }
        }
        Map<String, Object> ledger = new LinkedHashMap<>();
        ledger.put("candidate_items", candidates.size());
        ledger.put("selected_items", selected.size());
        ledger.put("compressed_items", compressed.size());
        ledger.put("char_budget", charBudget);
        ledger.put("max_items", settings.contextMaxItems());
        ledger.put("rrf_k", settings.contextRrfK());
        ledger.put("channel_counts", channels.entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().size(), (a, b) -> a, LinkedHashMap::new)));
        ledger.put("rrf_top_items", ordered.stream().limit(20).map(ContextEngine::rrfSummary).toList());
        ledger.put("selected_chars", selected.stream().mapToInt(item -> intValue(item.get("chars"))).sum());
        ledger.put("selected_item_ids", selected.stream().map(item -> item.get("id")).toList());
        ledger.put("compressed_item_ids", compressed.stream().map(item -> item.get("id")).toList());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", selected);
        out.put("compressed_items", compressed.stream().limit(80).toList());
        out.put("ledger", ledger);
        return out;
    }

    private static Map<String, List<Map<String, Object>>> retrievalChannels(List<Map<String, Object>> candidates, Map<String, Object> analysis) {
        Set<String> queryTerms = queryTerms(analysis);
        Map<String, List<Map<String, Object>>> channels = new LinkedHashMap<>();
        channels.put("lexical_diff_terms", ranked(candidates, item -> lexicalScore(item, queryTerms)));
        channels.put("path_risk", ranked(candidates, ContextEngine::pathRiskScore));
        channels.put("symbol_lsp", ranked(candidates, ContextEngine::symbolScore));
        channels.put("test_relation", ranked(candidates, ContextEngine::testRelationScore));
        channels.put("config_dependency", ranked(candidates, ContextEngine::configDependencyScore));
        channels.put("memory_pattern", ranked(candidates, ContextEngine::memoryPatternScore));
        channels.put("static_signal", ranked(candidates, ContextEngine::staticSignalScore));
        channels.put("risk_probe", ranked(candidates, ContextEngine::riskProbeScore));
        return channels;
    }

    private interface ScoreFn {
        double score(Map<String, Object> item);
    }

    private static List<Map<String, Object>> ranked(List<Map<String, Object>> candidates, ScoreFn scoreFn) {
        return candidates.stream()
                .map(item -> {
                    Map<String, Object> scored = new LinkedHashMap<>(item);
                    scored.put("_channel_score", scoreFn.score(item));
                    return scored;
                })
                .filter(item -> doubleValue(item.get("_channel_score")) > 0.0)
                .sorted(Comparator.comparingDouble((Map<String, Object> item) -> -doubleValue(item.get("_channel_score")))
                        .thenComparing(item -> String.valueOf(item.get("id"))))
                .toList();
    }

    private static List<Map<String, Object>> rrfFuse(List<Map<String, Object>> candidates, Map<String, List<Map<String, Object>>> channels,
                                                     int k, boolean keepBatchSlices) {
        Map<String, Map<String, Object>> byId = candidates.stream().collect(Collectors.toMap(
                item -> String.valueOf(item.get("id")),
                item -> item,
                (a, b) -> a,
                LinkedHashMap::new
        ));
        Map<String, Double> scores = new HashMap<>();
        Map<String, List<String>> retrievalChannels = new HashMap<>();
        Map<String, Map<String, Integer>> channelRanks = new HashMap<>();
        for (Map.Entry<String, List<Map<String, Object>>> entry : channels.entrySet()) {
            int rank = 0;
            for (Map<String, Object> item : entry.getValue()) {
                rank++;
                String id = String.valueOf(item.get("id"));
                scores.merge(id, 1.0 / (k + rank), Double::sum);
                retrievalChannels.computeIfAbsent(id, ignored -> new ArrayList<>()).add(entry.getKey());
                channelRanks.computeIfAbsent(id, ignored -> new LinkedHashMap<>()).put(entry.getKey(), rank);
            }
        }
        for (Map<String, Object> item : candidates) {
            String id = String.valueOf(item.get("id"));
            if (keepBatchSlices && "batch_slice".equals(item.get("type"))) {
                scores.merge(id, 1.0, Double::sum);
                retrievalChannels.computeIfAbsent(id, ignored -> new ArrayList<>()).add("mandatory_batch_slice");
                channelRanks.computeIfAbsent(id, ignored -> new LinkedHashMap<>()).put("mandatory_batch_slice", 1);
            }
        }
        List<Map<String, Object>> fused = new ArrayList<>();
        for (Map.Entry<String, Map<String, Object>> entry : byId.entrySet()) {
            Map<String, Object> item = new LinkedHashMap<>(entry.getValue());
            double rrf = scores.getOrDefault(entry.getKey(), 0.0001 + doubleValue(item.get("score")) / 10000.0);
            List<String> itemChannels = retrievalChannels.getOrDefault(entry.getKey(), List.of("base_score"));
            item.put("rrf_score", rrf);
            item.put("retrieval_channels", itemChannels);
            item.put("channel_ranks", channelRanks.getOrDefault(entry.getKey(), Map.of()));
            item.put("why_selected", whySelected(itemChannels, item));
            fused.add(item);
        }
        return fused.stream()
                .sorted(Comparator.comparingDouble((Map<String, Object> item) -> -doubleValue(item.get("rrf_score")))
                        .thenComparing(Comparator.comparingDouble((Map<String, Object> item) -> -doubleValue(item.get("score"))))
                        .thenComparing(item -> String.valueOf(item.get("id"))))
                .toList();
    }

    private static Map<String, Object> rrfSummary(Map<String, Object> item) {
        return Map.of(
                "id", item.get("id"),
                "type", item.get("type"),
                "path", item.get("path"),
                "rrf_score", item.getOrDefault("rrf_score", 0.0),
                "channels", item.getOrDefault("retrieval_channels", List.of())
        );
    }

    private static String whySelected(List<String> channels, Map<String, Object> item) {
        if (channels.contains("mandatory_batch_slice")) return "Current repository audit batch slice is mandatory context.";
        if (channels.contains("risk_probe")) return "Risk probe channel matched changed behavior or generated probe.";
        if (channels.contains("lexical_diff_terms")) return "Lexical query terms matched context content or path.";
        if (channels.contains("symbol_lsp")) return "LSP symbol/diagnostic channel linked this item to code structure.";
        if (channels.contains("test_relation")) return "Test relation channel linked this item to changed behavior coverage.";
        if (channels.contains("static_signal")) return "Static/LSP diagnostic signal supplied supporting evidence.";
        if (channels.contains("config_dependency")) return "Configuration/dependency channel matched build or contract context.";
        if (channels.contains("memory_pattern")) return "Memory channel matched historical review or false-positive context.";
        return String.valueOf(item.getOrDefault("reason", "Selected by base context score."));
    }

    private static Set<String> queryTerms(Map<String, Object> analysis) {
        Set<String> terms = new HashSet<>();
        addTerms(terms, analysis.get("context_scout"));
        addTerms(terms, analysis.get("risk_model"));
        addTerms(terms, analysis.get("regression_test_reasoning"));
        addTerms(terms, analysis.get("risk_probes"));
        addTerms(terms, analysis.get("repo_manifest"));
        return terms.stream().filter(term -> term.length() >= 3).limit(300).collect(Collectors.toCollection(java.util.LinkedHashSet::new));
    }

    @SuppressWarnings("unchecked")
    private static void addTerms(Set<String> terms, Object value) {
        if (value == null) {
            return;
        }
        if (value instanceof Map<?, ?> map) {
            for (Object nested : map.values()) addTerms(terms, nested);
        } else if (value instanceof List<?> list) {
            for (Object nested : list) addTerms(terms, nested);
        } else if (value instanceof String text) {
            for (String token : text.toLowerCase(Locale.ROOT).split("[^a-zA-Z0-9_.$/-]+")) {
                if (token.length() >= 3 && !Set.of("public", "private", "return", "class", "function", "import", "from", "with", "this", "that", "true", "false").contains(token)) {
                    terms.add(token);
                }
            }
        }
    }

    private static double lexicalScore(Map<String, Object> item, Set<String> queryTerms) {
        if (queryTerms.isEmpty()) return doubleValue(item.get("score")) * 0.2;
        String haystack = (String.valueOf(item.get("path")) + "\n" + item.get("type") + "\n" + item.get("content")).toLowerCase(Locale.ROOT);
        int matches = 0;
        for (String term : queryTerms) {
            if (haystack.contains(term)) matches++;
        }
        return matches == 0 ? 0.0 : matches + doubleValue(item.get("score")) * 0.1;
    }

    private static double pathRiskScore(Map<String, Object> item) {
        String path = String.valueOf(item.get("path"));
        int rank = riskRank(path, Map.of());
        return switch (rank) {
            case 0 -> 5.0;
            case 1 -> 3.0;
            case 2 -> 2.0;
            default -> 0.3;
        };
    }

    private static double symbolScore(Map<String, Object> item) {
        String type = String.valueOf(item.get("type"));
        if (type.contains("lsp")) return 4.0;
        String content = String.valueOf(item.get("content")).toLowerCase(Locale.ROOT);
        return containsAny(content, "symbol", "definition", "references", "hover", "diagnostic") ? 1.5 : 0.0;
    }

    private static double testRelationScore(Map<String, Object> item) {
        String type = String.valueOf(item.get("type"));
        String path = String.valueOf(item.get("path")).toLowerCase(Locale.ROOT);
        if ("related_tests".equals(type)) return 4.0;
        if (containsAny(path, "test", "spec")) return 2.5;
        return 0.0;
    }

    private static double configDependencyScore(Map<String, Object> item) {
        String type = String.valueOf(item.get("type"));
        String path = String.valueOf(item.get("path")).toLowerCase(Locale.ROOT);
        if (containsAny(type, "dependency", "config", "repo_rules")) return 4.0;
        if (containsAny(path, "package.json", "pom.xml", "build.gradle", "cargo.toml", "go.mod", "gemfile", "podfile", "dockerfile", ".github/workflows")) return 2.5;
        return 0.0;
    }

    private static double memoryPatternScore(Map<String, Object> item) {
        String type = String.valueOf(item.get("type"));
        return type.contains("memory") ? 3.0 : 0.0;
    }

    private static double staticSignalScore(Map<String, Object> item) {
        String type = String.valueOf(item.get("type"));
        String source = String.valueOf(item.get("source"));
        if (type.contains("static") || type.contains("diagnostic") || source.equals("tool")) return 3.5;
        return 0.0;
    }

    private static double riskProbeScore(Map<String, Object> item) {
        String type = String.valueOf(item.get("type"));
        return type.contains("risk_probe") ? 5.0 : 0.0;
    }

    private static Map<String, Object> result(String mode, List<Map<String, Object>> index,
                                              List<Map<String, Object>> candidates, Map<String, Object> pack) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("mode", mode);
        summary.put("indexed_files", index.stream().map(item -> item.get("path")).distinct().count());
        summary.put("candidate_items", candidates.size());
        summary.put("selected_items", mapOf(pack.get("ledger")).getOrDefault("selected_items", 0));
        summary.put("compressed_items", mapOf(pack.get("ledger")).getOrDefault("compressed_items", 0));
        summary.put("top_context_types", candidates.stream()
                .collect(Collectors.groupingBy(item -> String.valueOf(item.get("type")), LinkedHashMap::new, Collectors.counting())));
        Object ledger = pack.get("ledger");
        if (ledger instanceof Map<?, ?> ledgerMap) {
            summary.put("top_retrieval_channels", ledgerMap.containsKey("channel_counts") ? ledgerMap.get("channel_counts") : Map.of());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("mode", mode);
        out.put("context_summary", summary);
        out.put("context_index", index.stream().limit(240).toList());
        out.put("context_pack", pack);
        out.put("context_ledger", pack.get("ledger"));
        return out;
    }

    private static void addItem(List<Map<String, Object>> out, AtomicInteger nextId, String type, String path,
                                Integer startLine, Integer endLine, String reason, String source, Object content, double score) {
        String text = stringify(content);
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", "ctx-" + nextId.getAndIncrement());
        item.put("type", type);
        item.put("source", source);
        item.put("path", path == null || path.isBlank() ? "unknown" : path);
        item.put("start_line", startLine);
        item.put("end_line", endLine);
        item.put("reason", reason);
        item.put("score", Math.max(0.0, Math.min(1.0, score)));
        item.put("chars", text.length());
        item.put("content", truncate(text, 12_000));
        out.add(item);
    }

    private static Map<String, Object> summaryItem(Map<String, Object> item) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("id", item.get("id"));
        summary.put("type", item.get("type"));
        summary.put("path", item.get("path"));
        summary.put("reason", item.get("reason"));
        summary.put("score", item.get("score"));
        summary.put("summary", firstLine(String.valueOf(item.getOrDefault("content", "")), 240));
        return summary;
    }

    private static Map<String, List<Map<String, Object>>> symbolsByPath(Object rawLsp) {
        List<Map<String, Object>> symbols = new ArrayList<>();
        Map<String, Object> lsp = mapOf(rawLsp);
        symbols.addAll(listOfMaps(lsp.get("symbols")));
        symbols.addAll(listOfMaps(lsp.get("symbols_preview")));
        return dedupeSymbols(symbols).stream()
                .collect(Collectors.groupingBy(item -> String.valueOf(item.getOrDefault("path", item.getOrDefault("file", ""))),
                        LinkedHashMap::new, Collectors.toList()));
    }

    private static List<Map<String, Object>> symbolsForPath(Object rawLsp, String path) {
        return symbolsByPath(rawLsp).getOrDefault(path, List.of()).stream().limit(20).toList();
    }

    private static List<Map<String, Object>> dedupeSymbols(List<Map<String, Object>> symbols) {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        for (Map<String, Object> symbol : symbols) {
            String key = symbol.getOrDefault("path", symbol.getOrDefault("file", "")) + ":"
                    + symbol.getOrDefault("name", symbol.getOrDefault("symbol", "")) + ":"
                    + symbol.getOrDefault("line", "");
            if (!key.equals("::")) {
                out.putIfAbsent(key, symbol);
            }
        }
        return new ArrayList<>(out.values());
    }

    private static Set<String> changedPaths(List<Map<String, Object>> changedFiles) {
        return changedFiles.stream().map(ContextEngine::filePath).collect(Collectors.toSet());
    }

    private static Set<Integer> changedLinesFromPatch(String patch) {
        Set<Integer> lines = new java.util.LinkedHashSet<>();
        if (patch == null || patch.isBlank()) return lines;
        int currentNewLine = 0;
        boolean sawHunk = false;
        Pattern hunk = Pattern.compile("@@ -\\d+(?:,\\d+)? \\+(\\d+)(?:,\\d+)? @@.*");
        for (String line : patch.split("\\R")) {
            Matcher matcher = hunk.matcher(line);
            if (matcher.matches()) {
                sawHunk = true;
                currentNewLine = Integer.parseInt(matcher.group(1));
                continue;
            }
            if (!sawHunk) {
                if (line.startsWith("+") && !line.startsWith("+++")) lines.add(1);
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

    private static int firstChangedLine(String patch) {
        return changedLinesFromPatch(patch).stream().findFirst().orElse(1);
    }

    private static int riskRank(String path, Map<String, Object> risk) {
        String lower = path == null ? "" : path.toLowerCase(Locale.ROOT);
        if (containsAny(lower, "auth", "security", "token", "password", "secret", "crypto", "permission", "payment")) return 0;
        if (containsAny(lower, "migration", "schema", "controller", "route", "api", "proto", "openapi")) return 1;
        if (containsAny(lower, "package.json", "pom.xml", "build.gradle", "cargo.toml", "go.mod", "gemfile", "podfile", ".github/workflows")) return 2;
        if (containsAny(lower, "test", "spec")) return 4;
        List<String> riskTypes = listOfStrings(risk.get("risk_types"));
        if (riskTypes.stream().anyMatch(type -> type.contains("security") || type.contains("data"))) return 1;
        return 3;
    }

    private static double scoreForPath(String path, Map<String, Object> risk, double base) {
        int rank = riskRank(path, risk);
        return Math.min(1.0, base + Math.max(0, 3 - rank) * 0.04);
    }

    private static String role(String path) {
        String lower = path == null ? "" : path.toLowerCase(Locale.ROOT);
        if (containsAny(lower, "test", "spec")) return "test";
        if (containsAny(lower, "controller", "route", "handler", "api")) return "api";
        if (containsAny(lower, "auth", "security", "permission")) return "security";
        if (containsAny(lower, "migration", "schema")) return "data";
        if (containsAny(lower, "package.json", "pom.xml", "build.gradle", "cargo.toml", "go.mod", "gemfile", "podfile", "dockerfile", ".github/workflows")) return "config";
        return "source";
    }

    private static boolean instructionFile(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        return lower.endsWith("claude.md") || lower.endsWith(".cursorrules")
                || lower.endsWith("copilot-instructions.md") || lower.endsWith("contributing.md")
                || lower.endsWith("readme.md") || lower.endsWith("agents.md");
    }

    private static String language(String path) {
        String lower = path == null ? "" : path.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".java")) return "java";
        if (lower.endsWith(".kt")) return "kotlin";
        if (lower.endsWith(".js")) return "javascript";
        if (lower.endsWith(".ts") || lower.endsWith(".tsx")) return "typescript";
        if (lower.endsWith(".go")) return "go";
        if (lower.endsWith(".py")) return "python";
        if (lower.endsWith(".rs")) return "rust";
        if (lower.endsWith(".cs")) return "csharp";
        if (lower.endsWith(".php")) return "php";
        if (lower.endsWith(".swift")) return "swift";
        if (lower.endsWith(".rb")) return "ruby";
        if (lower.endsWith(".m") || lower.endsWith(".mm")) return "objective-c";
        if (lower.endsWith(".c") || lower.endsWith(".h")) return "c";
        if (lower.endsWith(".cc") || lower.endsWith(".cpp") || lower.endsWith(".hpp")) return "cpp";
        if (lower.endsWith(".yml") || lower.endsWith(".yaml")) return "yaml";
        if (lower.endsWith(".json")) return "json";
        return "text";
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> listOfMaps(Object value) {
        if (value instanceof List<?> list) {
            return list.stream().filter(Map.class::isInstance).map(item -> (Map<String, Object>) item).collect(Collectors.toList());
        }
        return List.of();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapOf(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private static List<String> listOfStrings(Object value) {
        if (value instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        return List.of();
    }

    private static String filePath(Map<String, Object> file) {
        return String.valueOf(file.getOrDefault("filename", file.getOrDefault("path", "")));
    }

    private static String patch(Map<String, Object> file) {
        return String.valueOf(file.getOrDefault("patch", ""));
    }

    private static int intValue(Object value) {
        if (value instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Exception e) {
            return 0;
        }
    }

    private static double doubleValue(Object value) {
        if (value instanceof Number n) return n.doubleValue();
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (Exception e) {
            return 0.0;
        }
    }

    private static boolean containsAny(String text, String... needles) {
        String lower = text == null ? "" : text.toLowerCase(Locale.ROOT);
        for (String needle : needles) {
            if (lower.contains(needle.toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }

    private static String stringify(Object value) {
        if (value == null) return "";
        if (value instanceof String text) return text;
        return String.valueOf(value);
    }

    private static String truncate(String value, int max) {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, Math.max(0, max - 80)) + "\n...[truncated " + (value.length() - max) + " chars]";
    }

    private static String firstLine(String value, int max) {
        String line = value == null ? "" : value.lines().findFirst().orElse("");
        return truncate(line, max).replace('\n', ' ');
    }
}
