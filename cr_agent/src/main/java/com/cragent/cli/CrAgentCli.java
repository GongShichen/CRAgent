package com.cragent.cli;

import com.cragent.agent.CodeReviewAgent;
import com.cragent.agent.LspAnalyzer;
import com.cragent.agent.LspServerRegistry;
import com.cragent.config.Settings;
import com.cragent.llm.LlmClient;
import com.cragent.llm.OpenAiCompatibleClient;
import com.cragent.model.AgentRunResult;
import com.cragent.model.ReviewIssue;
import com.cragent.model.ToolResult;
import com.cragent.tools.GitHubTools;

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
        if (args.length > 0) {
            System.out.println("CR Agent 现在只保留 chat 模式；命令行参数会被忽略。直接使用 ./gradlew run 启动。");
        }
        Settings settings = Settings.load();
        chat(settings);
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

    private static void chat(Settings baseSettings) {
        System.out.println("CR Agent chat 已启动。输入 GitHub PR 或 commit 范围，我会解析并执行 review。输入 help 查看示例，exit 退出。");
        printLspPreflight(baseSettings);
        Scanner scanner = new Scanner(System.in);
        while (true) {
            System.out.print("cr-agent> ");
            if (!scanner.hasNextLine()) {
                System.out.println();
                return;
            }
            String line = scanner.nextLine().trim();
            ChatCommandParser.ChatIntent intent = routeChatIntent(baseSettings, line);
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
                        System.out.printf("识别到 commit diff CR: %s %s...%s (%s)%n", intent.repo(), intent.base(), intent.head(), settings.dryRun() ? "dry-run" : "live");
                        LlmClient llm = new OpenAiCompatibleClient(settings);
                        printReview(reviewCommitRange(settings, llm, intent.repo(), intent.base(), intent.head()));
                    }
                    case REPO_AUDIT -> {
                        Settings settings = applyChatOverride(baseSettings, intent);
                        requireLlmCredentials(settings);
                        System.out.printf("识别到全量仓库 CR: %s (%s)%n", intent.repo(), settings.dryRun() ? "dry-run" : "live");
                        LlmClient llm = new OpenAiCompatibleClient(settings);
                        printReview(new CodeReviewAgent(settings, llm).reviewRepository(intent.repo()));
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

    private static void printLspPreflight(Settings settings) {
        if (!settings.lspEnabled()) {
            System.out.println("LSP: disabled by CR_AGENT_LSP_ENABLED=false");
            return;
        }
        System.out.println("LSP server preflight:");
        boolean missing = false;
        for (LspAnalyzer.ServerSpec server : LspAnalyzer.supportedServers()) {
            boolean available = LspServerRegistry.commandExists(server.executable());
            System.out.printf("- %s: %s (%s)%n", server.language(), available ? "available" : "missing", server.command());
            if (!available) {
                missing = true;
                System.out.println("  install: " + server.installHint());
            }
        }
        if (missing) {
            System.out.println("缺失的 LSP server 不会自动安装；相关语言在本轮任务中会跳过 LSP，上下文仍会继续用 diff/static/repo index。");
        }
    }

    private static Settings applyChatOverride(Settings settings, ChatCommandParser.ChatIntent intent) {
        return intent.dryRunOverride() == null ? settings : settings.withDryRun(intent.dryRunOverride());
    }

    private static ChatCommandParser.ChatIntent routeChatIntent(Settings settings, String line) {
        ChatCommandParser.ChatIntent local = ChatCommandParser.parse(line);
        if (local.type() == ChatCommandParser.Type.EXIT || local.type() == ChatCommandParser.Type.HELP || !settings.hasLlmCredentials()) {
            return local;
        }
        return new LlmIntentRouter(new OpenAiCompatibleClient(settings)).route(line);
    }

    private static AgentRunResult reviewCommitRange(Settings settings, LlmClient llm, String repo, String base, String head) {
        GitEnvironment.LocalReviewContext local = GitEnvironment.localReviewContext(repo, base, head);
        if (local != null) {
            if (local.temporaryClone()) {
                System.out.println("本机未找到 clone，已临时 clone 到 target-project/ 生成 review 上下文并完成清理: " + local.repoPath());
            } else {
                System.out.println("使用本机 Git 仓库生成 review 上下文: " + local.repoPath());
            }
            return new CodeReviewAgent(settings, llm).reviewLocalGitCommits(repo, base, head, local.changedFiles(), local.diff(), local.commits(), local.author(), local.repoPath());
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
                  对整个 https://github.com/owner/repo.git 做 CR
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
        System.out.println("Hint: fix the configuration and rerun ./gradlew run.");
    }

    private static void usage() {
        System.out.println("""
                Usage:
                  ./gradlew run
                """);
    }
}
