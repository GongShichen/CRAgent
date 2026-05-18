package com.cragent.cli;

import com.cragent.agent.CodeReviewAgent;
import com.cragent.config.Settings;
import com.cragent.datasets.TraceDatasetExporter;
import com.cragent.llm.LlmClient;
import com.cragent.llm.OpenAiCompatibleClient;
import com.cragent.memory.MemoryStore;
import com.cragent.model.AgentRunResult;
import com.cragent.model.ReviewIssue;
import com.cragent.model.ToolResult;
import com.cragent.tools.GitHubTools;
import com.cragent.tools.MemoryTools;
import com.cragent.util.Jsons;
import com.cragent.util.ProjectPaths;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Scanner;

public class CrAgentCli {
    public static void main(String[] args) {
        try {
            run(args);
        } catch (Exception e) {
            printFriendlyError(e);
        }
    }

    private static void run(String[] args) throws Exception {
        if (args.length == 0) {
            usage();
            return;
        }
        String command = args[0];
        Map<String, String> opts = parse(args);
        Settings settings = applyCliOverrides(Settings.load(), opts);
        switch (command) {
            case "review" -> {
                PrIdentifier id = resolvePr(opts);
                requireReviewCredentials(settings);
                LlmClient llm = new OpenAiCompatibleClient(settings);
                AgentRunResult result = new CodeReviewAgent(settings, llm).review(id.fullRepo(), id.pr());
                printReview(result);
            }
            case "review-commits" -> {
                String repo = normalizeRepo(required(opts, "--repo"));
                String base = required(opts, "--base");
                String head = required(opts, "--head");
                requireLlmCredentials(settings);
                LlmClient llm = new OpenAiCompatibleClient(settings);
                AgentRunResult result = reviewCommitRange(settings, llm, repo, base, head);
                printReview(result);
            }
            case "chat" -> chat(settings);
            case "git-check" -> printGitCheck(normalizeRepo(required(opts, "--repo")));
            case "github-token-check" -> printGitHubTokenCheck(settings, normalizeRepo(required(opts, "--repo")));
            case "batch-review" -> {
                Path prsFile = Path.of(required(opts, "--prs"));
                requireReviewCredentials(settings);
                LlmClient llm = new OpenAiCompatibleClient(settings);
                int ok = 0;
                for (String line : Files.readAllLines(prsFile)) {
                    String trimmed = line.trim();
                    if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                        continue;
                    }
                    PrIdentifier id = PrIdentifier.parse(trimmed);
                    CodeReviewAgent agent = new CodeReviewAgent(settings, llm);
                    AgentRunResult result = agent.review(id.fullRepo(), id.pr());
                    if ("completed".equals(result.status)) {
                        ok++;
                    }
                    System.out.printf("%s #%d: %s (%d issues)%n", id.fullRepo(), id.pr(), result.status, result.issues.size());
                }
                System.out.println("Successful reviews: " + ok);
            }
            case "health-report" -> {
                String repo = required(opts, "--repo");
                Object report = new MemoryTools(new MemoryStore(settings.memoryDir())).memoryHealthReport(Map.of("repo", repo));
                System.out.println(Jsons.pretty(report));
            }
            case "export-sft" -> {
                Path input = Path.of(opts.getOrDefault("--input", ProjectPaths.defaultTraceDir().toString()));
                Path output = Path.of(opts.getOrDefault("--output", ProjectPaths.defaultSftPath().toString()));
                int count = new TraceDatasetExporter().exportSft(input, output);
                System.out.printf("Exported %d SFT records to %s%n", count, output);
            }
            case "export-dpo" -> {
                Path input = Path.of(opts.getOrDefault("--input", ProjectPaths.defaultTraceDir().toString()));
                Path output = Path.of(opts.getOrDefault("--output", ProjectPaths.defaultDpoPath().toString()));
                int count = new TraceDatasetExporter().exportDpo(input, output);
                System.out.printf("Exported %d DPO records to %s%n", count, output);
            }
            case "export-datasets" -> {
                Path input = Path.of(opts.getOrDefault("--input", ProjectPaths.defaultTraceDir().toString()));
                Path sft = Path.of(opts.getOrDefault("--sft-output", ProjectPaths.defaultSftPath().toString()));
                Path dpo = Path.of(opts.getOrDefault("--dpo-output", ProjectPaths.defaultDpoPath().toString()));
                TraceDatasetExporter exporter = new TraceDatasetExporter();
                int sftCount = exporter.exportSft(input, sft);
                int dpoCount = exporter.exportDpo(input, dpo);
                System.out.printf("Exported %d SFT records to %s%n", sftCount, sft);
                System.out.printf("Exported %d DPO records to %s%n", dpoCount, dpo);
            }
            case "inspect" -> {
                Path trace = Path.of(required(opts, "--trace"));
                inspectTrace(trace);
            }
            case "init-memory" -> {
                Files.createDirectories(settings.memoryDir());
                if (!Files.exists(settings.memoryDir().resolve("rules.jsonl"))) {
                    Files.createFile(settings.memoryDir().resolve("rules.jsonl"));
                }
                System.out.println("Initialized memory files in " + settings.memoryDir());
            }
            default -> usage();
        }
    }

    private static void printReview(AgentRunResult result) {
        System.out.println("Status: " + result.status);
        System.out.println("Summary: " + result.summary);
        System.out.println("Trace: " + result.tracePath);
        if (result.reportPath != null) {
            System.out.println("Report: " + result.reportPath);
        }
        System.out.println("Issues: " + result.issues.size());
        for (ReviewIssue issue : result.issues) {
            System.out.printf("- [%s] %s %s:%s %s%n", issue.severity, issue.category, issue.file, issue.line, issue.body);
        }
        System.out.println("Actions: " + result.actions.size());
        for (Map<String, Object> action : result.actions) {
            Object actionResult = action.get("result");
            if (actionResult instanceof ToolResult toolResult && !toolResult.ok) {
                System.out.printf("- Action warning: %s: %s%n", action.get("name"), toolResult.error);
            }
        }
    }

    private static Map<String, String> parse(String[] args) {
        Map<String, String> out = new HashMap<>();
        for (int i = 1; i < args.length; i++) {
            if (!args[i].startsWith("--")) {
                continue;
            }
            if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                out.put(args[i], args[++i]);
            } else {
                out.put(args[i], "true");
            }
        }
        return out;
    }

    private static Settings applyCliOverrides(Settings settings, Map<String, String> opts) {
        if (opts.containsKey("--dry-run")) {
            return settings.withDryRun(true);
        }
        if (opts.containsKey("--live")) {
            return settings.withDryRun(false);
        }
        return settings;
    }

    private static PrIdentifier resolvePr(Map<String, String> opts) {
        if (opts.containsKey("--pr-url")) {
            return PrIdentifier.parse(opts.get("--pr-url"));
        }
        if (opts.containsKey("--repo") && opts.containsKey("--pr")) {
            return PrIdentifier.parse(opts.get("--repo") + " #" + opts.get("--pr"));
        }
        if (opts.containsKey("--pr")) {
            return PrIdentifier.parse(opts.get("--pr"));
        }
        throw new IllegalArgumentException("Provide --repo owner/name --pr 123 or --pr-url https://github.com/owner/name/pull/123");
    }

    private static String normalizeRepo(String repo) {
        String value = ChatCommandParser.normalizeRepo(repo);
        if (value == null) {
            throw new IllegalArgumentException("Repository must be owner/name or a GitHub repository URL.");
        }
        return value;
    }

    private static void chat(Settings baseSettings) {
        System.out.println("CR Agent chat 已启动。输入 GitHub PR 或 commit 范围，我会解析并执行 review。输入 help 查看示例，exit 退出。");
        Scanner scanner = new Scanner(System.in);
        while (true) {
            System.out.print("cr-agent> ");
            if (!scanner.hasNextLine()) {
                System.out.println();
                return;
            }
            String line = scanner.nextLine().trim();
            ChatCommandParser.ChatIntent intent = ChatCommandParser.parse(line);
            try {
                switch (intent.type()) {
                    case EXIT -> {
                        System.out.println("bye");
                        return;
                    }
                    case HELP -> printChatHelp();
                    case UNKNOWN -> {
                        System.out.println(intent.message());
                        maybePrintGitCheck(line);
                    }
                    case PR -> {
                        Settings settings = applyChatOverride(baseSettings, intent);
                        requireReviewCredentials(settings);
                        System.out.printf("解析到 PR review: %s #%d (%s)%n", intent.repo(), intent.pr(), settings.dryRun() ? "dry-run" : "live");
                        LlmClient llm = new OpenAiCompatibleClient(settings);
                        printReview(new CodeReviewAgent(settings, llm).review(intent.repo(), intent.pr()));
                    }
                    case COMMITS -> {
                        Settings settings = applyChatOverride(baseSettings, intent);
                        requireLlmCredentials(settings);
                        System.out.printf("解析到 commit range review: %s %s...%s (%s)%n", intent.repo(), intent.base(), intent.head(), settings.dryRun() ? "dry-run" : "live");
                        LlmClient llm = new OpenAiCompatibleClient(settings);
                        printReview(reviewCommitRange(settings, llm, intent.repo(), intent.base(), intent.head()));
                    }
                    case REPO -> {
                        Settings settings = applyChatOverride(baseSettings, intent);
                        requireLlmCredentials(settings);
                        CommitRange range = latestCommitRange(settings, intent.repo());
                        System.out.printf("未指定 PR/commit，默认 review 默认分支最新提交: %s %s...%s (%s)%n", intent.repo(), range.base(), range.head(), settings.dryRun() ? "dry-run" : "live");
                        LlmClient llm = new OpenAiCompatibleClient(settings);
                        printReview(reviewCommitRange(settings, llm, intent.repo(), range.base(), range.head()));
                    }
                }
            } catch (Exception e) {
                printFriendlyError(e);
            }
        }
    }

    private static Settings applyChatOverride(Settings settings, ChatCommandParser.ChatIntent intent) {
        return intent.dryRunOverride() == null ? settings : settings.withDryRun(intent.dryRunOverride());
    }

    private static AgentRunResult reviewCommitRange(Settings settings, LlmClient llm, String repo, String base, String head) {
        GitEnvironment.LocalReviewContext local = GitEnvironment.localReviewContext(repo, base, head);
        if (local != null) {
            if (local.temporaryClone()) {
                System.out.println("本机未找到 clone，已临时 clone 到 target-project/ 生成 review 上下文并完成清理: " + local.repoPath());
            } else {
                System.out.println("使用本机 Git 仓库生成 review 上下文: " + local.repoPath());
            }
            return new CodeReviewAgent(settings, llm).reviewLocalGitCommits(repo, base, head, local.changedFiles(), local.diff(), local.commits(), local.author());
        }
        if (!settings.hasGithubCredentials()) {
            throw new IllegalStateException("本机未找到可用 clone，且 GITHUB_TOKEN 未配置，无法读取 " + repo + " 的 commit diff。");
        }
        return new CodeReviewAgent(settings, llm).reviewCommits(repo, base, head);
    }

    @SuppressWarnings("unchecked")
    private static CommitRange latestCommitRange(Settings settings, String repo) {
        GitEnvironment.CheckResult gitCheck = GitEnvironment.checkRepoAccess(repo);
        GitEnvironment.CommitRange gitRange = GitEnvironment.latestCommitRange(repo);
        if (gitRange != null) {
            return new CommitRange(gitRange.base(), gitRange.head());
        }
        String[] parts = repo.split("/", 2);
        Object raw;
        try {
            raw = new GitHubTools(settings.githubToken()).listCommits(Map.of("owner", parts[0], "repo", parts[1], "per_page", 2));
        } catch (Exception e) {
            printGitHubTokenCheck(settings, repo);
            if (!gitCheck.accessible()) {
                printGitCheck(gitCheck);
                throw new IllegalStateException("无法通过本机 Git 或 GITHUB_TOKEN 读取仓库 " + repo + " 的默认分支提交。请先配置 GitHub Git 凭据或授权 GITHUB_TOKEN。");
            }
            throw e;
        }
        if (!(raw instanceof java.util.List<?> list) || list.size() < 2) {
            throw new IllegalStateException("仓库 " + repo + " 默认分支提交数不足，无法自动选择 base/head。请显式提供两个 commit。");
        }
        Map<String, Object> head = (Map<String, Object>) list.get(0);
        Map<String, Object> base = (Map<String, Object>) list.get(1);
        return new CommitRange(String.valueOf(base.get("sha")), String.valueOf(head.get("sha")));
    }

    private static void printChatHelp() {
        System.out.println("""
                可以这样说：
                  帮我 review https://github.com/owner/repo/pull/123
                  帮我 review https://github.com/owner/repo
                  看一下 owner/repo PR 123
                  对 owner/repo 从 abc1234 到 def5678 两个 commit 做 CR
                  review https://github.com/owner/repo/compare/main...feature-branch
                  用 dry-run 看 owner/repo #123
                  live review owner/repo base main head feature-branch
                """);
    }

    private record CommitRange(String base, String head) {
    }

    private static void maybePrintGitCheck(String line) {
        String repo = extractRepoForCheck(line);
        if (repo == null) {
            return;
        }
        GitEnvironment.CheckResult check = GitEnvironment.checkRepoAccess(repo);
        if (!check.accessible()) {
            printGitCheck(check);
        }
    }

    private static String extractRepoForCheck(String line) {
        ChatCommandParser.ChatIntent asCommits = ChatCommandParser.parse(line + " base placeholder-a head placeholder-b");
        if (asCommits.repo() != null) {
            return asCommits.repo();
        }
        return null;
    }

    private static void printGitCheck(String repo) {
        printGitCheck(GitEnvironment.checkRepoAccess(repo));
    }

    private static void printGitHubTokenCheck(Settings settings, String repo) {
        GitHubTokenEnvironment.CheckResult check = GitHubTokenEnvironment.checkRepo(settings, repo);
        System.out.println(check.accessible() ? "GitHub token access: ok" : "GitHub token access: unavailable");
        System.out.println(check.message());
    }

    private static void printGitCheck(GitEnvironment.CheckResult check) {
        System.out.println(check.accessible() ? "Git access: ok" : "Git access: unavailable");
        System.out.println(check.message());
        for (String detail : check.details()) {
            System.out.println("- " + detail);
        }
        if (check.anonymousOrUnauthorized()) {
            System.out.println("请先在当前设备配置 GitHub 凭据，或把该仓库授权给 .env 中的 GITHUB_TOKEN。");
        }
    }

    private static void inspectTrace(Path trace) throws Exception {
        int i = 0;
        for (String line : Files.readAllLines(trace)) {
            if (line.isBlank()) {
                continue;
            }
            Map<?, ?> record = Jsons.MAPPER.readValue(line, Map.class);
            Object typeValue = record.get("event_type");
            if (typeValue == null) {
                typeValue = record.get("type");
            }
            String type = String.valueOf(typeValue == null ? "?" : typeValue);
            System.out.printf("[%03d] %s", i++, type);
            if (record.containsKey("phase")) {
                System.out.print(" phase=" + record.get("phase"));
            }
            if (record.containsKey("summary")) {
                System.out.print(" summary=" + record.get("summary"));
            }
            if (record.containsKey("issues_found")) {
                System.out.print(" issues=" + record.get("issues_found"));
            }
            System.out.println();
        }
    }

    private static String required(Map<String, String> opts, String key) {
        String value = opts.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing required option: " + key);
        }
        return value;
    }

    private static void requireReviewCredentials(Settings settings) {
        requireLlmCredentials(settings);
        if (!settings.hasGithubCredentials()) {
            throw new IllegalStateException("GITHUB_TOKEN is required to read pull request context.");
        }
    }

    private static void requireLlmCredentials(Settings settings) {
        if (!settings.hasLlmCredentials()) {
            throw new IllegalStateException("OPENAI_BASE_URL, OPENAI_API_KEY, and OPENAI_MODEL are required for review.");
        }
    }

    private static void printFriendlyError(Exception e) {
        String message = e.getMessage() == null || e.getMessage().isBlank()
                ? e.getClass().getSimpleName()
                : e.getMessage();
        System.out.println("Status: failed");
        System.out.println("Summary: " + message);
        System.out.println("Hint: fix the configuration or command arguments, then rerun the same command. Use --dry-run to avoid GitHub writes.");
    }

    private static void usage() {
        System.out.println("""
                Usage:
                  ./gradlew run --args="review --repo owner/name --pr 123"
                  ./gradlew run --args="review --pr-url https://github.com/owner/name/pull/123"
                  ./gradlew run --args="chat"
                  ./gradlew run --args="git-check --repo owner/name"
                  ./gradlew run --args="github-token-check --repo owner/name"
                  ./gradlew run --args="review-commits --repo owner/name --base <sha-or-ref> --head <sha-or-ref> --dry-run"
                  ./gradlew run --args="review-commits --repo https://github.com/owner/name --base main --head feature-branch --dry-run"
                  ./gradlew run --args="batch-review --prs prs.txt"
                  ./gradlew run --args="health-report --repo owner/name"
                  ./gradlew run --args="export-sft --input data/traces --output ../datasets/SFT/sft.jsonl"
                  ./gradlew run --args="export-dpo --input data/traces --output ../datasets/DPO/dpo.jsonl"
                  ./gradlew run --args="export-datasets --input data/traces"
                  ./gradlew run --args="inspect --trace data/traces/<session>.jsonl"
                  ./gradlew run --args="init-memory"
                """);
    }
}
