package com.cragent.agent;

import com.cragent.config.Settings;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class RepoAuditIndexer {
    private static final long HARD_MAX_BYTES = 1_000_000L;

    private final Settings settings;

    public RepoAuditIndexer(Settings settings) {
        this.settings = settings;
    }

    public AuditIndex index(Path repoPath) {
        List<AuditFile> reviewed = new ArrayList<>();
        List<Map<String, Object>> skipped = new ArrayList<>();
        List<Path> tracked = gitTrackedFiles(repoPath);
        if (!tracked.isEmpty()) {
            for (Path relative : tracked.stream().sorted(Comparator.comparing(Path::toString)).toList()) {
                Path file = repoPath.resolve(relative).normalize();
                if (Files.isRegularFile(file)) {
                    addFile(repoPath, file, reviewed, skipped);
                }
            }
        } else {
            walkFilesystem(repoPath, reviewed, skipped);
        }
        List<AuditSlice> slices = new ArrayList<>();
        for (AuditFile file : reviewed) {
            slices.addAll(slicesFor(repoPath, file));
        }
        return new AuditIndex(reviewed, skipped, slices, stack(reviewed), directorySummary(reviewed));
    }

    private void walkFilesystem(Path repoPath, List<AuditFile> reviewed, List<Map<String, Object>> skipped) {
        try {
            Files.walkFileTree(repoPath, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (repoPath.equals(dir)) {
                        return FileVisitResult.CONTINUE;
                    }
                    String rel = repoPath.relativize(dir).toString().replace('\\', '/');
                    String reason = skipDirectoryReason(rel);
                    if (reason != null) {
                        skipped.add(Map.of("path", rel + "/", "reason", reason));
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (attrs.isRegularFile()) {
                        addFile(repoPath, file, reviewed, skipped);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
            reviewed.sort(Comparator.comparing(AuditFile::path));
        } catch (IOException e) {
            skipped.add(Map.of("path", ".", "reason", "walk_failed: " + e.getMessage()));
        }
    }

    private static List<Path> gitTrackedFiles(Path repoPath) {
        try {
            Process process = new ProcessBuilder("git", "-C", repoPath.toString(), "ls-files", "-z")
                    .redirectErrorStream(true)
                    .start();
            byte[] bytes = process.getInputStream().readAllBytes();
            int code = process.waitFor();
            if (code != 0 || bytes.length == 0) {
                return List.of();
            }
            String output = new String(bytes, StandardCharsets.UTF_8);
            List<Path> files = new ArrayList<>();
            for (String item : output.split("\\u0000")) {
                if (!item.isBlank()) {
                    files.add(Path.of(item));
                }
            }
            return files;
        } catch (Exception e) {
            return List.of();
        }
    }

    private void addFile(Path repoPath, Path path, List<AuditFile> reviewed, List<Map<String, Object>> skipped) {
        Path relPath = repoPath.relativize(path);
        String rel = relPath.toString().replace('\\', '/');
        String reason = skipReason(rel, path);
        if (reason != null) {
            skipped.add(Map.of("path", rel, "reason", reason));
            return;
        }
        try {
            long bytes = Files.size(path);
            if (bytes > HARD_MAX_BYTES) {
                skipped.add(Map.of("path", rel, "reason", "too_large"));
                return;
            }
            String content = Files.readString(path, StandardCharsets.UTF_8);
            reviewed.add(new AuditFile(rel, language(rel), bytes, lines(content), isTest(rel), isConfig(rel), sensitive(rel), content));
        } catch (Exception e) {
            skipped.add(Map.of("path", rel, "reason", "unreadable_text"));
        }
    }

    private List<AuditSlice> slicesFor(Path repoPath, AuditFile file) {
        List<AuditSlice> out = new ArrayList<>();
        String[] lines = file.content().split("\\R", -1);
        int maxChars = Math.max(2000, settings.repoAuditMaxFileChars());
        int start = 1;
        StringBuilder chunk = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (chunk.length() + line.length() + 1 > maxChars && !chunk.isEmpty()) {
                out.add(new AuditSlice(file.path(), start, i, chunk.toString()));
                start = i + 1;
                chunk = new StringBuilder();
            }
            chunk.append(line).append('\n');
        }
        if (!chunk.isEmpty()) {
            out.add(new AuditSlice(file.path(), start, Math.max(start, lines.length), chunk.toString()));
        }
        return out;
    }

    public List<List<AuditSlice>> batches(List<AuditSlice> slices) {
        int budgetChars = Math.max(8000, settings.repoAuditBatchTokenBudget() * 3);
        List<AuditSlice> ordered = slices.stream()
                .sorted(Comparator.comparingInt((AuditSlice s) -> riskRank(s.path())).thenComparing(AuditSlice::path))
                .toList();
        List<List<AuditSlice>> batches = new ArrayList<>();
        List<AuditSlice> current = new ArrayList<>();
        int chars = 0;
        for (AuditSlice slice : ordered) {
            int size = slice.content().length();
            if (!current.isEmpty() && chars + size > budgetChars) {
                batches.add(current);
                current = new ArrayList<>();
                chars = 0;
            }
            current.add(slice);
            chars += size;
        }
        if (!current.isEmpty()) {
            batches.add(current);
        }
        return batches;
    }

    private static String skipReason(String path, Path absolute) {
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.contains("/.git/") || lower.startsWith(".git/")) return "git_metadata";
        if (lower.contains("/.swebench-runs/") || lower.startsWith(".swebench-runs/")) return "runtime_artifact";
        if (lower.contains("/target-project/") || lower.startsWith("target-project/")) return "runtime_artifact";
        if (lower.contains("/report/") || lower.startsWith("report/")) return "runtime_artifact";
        if (lower.contains("/data/traces/") || lower.startsWith("data/traces/")) return "runtime_artifact";
        if (lower.contains("/datasets/") || lower.startsWith("datasets/")) return "runtime_artifact";
        if (lower.contains("/memory/") || lower.startsWith("memory/")) return "runtime_artifact";
        if (lower.contains("/node_modules/") || lower.startsWith("node_modules/")) return "vendor_cache";
        if (lower.contains("/vendor/") || lower.startsWith("vendor/")) return "vendor_cache";
        if (lower.contains("/third_party/") || lower.startsWith("third_party/")) return "vendor_cache";
        if (lower.contains("/build/") || lower.contains("/dist/") || lower.contains("/target/") || lower.contains("/out/")) return "build_artifact";
        if (lower.contains("/coverage/") || lower.startsWith("coverage/")) return "build_artifact";
        if (lower.contains("/.gradle/") || lower.contains("/.idea/") || lower.contains("/.venv/") || lower.contains("/__pycache__/")) return "cache";
        if (lower.contains("/.cache/") || lower.startsWith(".cache/") || lower.contains("/.pytest_cache/") || lower.contains("/.mypy_cache/")) return "cache";
        if (lower.endsWith(".class") || lower.endsWith(".jar") || lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                || lower.endsWith(".gif") || lower.endsWith(".pdf") || lower.endsWith(".zip") || lower.endsWith(".gz")
                || lower.endsWith(".wasm") || lower.endsWith(".dylib") || lower.endsWith(".so") || lower.endsWith(".a")) return "binary";
        if (lower.endsWith(".min.js") || lower.endsWith(".generated.java") || lower.contains("/generated/")) return "generated";
        return null;
    }

    private static String skipDirectoryReason(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.equals(".git") || lower.endsWith("/.git")) return "git_metadata";
        if (lower.equals(".swebench-runs") || lower.endsWith("/.swebench-runs")) return "runtime_artifact";
        if (lower.equals("target-project") || lower.endsWith("/target-project")) return "runtime_artifact";
        if (lower.equals("report") || lower.endsWith("/report")) return "runtime_artifact";
        if (lower.equals("memory") || lower.endsWith("/memory")) return "runtime_artifact";
        if (lower.equals("node_modules") || lower.endsWith("/node_modules")) return "vendor_cache";
        if (lower.equals("vendor") || lower.endsWith("/vendor")) return "vendor_cache";
        if (lower.equals("third_party") || lower.endsWith("/third_party")) return "vendor_cache";
        if (lower.equals("build") || lower.endsWith("/build") || lower.equals("dist") || lower.endsWith("/dist")
                || lower.equals("target") || lower.endsWith("/target") || lower.equals("out") || lower.endsWith("/out")
                || lower.equals("coverage") || lower.endsWith("/coverage")) return "build_artifact";
        if (lower.equals(".gradle") || lower.endsWith("/.gradle") || lower.equals(".idea") || lower.endsWith("/.idea")
                || lower.equals(".venv") || lower.endsWith("/.venv") || lower.equals("__pycache__") || lower.endsWith("/__pycache__")
                || lower.equals(".cache") || lower.endsWith("/.cache") || lower.equals(".pytest_cache") || lower.endsWith("/.pytest_cache")
                || lower.equals(".mypy_cache") || lower.endsWith("/.mypy_cache")) return "cache";
        if (lower.equals("generated") || lower.endsWith("/generated")) return "generated";
        return null;
    }

    private static String language(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".java")) return "java";
        if (lower.endsWith(".kt")) return "kotlin";
        if (lower.endsWith(".js")) return "javascript";
        if (lower.endsWith(".ts") || lower.endsWith(".tsx")) return "typescript";
        if (lower.endsWith(".go")) return "go";
        if (lower.endsWith(".py")) return "python";
        if (lower.endsWith(".rs")) return "rust";
        if (lower.endsWith(".yml") || lower.endsWith(".yaml") || lower.endsWith(".json") || lower.endsWith(".toml") || lower.endsWith(".xml")) return "config";
        if (lower.endsWith(".md")) return "markdown";
        return "text";
    }

    private static boolean isTest(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        return lower.contains("/test/") || lower.contains("/tests/") || lower.endsWith("_test.go") || lower.endsWith("_test.rs")
                || lower.endsWith(".test.ts") || lower.endsWith(".spec.ts") || lower.endsWith("test.java");
    }

    private static boolean isConfig(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        return lower.endsWith("pom.xml") || lower.endsWith("build.gradle") || lower.endsWith("package.json")
                || lower.endsWith("cargo.toml") || lower.endsWith("pyproject.toml") || lower.contains(".github/workflows/");
    }

    private static boolean sensitive(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        return lower.contains("auth") || lower.contains("security") || lower.contains("token") || lower.contains("password")
                || lower.contains("permission") || lower.contains("payment") || lower.contains("migration") || lower.contains("crypto");
    }

    private static int riskRank(String path) {
        return sensitive(path) ? 0 : (isConfig(path) ? 1 : (isTest(path) ? 3 : 2));
    }

    private static int lines(String content) {
        return content.isBlank() ? 0 : content.split("\\R", -1).length;
    }

    private static Map<String, Object> stack(List<AuditFile> files) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (AuditFile file : files) {
            counts.merge(file.language(), 1, Integer::sum);
        }
        return new LinkedHashMap<>(counts);
    }

    private static Map<String, Object> directorySummary(List<AuditFile> files) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (AuditFile file : files) {
            String dir = file.path().contains("/") ? file.path().substring(0, file.path().indexOf('/')) : ".";
            counts.merge(dir, 1, Integer::sum);
        }
        return new LinkedHashMap<>(counts);
    }

    public record AuditIndex(List<AuditFile> files, List<Map<String, Object>> skipped, List<AuditSlice> slices,
                             Map<String, Object> stack, Map<String, Object> directories) {
    }

    public record AuditFile(String path, String language, long bytes, int lines, boolean test, boolean config,
                            boolean sensitive, String content) {
        public Map<String, Object> manifest() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("path", path);
            out.put("language", language);
            out.put("bytes", bytes);
            out.put("lines", lines);
            out.put("test", test);
            out.put("config", config);
            out.put("sensitive", sensitive);
            return out;
        }
    }

    public record AuditSlice(String path, int startLine, int endLine, String content) {
        public Map<String, Object> payload() {
            return Map.of("path", path, "start_line", startLine, "end_line", endLine, "content", content);
        }
    }
}
