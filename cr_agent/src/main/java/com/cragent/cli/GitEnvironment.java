package com.cragent.cli;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

public final class GitEnvironment {
    private GitEnvironment() {
    }

    public static CheckResult checkRepoAccess(String repo) {
        String normalized = ChatCommandParser.normalizeRepo(repo);
        if (normalized == null) {
            return new CheckResult(false, true, "仓库格式无法识别：" + repo, List.of());
        }
        List<String> details = new ArrayList<>();
        CommandResult user = run(Duration.ofSeconds(5), "git", "config", "--global", "--get", "user.name");
        CommandResult email = run(Duration.ofSeconds(5), "git", "config", "--global", "--get", "user.email");
        CommandResult helper = run(Duration.ofSeconds(5), "git", "config", "--global", "--get", "credential.helper");
        details.add("git user.name: " + valueOrUnset(user.stdout()));
        details.add("git user.email: " + valueOrUnset(email.stdout()));
        details.add("git credential.helper: " + valueOrUnset(helper.stdout()));

        CommandResult gh = run(Duration.ofSeconds(8), "gh", "auth", "status");
        details.add("gh auth: " + (gh.exitCode() == 0 ? "available" : "not available"));

        CommandResult remote = run(Duration.ofSeconds(15), "git", "ls-remote", "https://github.com/" + normalized + ".git", "HEAD");
        if (remote.exitCode() == 0) {
            return new CheckResult(true, false, "Git 可以访问 " + normalized, details);
        }
        String output = (remote.stderr() + "\n" + remote.stdout()).trim();
        boolean anonymous = output.contains("could not read Username")
                || output.contains("Authentication failed")
                || output.contains("Repository not found")
                || output.contains("not found");
        String message = anonymous
                ? "当前设备没有可用于 " + normalized + " 的 GitHub Git 凭据；匿名或未授权 Git 访问无法读取该仓库。"
                : "Git 无法访问 " + normalized + "：" + output;
        return new CheckResult(false, anonymous, message, details);
    }

    public static CommitRange latestCommitRange(String repo) {
        String normalized = ChatCommandParser.normalizeRepo(repo);
        if (normalized == null) {
            return null;
        }
        String url = "https://github.com/" + normalized + ".git";
        CommandResult head = run(Duration.ofSeconds(15), "git", "ls-remote", url, "HEAD");
        if (head.exitCode() != 0) {
            url = "git@github.com:" + normalized + ".git";
            head = run(Duration.ofSeconds(15), "git", "ls-remote", url, "HEAD");
        }
        if (head.exitCode() != 0) {
            return null;
        }
        String headSha = firstColumn(head.stdout());
        if (headSha == null || headSha.isBlank()) {
            return null;
        }
        CommandResult parent = run(Duration.ofSeconds(25), "git", "ls-remote", url, headSha + "^");
        String baseSha = firstColumn(parent.stdout());
        if (parent.exitCode() != 0 || baseSha == null || baseSha.isBlank()) {
            baseSha = parentFromTemporaryFetch(url, headSha);
        }
        if (baseSha == null || baseSha.isBlank()) {
            return null;
        }
        return new CommitRange(baseSha, headSha);
    }

    public static java.nio.file.Path findLocalRepository(String repo) {
        String normalized = ChatCommandParser.normalizeRepo(repo);
        if (normalized == null) {
            return null;
        }
        java.nio.file.Path root = java.nio.file.Path.of(System.getProperty("user.home"), "Documents");
        if (!java.nio.file.Files.exists(root)) {
            root = java.nio.file.Path.of(System.getProperty("user.home"));
        }
        try (java.util.stream.Stream<java.nio.file.Path> stream = java.nio.file.Files.find(root, 6,
                (path, attrs) -> attrs.isDirectory() && path.getFileName() != null && ".git".equals(path.getFileName().toString()))) {
            return stream
                    .map(java.nio.file.Path::getParent)
                    .filter(path -> hasRemote(path, normalized))
                    .findFirst()
                    .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    public static LocalReviewContext localReviewContext(String repo, String base, String head) {
        java.nio.file.Path path = findLocalRepository(repo);
        if (path == null) {
            return null;
        }
        runIn(path, Duration.ofSeconds(45), "git", "fetch", "--quiet", "--all", "--prune");
        String diff = runIn(path, Duration.ofSeconds(30), "git", "diff", "--find-renames", "--unified=80", base, head).stdout();
        String numstat = runIn(path, Duration.ofSeconds(15), "git", "diff", "--numstat", "--find-renames", base, head).stdout();
        String names = runIn(path, Duration.ofSeconds(15), "git", "diff", "--name-status", "--find-renames", base, head).stdout();
        String commits = runIn(path, Duration.ofSeconds(15), "git", "log", "--format=%H%x09%an%x09%ae%x09%s", base + ".." + head).stdout();
        String author = "unknown";
        String firstCommit = commits == null ? "" : commits.lines().findFirst().orElse("");
        String[] parts = firstCommit.split("\\t");
        if (parts.length >= 2 && !parts[1].isBlank()) {
            author = parts[1];
        }
        return new LocalReviewContext(path, changedFiles(numstat, names, diff), diff, commitRows(commits), author);
    }

    private static String parentFromTemporaryFetch(String url, String headSha) {
        try {
            java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("cr-agent-git-");
            try {
                CommandResult init = runIn(dir, Duration.ofSeconds(15), "git", "init", "--quiet");
                if (init.exitCode() != 0) {
                    return null;
                }
                CommandResult fetch = runIn(dir, Duration.ofSeconds(60), "git", "fetch", "--quiet", "--depth=2", url, headSha);
                if (fetch.exitCode() != 0) {
                    return null;
                }
                CommandResult parent = runIn(dir, Duration.ofSeconds(15), "git", "rev-parse", headSha + "^");
                return parent.exitCode() == 0 ? parent.stdout().trim() : null;
            } finally {
                deleteRecursively(dir);
            }
        } catch (IOException e) {
            return null;
        }
    }

    private static CommandResult runIn(java.nio.file.Path dir, Duration timeout, String... command) {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(dir.toFile());
        return run(builder, timeout);
    }

    private static boolean hasRemote(java.nio.file.Path path, String repo) {
        CommandResult remotes = runIn(path, Duration.ofSeconds(5), "git", "remote", "-v");
        String normalized = repo.toLowerCase();
        return remotes.stdout().toLowerCase().contains("github.com:" + normalized + ".git")
                || remotes.stdout().toLowerCase().contains("github.com/" + normalized + ".git")
                || remotes.stdout().toLowerCase().contains("github.com/" + normalized + " ");
    }

    private static java.util.List<java.util.Map<String, Object>> changedFiles(String numstat, String names, String diff) {
        java.util.Map<String, String> statuses = new java.util.LinkedHashMap<>();
        if (names != null) {
            for (String line : names.split("\\R")) {
                if (line.isBlank()) {
                    continue;
                }
                String[] parts = line.split("\\t");
                String status = parts[0];
                String file = parts.length >= 3 ? parts[2] : (parts.length >= 2 ? parts[1] : "");
                if (!file.isBlank()) {
                    statuses.put(file, status.substring(0, 1));
                }
            }
        }
        java.util.List<java.util.Map<String, Object>> out = new java.util.ArrayList<>();
        if (numstat != null) {
            for (String line : numstat.split("\\R")) {
                if (line.isBlank()) {
                    continue;
                }
                String[] parts = line.split("\\t");
                if (parts.length < 3) {
                    continue;
                }
                String file = parts.length >= 4 ? parts[3] : parts[2];
                java.util.Map<String, Object> item = new java.util.LinkedHashMap<>();
                item.put("filename", file);
                item.put("status", statusName(statuses.getOrDefault(file, "M")));
                item.put("additions", parseCount(parts[0]));
                item.put("deletions", parseCount(parts[1]));
                item.put("patch", patchForFile(diff, file));
                out.add(item);
            }
        }
        return out;
    }

    private static java.util.List<java.util.Map<String, Object>> commitRows(String commits) {
        java.util.List<java.util.Map<String, Object>> out = new java.util.ArrayList<>();
        if (commits == null) {
            return out;
        }
        for (String line : commits.split("\\R")) {
            if (line.isBlank()) {
                continue;
            }
            String[] parts = line.split("\\t", 4);
            java.util.Map<String, Object> item = new java.util.LinkedHashMap<>();
            item.put("sha", parts.length > 0 ? parts[0] : "");
            item.put("author", parts.length > 1 ? parts[1] : "");
            item.put("email", parts.length > 2 ? parts[2] : "");
            item.put("message", parts.length > 3 ? parts[3] : "");
            out.add(item);
        }
        return out;
    }

    private static String patchForFile(String diff, String file) {
        if (diff == null || diff.isBlank()) {
            return "";
        }
        String marker = "diff --git ";
        String[] sections = diff.split("(?m)^diff --git ");
        for (String section : sections) {
            if (section.contains(" b/" + file + "\n") || section.contains(" b/" + file + "\r\n") || section.contains(" " + file + "\n")) {
                return marker + section;
            }
        }
        return "";
    }

    private static int parseCount(String value) {
        return value == null || "-".equals(value) ? 0 : Integer.parseInt(value);
    }

    private static String statusName(String status) {
        return switch (status) {
            case "A" -> "added";
            case "D" -> "removed";
            case "R" -> "renamed";
            default -> "modified";
        };
    }

    private static String valueOrUnset(String value) {
        String trimmed = value == null ? "" : value.trim();
        return trimmed.isBlank() ? "<unset>" : trimmed;
    }

    private static CommandResult run(Duration timeout, String... command) {
        return run(new ProcessBuilder(command), timeout);
    }

    private static CommandResult run(ProcessBuilder builder, Duration timeout) {
        try {
            Process process = builder.start();
            boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                return new CommandResult(124, "", "command timed out");
            }
            String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            return new CommandResult(process.exitValue(), stdout, stderr);
        } catch (IOException e) {
            return new CommandResult(127, "", e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new CommandResult(130, "", "interrupted");
        }
    }

    private static String firstColumn(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String firstLine = value.strip().split("\\R", 2)[0];
        String[] parts = firstLine.split("\\s+");
        return parts.length == 0 ? null : parts[0];
    }

    private static void deleteRecursively(java.nio.file.Path path) throws IOException {
        if (!java.nio.file.Files.exists(path)) {
            return;
        }
        try (java.util.stream.Stream<java.nio.file.Path> stream = java.nio.file.Files.walk(path)) {
            stream.sorted(java.util.Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            java.nio.file.Files.deleteIfExists(p);
                        } catch (IOException ignored) {
                            // Best effort cleanup.
                        }
                    });
        }
    }

    public record CheckResult(boolean accessible, boolean anonymousOrUnauthorized, String message, List<String> details) {
    }

    public record CommitRange(String base, String head) {
    }

    public record LocalReviewContext(java.nio.file.Path repoPath, java.util.List<java.util.Map<String, Object>> changedFiles, String diff,
                                     java.util.List<java.util.Map<String, Object>> commits, String author) {
    }

    private record CommandResult(int exitCode, String stdout, String stderr) {
    }
}
