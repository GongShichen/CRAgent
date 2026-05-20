package com.cragent.datasets;

import com.cragent.agent.CodeReviewAgent;
import com.cragent.cli.GitEnvironment;
import com.cragent.config.Settings;
import com.cragent.llm.LlmClient;
import com.cragent.llm.OpenAiCompatibleClient;
import com.cragent.model.AgentRunResult;
import com.cragent.tools.GitHubTools;
import com.cragent.util.Jsons;
import com.cragent.util.ProjectPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class DatasetBatchRunner {
    private final Settings baseSettings;

    public DatasetBatchRunner(Settings baseSettings) {
        this.baseSettings = baseSettings;
    }

    public int run(String[] args) {
        Options options = Options.parse(args);
        if (!baseSettings.hasLlmCredentials()) {
            throw new IllegalStateException("OPENAI_BASE_URL, OPENAI_API_KEY, and OPENAI_MODEL are required for dataset runs.");
        }
        List<Map<String, Object>> tasks = readJsonl(options.tasksPath());
        if (tasks.isEmpty()) {
            throw new IllegalStateException("No tasks found in " + options.tasksPath());
        }

        Filesystem.ensureParent(options.resultsPath());
        Set<String> completed = options.resume() ? completedTaskIds(options.resultsPath()) : Set.of();
        List<Map<String, Object>> results = new ArrayList<>();
        int attempted = 0;
        int completedCount = 0;
        int failedCount = 0;
        int skippedCount = 0;

        for (Map<String, Object> rawTask : tasks) {
            if (attempted >= options.limit()) {
                break;
            }
            Map<String, Object> task = new LinkedHashMap<>(rawTask);
            String taskId = taskId(task);
            if (options.resume() && completed.contains(taskId)) {
                skippedCount++;
                continue;
            }
            attempted++;
            Map<String, Object> row = runOne(task, taskId, options);
            results.add(row);
            appendJsonl(options.resultsPath(), row);
            if ("completed".equals(row.get("status"))) {
                completedCount++;
            } else {
                failedCount++;
            }
            writeManifest(options, tasks.size(), attempted, completedCount, failedCount, skippedCount, results);
            System.out.printf("[%s] %s %s%n", row.get("status"), taskId, row.getOrDefault("summary", ""));
        }
        writeManifest(options, tasks.size(), attempted, completedCount, failedCount, skippedCount, results);
        System.out.println("Dataset run manifest: " + options.manifestPath());
        return 0;
    }

    private Map<String, Object> runOne(Map<String, Object> task, String taskId, Options options) {
        String mode = string(task.get("mode"));
        String repo = string(task.get("repo"));
        Settings settings = baseSettings
                .withDryRun(!options.live())
                .withTraceDir(options.traceDir());
        LlmClient llm = new OpenAiCompatibleClient(settings);
        Instant started = Instant.now();
        try {
            AgentRunResult result = switch (mode) {
                case "pr" -> reviewPr(settings, llm, repo, intValue(task.get("pr")));
                case "commits" -> reviewCommits(settings, llm, repo, string(task.get("base")), string(task.get("head")));
                case "repo_latest" -> {
                    CommitRange range = latestCommitRange(settings, repo);
                    yield reviewCommits(settings, llm, repo, range.base(), range.head());
                }
                case "repo_audit" -> new CodeReviewAgent(settings, llm).reviewRepository(repo);
                default -> throw new IllegalArgumentException("Unsupported dataset task mode: " + mode);
            };
            return resultRow(task, taskId, started, "completed", result.summary,
                    result.tracePath == null ? "" : result.tracePath.toString(),
                    result.reportPath == null ? "" : result.reportPath.toString(),
                    result.issues.size(), null);
        } catch (Exception e) {
            return resultRow(task, taskId, started, "failed", safeMessage(e), null, null, 0, e.getClass().getName());
        }
    }

    private AgentRunResult reviewPr(Settings settings, LlmClient llm, String repo, int pr) {
        if (!settings.hasGithubCredentials()) {
            throw new IllegalStateException("GITHUB_TOKEN is required for PR dataset tasks.");
        }
        return new CodeReviewAgent(settings, llm).review(repo, pr);
    }

    private AgentRunResult reviewCommits(Settings settings, LlmClient llm, String repo, String base, String head) {
        GitEnvironment.LocalReviewContext local = GitEnvironment.localReviewContext(repo, base, head);
        if (local != null) {
            return new CodeReviewAgent(settings, llm).reviewLocalGitCommits(repo, base, head,
                    local.changedFiles(), local.diff(), local.commits(), local.author(), local.repoPath());
        }
        if (!settings.hasGithubCredentials()) {
            throw new IllegalStateException("Local git context unavailable and GITHUB_TOKEN is not configured for " + repo);
        }
        return new CodeReviewAgent(settings, llm).reviewCommits(repo, base, head);
    }

    @SuppressWarnings("unchecked")
    private CommitRange latestCommitRange(Settings settings, String repo) {
        GitEnvironment.CommitRange gitRange = GitEnvironment.latestCommitRange(repo);
        if (gitRange != null) {
            return new CommitRange(gitRange.base(), gitRange.head());
        }
        if (!settings.hasGithubCredentials()) {
            throw new IllegalStateException("GITHUB_TOKEN is required to resolve latest commits for " + repo);
        }
        String[] parts = repo.split("/", 2);
        Object raw = new GitHubTools(settings.githubToken()).listCommits(Map.of("owner", parts[0], "repo", parts[1], "per_page", 2));
        if (!(raw instanceof List<?> list) || list.size() < 2) {
            throw new IllegalStateException("Repository has fewer than two commits: " + repo);
        }
        Map<String, Object> head = (Map<String, Object>) list.get(0);
        Map<String, Object> base = (Map<String, Object>) list.get(1);
        return new CommitRange(String.valueOf(base.get("sha")), String.valueOf(head.get("sha")));
    }

    private static Map<String, Object> resultRow(Map<String, Object> task, String taskId, Instant started, String status,
                                                 String summary, String tracePath, String reportPath, int issues, String errorType) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("task_id", taskId);
        row.put("status", status);
        row.put("started_at", started.toString());
        row.put("finished_at", Instant.now().toString());
        row.put("mode", task.getOrDefault("mode", ""));
        row.put("repo", task.getOrDefault("repo", ""));
        row.put("language", task.getOrDefault("language", ""));
        row.put("summary", summary == null ? "" : summary);
        row.put("trace_path", tracePath == null ? "" : tracePath);
        row.put("report_path", reportPath == null ? "" : reportPath);
        row.put("issues_found", issues);
        if (errorType != null) {
            row.put("error_type", errorType);
        }
        row.put("task", task);
        return row;
    }

    private static void writeManifest(Options options, int totalTasks, int attempted, int completed,
                                      int failed, int skipped, List<Map<String, Object>> recentResults) {
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("run_id", options.runId());
        manifest.put("created_at", options.createdAt());
        manifest.put("updated_at", Instant.now().toString());
        manifest.put("tasks_path", options.tasksPath().toString());
        manifest.put("results_path", options.resultsPath().toString());
        manifest.put("trace_dir", options.traceDir().toString());
        manifest.put("limit", options.limit());
        manifest.put("live", options.live());
        manifest.put("resume", options.resume());
        manifest.put("total_tasks_available", totalTasks);
        manifest.put("attempted", attempted);
        manifest.put("completed", completed);
        manifest.put("failed", failed);
        manifest.put("skipped_completed", skipped);
        manifest.put("recent_results", recentResults.stream().skip(Math.max(0, recentResults.size() - 20)).toList());
        Filesystem.writeString(options.manifestPath(), Jsons.pretty(manifest));
    }

    private static List<Map<String, Object>> readJsonl(Path path) {
        if (!Files.exists(path)) {
            return List.of();
        }
        try {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                if (!line.isBlank()) {
                    rows.add(Jsons.MAPPER.readValue(line, Map.class));
                }
            }
            return rows;
        } catch (IOException e) {
            throw new IllegalStateException("Unable to read JSONL: " + path, e);
        }
    }

    private static Set<String> completedTaskIds(Path resultsPath) {
        Set<String> out = new HashSet<>();
        for (Map<String, Object> row : readJsonl(resultsPath)) {
            if ("completed".equals(row.get("status")) && row.get("task_id") != null) {
                out.add(String.valueOf(row.get("task_id")));
            }
        }
        return out;
    }

    private static void appendJsonl(Path path, Map<String, Object> row) {
        Filesystem.ensureParent(path);
        try {
            Files.writeString(path, Jsons.stringify(row) + System.lineSeparator(), StandardCharsets.UTF_8,
                    Files.exists(path) ? java.nio.file.StandardOpenOption.APPEND : java.nio.file.StandardOpenOption.CREATE);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to append result: " + path, e);
        }
    }

    private static String taskId(Map<String, Object> task) {
        Object explicit = task.get("id");
        if (explicit != null && !String.valueOf(explicit).isBlank()) {
            return String.valueOf(explicit);
        }
        return string(task.get("mode")) + ":" + string(task.get("repo")) + ":"
                + string(task.get("pr")) + ":" + string(task.get("base")) + ":" + string(task.get("head"));
    }

    private static String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static int intValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        return Integer.parseInt(String.valueOf(value));
    }

    private static String safeMessage(Exception e) {
        return e.getMessage() == null || e.getMessage().isBlank() ? e.getClass().getSimpleName() : e.getMessage();
    }

    private record CommitRange(String base, String head) {
    }

    public record Options(Path tasksPath, int limit, boolean resume, boolean live, String runId,
                          String createdAt, Path runDir, Path resultsPath, Path manifestPath, Path traceDir) {
        static Options parse(String[] args) {
            Map<String, String> values = new HashMap<>();
            Set<String> flags = new HashSet<>();
            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                if (!arg.startsWith("--")) {
                    continue;
                }
                String key = arg.substring(2);
                if ("resume".equals(key) || "live".equals(key)) {
                    flags.add(key);
                } else if (i + 1 < args.length) {
                    values.put(key, args[++i]);
                }
            }
            String runId = values.getOrDefault("run-id", DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                    .withZone(ZoneOffset.UTC).format(Instant.now()));
            Path rawRoot = ProjectPaths.repoRoot().resolve("datasets/raw").normalize();
            Path runDir = rawRoot.resolve("runs").resolve(runId).normalize();
            Path tasks = Path.of(values.getOrDefault("tasks", rawRoot.resolve("tasks.jsonl").toString())).normalize();
            int limit = Integer.parseInt(values.getOrDefault("limit", "1000"));
            Path results = Path.of(values.getOrDefault("results", runDir.resolve("results.jsonl").toString())).normalize();
            Path manifest = Path.of(values.getOrDefault("manifest", runDir.resolve("manifest.json").toString())).normalize();
            Path traceDir = Path.of(values.getOrDefault("trace-dir", Path.of("data/traces/raw").resolve(runId).toString())).normalize();
            return new Options(tasks, limit, flags.contains("resume"), flags.contains("live"), runId,
                    Instant.now().toString(), runDir, results, manifest, traceDir);
        }
    }

    private static final class Filesystem {
        private static void ensureParent(Path path) {
            try {
                if (path.getParent() != null) {
                    Files.createDirectories(path.getParent());
                }
            } catch (IOException e) {
                throw new IllegalStateException("Unable to create directory: " + path.getParent(), e);
            }
        }

        private static void writeString(Path path, String content) {
            ensureParent(path);
            try {
                Files.writeString(path, content, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new IllegalStateException("Unable to write file: " + path, e);
            }
        }
    }
}
