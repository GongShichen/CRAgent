package com.cragent.tools;

import com.cragent.agent.RepoAuditIndexer;
import com.cragent.agent.RepoStaticChecks;
import com.cragent.agent.AppleXcodeContext;
import com.cragent.config.Settings;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.cragent.tools.ToolSchemas.integer;
import static com.cragent.tools.ToolSchemas.object;
import static com.cragent.tools.ToolSchemas.str;

public class RepoAuditTools {
    private final Settings settings;

    public RepoAuditTools(Settings settings) {
        this.settings = settings;
    }

    public void register(ToolRouter router) {
        router.register(new ToolSpec("detect_project_stack", "Detect project stack from a local repo path.", object(Map.of(
                "repo_path", str("Local repository path")
        ), List.of("repo_path")), this::detectProjectStack, false));
        router.register(new ToolSpec("repo_file_manifest", "Build full repository file manifest.", object(Map.of(
                "repo_path", str("Local repository path")
        ), List.of("repo_path")), this::repoFileManifest, false));
        router.register(new ToolSpec("read_repo_file_slice", "Read a line range from a repository file.", object(Map.of(
                "repo_path", str("Local repository path"),
                "path", str("Relative file path"),
                "start_line", integer("Start line, 1-based"),
                "end_line", integer("End line, inclusive")
        ), List.of("repo_path", "path")), this::readRepoFileSlice, false));
        router.register(new ToolSpec("run_static_checks", "Run safe read-only static checks for a local repo.", object(Map.of(
                "repo_path", str("Local repository path")
        ), List.of("repo_path")), this::runStaticChecks, false));
        router.register(new ToolSpec("apple_xcode_context", "Detect Apple platform project markers and Xcode MCP bridge availability for Swift/Objective-C repository review.", object(Map.of(
                "repo_path", str("Local repository path")
        ), List.of("repo_path")), this::appleXcodeContext, false));
        router.register(new ToolSpec("search_repo_text", "Search text in readable repository files.", object(Map.of(
                "repo_path", str("Local repository path"),
                "query", str("Search query")
        ), List.of("repo_path", "query")), this::searchRepoText, false));
    }

    private Object detectProjectStack(Map<String, Object> args) {
        RepoAuditIndexer.AuditIndex index = new RepoAuditIndexer(settings).index(path(args, "repo_path"));
        return index.stack();
    }

    private Object repoFileManifest(Map<String, Object> args) {
        RepoAuditIndexer.AuditIndex index = new RepoAuditIndexer(settings).index(path(args, "repo_path"));
        return Map.of(
                "files", index.files().stream().map(RepoAuditIndexer.AuditFile::manifest).toList(),
                "skipped", index.skipped(),
                "slices", index.slices().size()
        );
    }

    private Object readRepoFileSlice(Map<String, Object> args) {
        try {
            Path root = path(args, "repo_path");
            Path file = safeResolve(root, String.valueOf(args.get("path")));
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            int start = intArg(args, "start_line", 1);
            int end = intArg(args, "end_line", lines.size());
            start = Math.max(1, start);
            end = Math.min(lines.size(), end);
            StringBuilder out = new StringBuilder();
            for (int i = start; i <= end; i++) {
                out.append(i).append(": ").append(lines.get(i - 1)).append('\n');
            }
            return Map.of("path", args.get("path"), "start_line", start, "end_line", end, "content", out.toString());
        } catch (Exception e) {
            return Map.of("error", e.getMessage());
        }
    }

    private Object runStaticChecks(Map<String, Object> args) {
        Path root = path(args, "repo_path");
        RepoAuditIndexer.AuditIndex index = new RepoAuditIndexer(settings).index(root);
        return new RepoStaticChecks().run(root, index.stack());
    }

    private Object appleXcodeContext(Map<String, Object> args) {
        return AppleXcodeContext.probe(path(args, "repo_path"));
    }

    private Object searchRepoText(Map<String, Object> args) {
        Path root = path(args, "repo_path");
        String query = String.valueOf(args.get("query"));
        RepoAuditIndexer.AuditIndex index = new RepoAuditIndexer(settings).index(root);
        List<Map<String, Object>> matches = index.files().stream()
                .filter(file -> file.content().contains(query))
                .limit(100)
                .map(file -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("path", file.path());
                    item.put("language", file.language());
                    item.put("lines", file.lines());
                    return item;
                })
                .toList();
        return Map.of("query", query, "matches", matches);
    }

    private static Path path(Map<String, Object> args, String key) {
        return Path.of(String.valueOf(args.get(key))).toAbsolutePath().normalize();
    }

    private static Path safeResolve(Path root, String relative) {
        Path resolved = root.resolve(relative).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("Path escapes repository root");
        }
        return resolved;
    }

    private static int intArg(Map<String, Object> args, String key, int fallback) {
        Object value = args.get(key);
        if (value instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Exception e) {
            return fallback;
        }
    }
}
