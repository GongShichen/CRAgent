package com.cragent.cli;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ChatCommandParser {
    private static final Pattern PR_URL = Pattern.compile("github\\.com/([^/\\s]+)/([^/\\s]+)/pull/(\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern COMPARE_URL = Pattern.compile("github\\.com/([^/\\s]+)/([^/\\s]+)/compare/([^\\s]+?)\\.\\.\\.([^\\s?#]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern REPO_URL = Pattern.compile("(?:https?://github\\.com/|git@github\\.com:)([^/\\s:]+)/([^/\\s]+?)(?:\\.git)?(?:[\\s/#?]|$)", Pattern.CASE_INSENSITIVE);
    private static final Pattern SHORT_REPO = Pattern.compile("\\b([A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+)\\b");
    private static final Pattern PR_NUMBER = Pattern.compile("(?i)(?:pull request|pr|#)\\s*#?(\\d+)");
    private static final Pattern SHA = Pattern.compile("\\b[0-9a-fA-F]{7,40}\\b");
    private static final Pattern BASE = Pattern.compile("(?i)(?:base|from|起点|从)\\s*[:=]?\\s*([A-Za-z0-9._/-]{2,})");
    private static final Pattern HEAD = Pattern.compile("(?i)(?:head|to|target|终点|到)\\s*[:=]?\\s*([A-Za-z0-9._/-]{2,})");

    private ChatCommandParser() {
    }

    public static ChatIntent parse(String input) {
        String text = input == null ? "" : input.trim();
        if (text.isBlank()) {
            return ChatIntent.unknown("请输入 GitHub 仓库和 PR 编号，或仓库和 base/head 两个 commit。");
        }
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.equals("exit") || lower.equals("quit") || lower.equals("q") || lower.equals("退出")) {
            return ChatIntent.exit();
        }
        if (lower.equals("help") || lower.equals("?") || lower.equals("帮助")) {
            return ChatIntent.help();
        }
        Boolean dryRun = dryRunOverride(lower);

        Matcher prUrl = PR_URL.matcher(text);
        if (prUrl.find()) {
            return ChatIntent.pr(prUrl.group(1) + "/" + prUrl.group(2), Integer.parseInt(prUrl.group(3)), dryRun);
        }

        Matcher compareUrl = COMPARE_URL.matcher(text);
        if (compareUrl.find()) {
            return ChatIntent.commits(compareUrl.group(1) + "/" + compareUrl.group(2), cleanRef(compareUrl.group(3)), cleanRef(compareUrl.group(4)), dryRun);
        }

        String repo = extractRepo(text);
        if (repo == null) {
            return ChatIntent.unknown("我还缺仓库信息。请给 owner/name 或 https://github.com/owner/name。");
        }

        Matcher prNumber = PR_NUMBER.matcher(text);
        if (prNumber.find()) {
            return ChatIntent.pr(repo, Integer.parseInt(prNumber.group(1)), dryRun);
        }

        String base = labeledRef(BASE, text);
        String head = labeledRef(HEAD, text);
        if (base != null && head != null) {
            return ChatIntent.commits(repo, base, head, dryRun);
        }

        List<String> hashes = hashes(text);
        if (hashes.size() >= 2) {
            return ChatIntent.commits(repo, hashes.get(0), hashes.get(1), dryRun);
        }

        return ChatIntent.repo(repo, dryRun);
    }

    private static String extractRepo(String text) {
        Matcher url = REPO_URL.matcher(text);
        if (url.find()) {
            return normalizeRepo(url.group(1) + "/" + url.group(2));
        }
        Matcher shortRepo = SHORT_REPO.matcher(text);
        if (shortRepo.find()) {
            return normalizeRepo(shortRepo.group(1));
        }
        return null;
    }

    public static String normalizeRepo(String repo) {
        String value = repo.trim();
        if (value.startsWith("https://github.com/")) {
            value = value.substring("https://github.com/".length());
        } else if (value.startsWith("http://github.com/")) {
            value = value.substring("http://github.com/".length());
        } else if (value.startsWith("git@github.com:")) {
            value = value.substring("git@github.com:".length());
        }
        value = value.replaceFirst("\\.git$", "").replaceAll("/+$", "");
        String[] parts = value.split("/");
        if (parts.length < 2 || parts[0].isBlank() || parts[1].isBlank()) {
            return null;
        }
        return parts[0] + "/" + parts[1];
    }

    private static Boolean dryRunOverride(String lower) {
        if (lower.contains("--dry-run") || lower.contains("dry run") || lower.contains("dry-run") || lower.contains("试跑")) {
            return true;
        }
        if (lower.contains("--live") || lower.contains("live") || lower.contains("真实执行") || lower.contains("不要 dry")) {
            return false;
        }
        return null;
    }

    private static String labeledRef(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? cleanRef(matcher.group(1)) : null;
    }

    private static List<String> hashes(String text) {
        List<String> out = new ArrayList<>();
        Matcher matcher = SHA.matcher(text);
        while (matcher.find()) {
            out.add(matcher.group());
        }
        return out;
    }

    private static String cleanRef(String value) {
        return value.replaceAll("[,，。；;]+$", "");
    }

    public record ChatIntent(Type type, String repo, Integer pr, String base, String head, Boolean dryRunOverride, String message) {
        public static ChatIntent pr(String repo, int pr, Boolean dryRunOverride) {
            return new ChatIntent(Type.PR, repo, pr, null, null, dryRunOverride, null);
        }

        public static ChatIntent commits(String repo, String base, String head, Boolean dryRunOverride) {
            return new ChatIntent(Type.COMMITS, repo, null, base, head, dryRunOverride, null);
        }

        public static ChatIntent unknown(String message) {
            return new ChatIntent(Type.UNKNOWN, null, null, null, null, null, message);
        }

        public static ChatIntent repo(String repo, Boolean dryRunOverride) {
            return new ChatIntent(Type.REPO, repo, null, null, null, dryRunOverride, null);
        }

        public static ChatIntent repoAudit(String repo, Boolean dryRunOverride) {
            return new ChatIntent(Type.REPO_AUDIT, repo, null, null, null, dryRunOverride, null);
        }

        public static ChatIntent help() {
            return new ChatIntent(Type.HELP, null, null, null, null, null, null);
        }

        public static ChatIntent exit() {
            return new ChatIntent(Type.EXIT, null, null, null, null, null, null);
        }
    }

    public enum Type {
        PR,
        COMMITS,
        REPO_AUDIT,
        REPO,
        HELP,
        EXIT,
        UNKNOWN
    }
}
