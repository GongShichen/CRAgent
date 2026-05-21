package com.cragent.tools;

import com.cragent.memory.MemoryStore;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.cragent.tools.ToolSchemas.*;

public class MemoryTools {
    private final MemoryStore store;
    private final boolean readEnabled;

    public MemoryTools(MemoryStore store) {
        this(store, true);
    }

    public MemoryTools(MemoryStore store, boolean readEnabled) {
        this.store = store;
        this.readEnabled = readEnabled;
    }

    public void register(ToolRouter router) {
        router.register(new ToolSpec("memory_get_all", "Read review memory.", object(Map.of(
                "repo", str("owner/name repository"),
                "author", str("GitHub username"),
                "max_tokens", integer("Max tokens")
        ), List.of()), this::memoryGetAll, false));
        router.register(new ToolSpec("memory_add_pattern", "Add known issue pattern.", object(Map.of(
                "name", str("Pattern name"),
                "description", str("Description"),
                "severity", str("Severity"),
                "indicators", Map.of("type", "array", "items", Map.of("type", "string"))
        ), List.of("name", "description")), this::memoryAddPattern, true));
        router.register(new ToolSpec("memory_add_false_positive", "Add false positive rule.", object(Map.of(
                "pattern", str("Pattern"),
                "reason", str("Reason"),
                "file_patterns", Map.of("type", "array", "items", Map.of("type", "string"))
        ), List.of("pattern", "reason")), this::memoryAddFalsePositive, true));
        router.register(new ToolSpec("memory_get_developer_profile", "Get developer profile.", object(Map.of(
                "author", str("GitHub username")
        ), List.of("author")), this::memoryGetDeveloperProfile, false));
        router.register(new ToolSpec("memory_update_developer_profile", "Update developer profile.", object(Map.of(
                "author", str("GitHub username"),
                "issues", array("Issues"),
                "new_issues", array("Issues"),
                "strengths", Map.of("type", "array", "items", Map.of("type", "string")),
                "growth_areas", Map.of("type", "array", "items", Map.of("type", "string"))
        ), List.of("author")), this::memoryUpdateDeveloperProfile, true));
        router.register(new ToolSpec("memory_aggregate_patterns", "Aggregate repo issue patterns.", object(Map.of(
                "repo", str("owner/name repository"),
                "issues", array("Issues")
        ), List.of("repo", "issues")), this::memoryAggregatePatterns, true));
        router.register(new ToolSpec("memory_health_report", "Generate repo health report.", object(Map.of(
                "repo", str("owner/name repository")
        ), List.of("repo")), this::memoryHealthReport, false));
    }

    public Object memoryGetAll(Map<String, Object> args) {
        if (!readEnabled) {
            return emptyMemory("memory_read_disabled");
        }
        ensureBuiltins();
        int maxTokens = intArg(args, "max_tokens", 3000);
        List<Map<String, Object>> rules = store.read("rules");
        return Map.of(
                "rules", rules,
                "developer_profiles", store.read("developer_profiles"),
                "repo_patterns", store.read("repo_patterns"),
                "summary", formatSummary(rules, maxTokens)
        );
    }

    public Object memoryAddPattern(Map<String, Object> args) {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("type", "known_pattern");
        record.put("id", newId("known_pattern"));
        record.put("created_at", Instant.now().toString());
        record.put("updated_at", Instant.now().toString());
        record.put("content", Map.of(
                "name", args.get("name"),
                "description", args.get("description"),
                "severity", args.getOrDefault("severity", "high"),
                "indicators", args.getOrDefault("indicators", List.of())
        ));
        store.append("rules", record);
        return Map.of("status", "ok", "id", record.get("id"));
    }

    public Object memoryAddFalsePositive(Map<String, Object> args) {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("type", "false_positive");
        record.put("id", newId("false_positive"));
        record.put("created_at", Instant.now().toString());
        record.put("updated_at", Instant.now().toString());
        record.put("content", Map.of(
                "pattern", args.get("pattern"),
                "reason", args.get("reason"),
                "file_patterns", args.getOrDefault("file_patterns", List.of())
        ));
        store.append("rules", record);
        return Map.of("status", "ok", "id", record.get("id"));
    }

    public Object memoryGetDeveloperProfile(Map<String, Object> args) {
        if (!readEnabled) {
            String author = String.valueOf(args.get("author"));
            return Map.of("found", false, "disabled", true, "profile", Map.of(
                    "type", "developer_profile",
                    "content", Map.of("author", author, "issue_history", List.of(), "strengths", List.of(), "growth_areas", List.of(), "pr_count", 0,
                            "message", "Memory reads are disabled for this run")
            ));
        }
        String author = String.valueOf(args.get("author"));
        for (Map<String, Object> profile : store.read("developer_profiles")) {
            Map<String, Object> content = content(profile);
            if (author.equals(content.get("author"))) {
                return Map.of("found", true, "profile", profile);
            }
        }
        return Map.of("found", false, "profile", Map.of(
                "type", "developer_profile",
                "content", Map.of("author", author, "issue_history", List.of(), "strengths", List.of(), "growth_areas", List.of(), "pr_count", 0,
                        "message", "No profile yet — this is the developer's first review")
        ));
    }

    @SuppressWarnings("unchecked")
    public Object memoryUpdateDeveloperProfile(Map<String, Object> args) {
        String author = String.valueOf(args.get("author"));
        List<Map<String, Object>> issues = (List<Map<String, Object>>) args.getOrDefault("issues", args.getOrDefault("new_issues", List.of()));
        List<String> strengths = stringList(args.getOrDefault("strengths", List.of()));
        List<String> growthAreas = stringList(args.getOrDefault("growth_areas", List.of()));
        List<Map<String, Object>> profiles = store.read("developer_profiles");
        String now = Instant.now().toString();
        Map<String, Object> profile = profiles.stream().filter(p -> author.equals(content(p).get("author"))).findFirst().orElseGet(() -> {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("type", "developer_profile");
            p.put("id", newId("developer_profile"));
            p.put("created_at", now);
            p.put("content", new LinkedHashMap<String, Object>());
            return p;
        });
        Map<String, Object> content = content(profile);
        content.put("author", author);
        content.put("pr_count", intValue(content.getOrDefault("pr_count", 0)) + 1);
        content.put("last_updated", now);
        Map<String, Map<String, Object>> history = new LinkedHashMap<>();
        for (Map<String, Object> item : listOfMaps(content.getOrDefault("issue_history", List.of()))) {
            history.put(String.valueOf(item.getOrDefault("category", "unknown")), new LinkedHashMap<>(item));
        }
        Map<String, Integer> categoryCounts = new LinkedHashMap<>();
        for (Map<String, Object> issue : issues) {
            String category = String.valueOf(issue.getOrDefault("category", "general"));
            Map<String, Object> item = history.computeIfAbsent(category, key -> {
                Map<String, Object> h = new LinkedHashMap<>();
                h.put("category", key);
                h.put("count", 0);
                return h;
            });
            item.put("count", intValue(item.getOrDefault("count", 0)) + 1);
            item.put("last_seen", now);
        }
        for (Map<String, Object> item : history.values()) {
            categoryCounts.put(String.valueOf(item.get("category")), intValue(item.get("count")));
        }
        content.put("issue_history", new ArrayList<>(history.values()));
        content.put("category_counts", categoryCounts);
        content.put("strengths", union(stringList(content.getOrDefault("strengths", List.of())), strengths));
        content.put("growth_areas", union(stringList(content.getOrDefault("growth_areas", List.of())), growthAreas));
        profile.put("updated_at", now);
        profile.put("content", content);
        profiles.removeIf(p -> author.equals(content(p).get("author")));
        profiles.add(profile);
        store.overwrite("developer_profiles", profiles);
        return Map.of("status", "ok", "profile", profile, "pr_count", content.get("pr_count"));
    }

    @SuppressWarnings("unchecked")
    public Object memoryAggregatePatterns(Map<String, Object> args) {
        String repo = String.valueOf(args.get("repo"));
        List<Map<String, Object>> issues = (List<Map<String, Object>>) args.getOrDefault("issues", List.of());
        List<Map<String, Object>> patterns = store.read("repo_patterns");
        String now = Instant.now().toString();
        for (Map<String, Object> issue : issues) {
            String patternName = issue.getOrDefault("severity", "medium") + ":" + issue.getOrDefault("category", "general");
            Map<String, Object> existing = patterns.stream()
                    .filter(p -> repo.equals(content(p).get("repo")) && patternName.equals(content(p).get("pattern_name")))
                    .findFirst()
                    .orElseGet(() -> {
                        Map<String, Object> p = new LinkedHashMap<>();
                        p.put("type", "repo_pattern");
                        p.put("id", newId("repo_pattern"));
                        p.put("created_at", now);
                        Map<String, Object> c = new LinkedHashMap<>();
                        c.put("repo", repo);
                        c.put("pattern_name", patternName);
                        c.put("occurrence_count", 0);
                        c.put("affected_files", new ArrayList<String>());
                        c.put("first_seen", now);
                        c.put("trend", "new");
                        p.put("content", c);
                        patterns.add(p);
                        return p;
                    });
            Map<String, Object> content = content(existing);
            content.put("category", issue.getOrDefault("category", "general"));
            content.put("severity", issue.getOrDefault("severity", "medium"));
            int count = intValue(content.getOrDefault("occurrence_count", 0)) + 1;
            content.put("occurrence_count", count);
            content.put("last_seen", now);
            content.put("trend", count > 5 ? "increasing" : (count == 1 ? "new" : "stable"));
            content.putIfAbsent("first_seen", now);
            content.putIfAbsent("sample_description", issue.getOrDefault("body", issue.getOrDefault("description", "")));
            List<String> files = stringList(content.getOrDefault("affected_files", List.of()));
            String file = String.valueOf(issue.getOrDefault("file", ""));
            if (!file.isBlank() && !files.contains(file)) {
                files.add(file);
            }
            content.put("affected_files", files);
            existing.put("updated_at", now);
            existing.put("content", content);
        }
        store.overwrite("repo_patterns", patterns);
        return Map.of("status", "ok", "patterns_updated", issues.size(), "repo", repo);
    }

    public Object memoryHealthReport(Map<String, Object> args) {
        if (!readEnabled) {
            return Map.of("repo", String.valueOf(args.get("repo")), "disabled", true, "total_occurrences", 0, "top_patterns", List.of());
        }
        String repo = String.valueOf(args.get("repo"));
        List<Map<String, Object>> patterns = store.read("repo_patterns").stream().filter(p -> repo.equals(content(p).get("repo"))).toList();
        int total = patterns.stream().mapToInt(p -> intValue(content(p).getOrDefault("occurrence_count", 0))).sum();
        List<Map<String, Object>> top = patterns.stream()
                .sorted(Comparator.comparingInt(p -> -intValue(content(p).getOrDefault("occurrence_count", 0))))
                .limit(10)
                .toList();
        return Map.of("repo", repo, "total_occurrences", total, "top_patterns", top);
    }

    private static Map<String, Object> emptyMemory(String reason) {
        return Map.of(
                "disabled", true,
                "reason", reason,
                "rules", List.of(),
                "developer_profiles", List.of(),
                "repo_patterns", List.of(),
                "summary", "Memory reads are disabled for this run."
        );
    }

    private void ensureBuiltins() {
        if (!store.read("rules").isEmpty()) {
            return;
        }
        List<Map<String, Object>> builtins = List.of(
                builtin("known_pattern", "builtin-1", Map.of("name", "SQL注入", "description", "使用字符串拼接或 f-string 构造 SQL 查询", "severity", "critical", "indicators", List.of("f\"SELECT", "f'SELECT", "+ \" WHERE", "+ ' WHERE"))),
                builtin("known_pattern", "builtin-2", Map.of("name", "硬编码密钥", "description", "代码中直接包含 API key、密码、token", "severity", "critical", "indicators", List.of("password =", "api_key =", "secret =", "token ="))),
                builtin("known_pattern", "builtin-3", Map.of("name", "XSS漏洞", "description", "未转义的用户输入直接渲染到 HTML", "severity", "high", "indicators", List.of("innerHTML =", "dangerouslySetInnerHTML", "render_template_string"))),
                builtin("false_positive", "builtin-fp-1", Map.of("pattern", "test 文件中的硬编码值", "reason", "测试文件使用固定样例数据", "file_patterns", List.of("test_*.py", "*_test.py", "*/tests/*", "*/test/*"))),
                builtin("false_positive", "builtin-fp-2", Map.of("pattern", "migrations 文件中的 SQL", "reason", "数据库迁移文件使用原生 SQL 是正常的", "file_patterns", List.of("*/migrations/*", "*migration*.py")))
        );
        builtins.forEach(r -> store.append("rules", r));
    }

    private static Map<String, Object> builtin(String type, String id, Map<String, Object> content) {
        return Map.of("type", type, "id", id, "created_at", "2026-01-01T00:00:00Z", "updated_at", "2026-01-01T00:00:00Z", "content", content);
    }

    private static String formatSummary(List<Map<String, Object>> records, int maxTokens) {
        StringBuilder out = new StringBuilder();
        for (Map<String, Object> record : records) {
            out.append(record.get("type")).append(": ").append(content(record)).append("\n");
        }
        int maxChars = maxTokens * 4;
        return out.length() > maxChars ? out.substring(0, maxChars) + "\n[已截断]" : out.toString();
    }

    private static int intArg(Map<String, Object> args, String key, int fallback) {
        Object value = args.get(key);
        if (value == null) {
            return fallback;
        }
        return value instanceof Number n ? n.intValue() : Integer.parseInt(String.valueOf(value));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> content(Map<String, Object> record) {
        Object content = record.get("content");
        if (content instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return record;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> listOfMaps(Object value) {
        if (value instanceof List<?> list) {
            return list.stream().filter(Map.class::isInstance).map(item -> (Map<String, Object>) item).toList();
        }
        return List.of();
    }

    private static List<String> stringList(Object value) {
        if (value instanceof List<?> list) {
            return new ArrayList<>(list.stream().map(String::valueOf).toList());
        }
        return new ArrayList<>();
    }

    private static List<String> union(List<String> a, List<String> b) {
        Set<String> out = new HashSet<>(a);
        out.addAll(b);
        return new ArrayList<>(out);
    }

    private static int intValue(Object value) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        if (value == null) {
            return 0;
        }
        return Integer.parseInt(String.valueOf(value));
    }

    private static String newId(String type) {
        return type + "-" + LocalDate.now().toString().replace("-", "") + "-" + UUID.randomUUID().toString().substring(0, 4);
    }
}
