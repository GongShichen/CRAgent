package com.cragent.tools;

import com.fasterxml.jackson.core.type.TypeReference;
import com.cragent.util.Jsons;
import com.cragent.util.Retry;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.cragent.tools.ToolSchemas.*;

public class GitHubTools {
    private final String token;
    private final HttpClient client = HttpClient.newHttpClient();

    public GitHubTools(String token) {
        this.token = token == null ? "" : token;
    }

    public boolean available() {
        return !token.isBlank();
    }

    public void register(ToolRouter router) {
        router.register(new ToolSpec("get_pull_request", "Get PR metadata.", object(Map.of(
                "owner", str("Repository owner"),
                "repo", str("Repository name"),
                "pull_number", integer("Pull request number")
        ), List.of("owner", "repo", "pull_number")), this::getPullRequest, false));
        router.register(new ToolSpec("get_pull_request_files", "Get files changed in a PR.", object(Map.of(
                "owner", str("Repository owner"),
                "repo", str("Repository name"),
                "pull_number", integer("Pull request number")
        ), List.of("owner", "repo", "pull_number")), this::getPullRequestFiles, false));
        router.register(new ToolSpec("get_pr_diff", "Get PR diff text.", object(Map.of(
                "owner", str("Repository owner"),
                "repo", str("Repository name"),
                "pr", integer("Pull request number")
        ), List.of("repo", "pr")), this::getPrDiff, false));
        router.register(new ToolSpec("list_changed_files", "List PR changed files.", object(Map.of(
                "owner", str("Repository owner"),
                "repo", str("Repository name"),
                "pr", integer("Pull request number")
        ), List.of("repo", "pr")), this::listChangedFiles, false));
        router.register(new ToolSpec("get_commit_compare", "Compare two commits or refs.", object(Map.of(
                "owner", str("Repository owner"),
                "repo", str("Repository name"),
                "base", str("Base commit or ref"),
                "head", str("Head commit or ref")
        ), List.of("repo", "base", "head")), this::getCommitCompare, false));
        router.register(new ToolSpec("get_commit_compare_diff", "Get diff between two commits or refs.", object(Map.of(
                "owner", str("Repository owner"),
                "repo", str("Repository name"),
                "base", str("Base commit or ref"),
                "head", str("Head commit or ref")
        ), List.of("repo", "base", "head")), this::getCommitCompareDiff, false));
        router.register(new ToolSpec("list_commits", "List commits for a branch or SHA.", object(Map.of(
                "owner", str("Repository owner"),
                "repo", str("Repository name"),
                "sha", str("Branch or SHA"),
                "per_page", integer("Commit count")
        ), List.of("owner", "repo")), this::listCommits, false));
        router.register(new ToolSpec("get_file_contents", "Read a file.", object(Map.of(
                "owner", str("Repository owner"),
                "repo", str("Repository name"),
                "path", str("File path"),
                "branch", str("Branch or SHA")
        ), List.of("owner", "repo", "path")), this::getFileContents, false));
        router.register(new ToolSpec("list_review_comments", "List PR review comments.", object(Map.of(
                "owner", str("Repository owner"),
                "repo", str("Repository name"),
                "pr", integer("Pull request number")
        ), List.of("repo", "pr")), this::listReviewComments, false));
        router.register(new ToolSpec("list_checks", "List CI checks.", object(Map.of(
                "owner", str("Repository owner"),
                "repo", str("Repository name"),
                "pr", integer("Pull request number")
        ), List.of("repo", "pr")), this::listChecks, false));
        router.register(new ToolSpec("get_pull_request_status", "Get PR status checks.", object(Map.of(
                "owner", str("Repository owner"),
                "repo", str("Repository name"),
                "pull_number", integer("Pull request number")
        ), List.of("owner", "repo", "pull_number")), this::getPullRequestStatus, false));
        router.register(new ToolSpec("search_code", "Search code in repository.", object(Map.of(
                "owner", str("Repository owner"),
                "repo", str("Repository name"),
                "query", str("Search query")
        ), List.of("repo", "query")), this::searchCode, false));
        router.register(new ToolSpec("list_repository_tree", "List repository files for architecture/test/security context.", object(Map.of(
                "owner", str("Repository owner"),
                "repo", str("Repository name"),
                "branch", str("Branch or SHA"),
                "recursive", bool("Whether to recursively list files")
        ), List.of("owner", "repo")), this::listRepositoryTree, false));
        router.register(new ToolSpec("get_surrounding_lines", "Read nearby source lines around a target line.", object(Map.of(
                "owner", str("Repository owner"),
                "repo", str("Repository name"),
                "path", str("File path"),
                "branch", str("Branch or SHA"),
                "line", integer("Target line"),
                "context", integer("Number of lines before and after")
        ), List.of("owner", "repo", "path", "line")), this::getSurroundingLines, false));
        router.register(new ToolSpec("find_related_tests", "Find likely existing tests for a changed source file.", object(Map.of(
                "owner", str("Repository owner"),
                "repo", str("Repository name"),
                "source_path", str("Changed source file path"),
                "branch", str("Branch or SHA")
        ), List.of("owner", "repo", "source_path")), this::findRelatedTests, false));
        router.register(new ToolSpec("get_dependency_manifests", "Read common dependency/build manifests for risk review.", object(Map.of(
                "owner", str("Repository owner"),
                "repo", str("Repository name"),
                "branch", str("Branch or SHA")
        ), List.of("owner", "repo")), this::getDependencyManifests, false));
        router.register(new ToolSpec("scan_sensitive_paths", "List repository paths that look security-, auth-, payment-, migration-, or config-sensitive.", object(Map.of(
                "owner", str("Repository owner"),
                "repo", str("Repository name"),
                "branch", str("Branch or SHA"),
                "limit", integer("Maximum paths to return")
        ), List.of("owner", "repo")), this::scanSensitivePaths, false));
        router.register(new ToolSpec("create_pull_request_review", "Create a PR review.", object(Map.of(
                "owner", str("Repository owner"),
                "repo", str("Repository name"),
                "pull_number", integer("Pull request number"),
                "event", str("APPROVE, REQUEST_CHANGES, or COMMENT"),
                "body", str("Review body"),
                "comments", array("Inline comments")
        ), List.of("owner", "repo", "pull_number", "event", "body")), this::createPullRequestReview, true));
        router.register(new ToolSpec("submit_review_comments", "Submit review comments.", object(Map.of(
                "owner", str("Repository owner"),
                "repo", str("Repository name"),
                "pr", integer("Pull request number"),
                "body", str("Review body"),
                "comments", array("Inline comments")
        ), List.of("repo", "pr", "body")), this::submitReviewComments, true));
        router.register(new ToolSpec("create_branch", "Create a branch.", object(Map.of(
                "owner", str("Repository owner"),
                "repo", str("Repository name"),
                "branch", str("New branch"),
                "from_branch", str("Source branch")
        ), List.of("owner", "repo", "branch")), this::createBranch, true));
        router.register(new ToolSpec("create_or_update_file", "Create or update file.", object(Map.of(
                "owner", str("Repository owner"),
                "repo", str("Repository name"),
                "path", str("File path"),
                "content", str("File content"),
                "message", str("Commit message"),
                "branch", str("Branch"),
                "sha", str("Existing file SHA")
        ), List.of("owner", "repo", "path", "content", "message", "branch")), this::createOrUpdateFile, true));
        router.register(new ToolSpec("create_pull_request", "Create pull request.", object(Map.of(
                "owner", str("Repository owner"),
                "repo", str("Repository name"),
                "title", str("Title"),
                "head", str("Head branch"),
                "base", str("Base branch"),
                "body", str("Body")
        ), List.of("owner", "repo", "title", "head", "base")), this::createPullRequest, true));
        router.register(new ToolSpec("add_issue_comment", "Add issue/PR comment.", object(Map.of(
                "owner", str("Repository owner"),
                "repo", str("Repository name"),
                "issue_number", integer("Issue number"),
                "body", str("Comment body")
        ), List.of("owner", "repo", "issue_number", "body")), this::addIssueComment, true));
        router.register(new ToolSpec("detect_test_framework", "Detect test framework from repository config.", object(Map.of(
                "owner", str("Repository owner"),
                "repo", str("Repository name"),
                "branch", str("Branch")
        ), List.of("owner", "repo")), this::detectTestFramework, false));
    }

    public Object getPullRequest(Map<String, Object> args) {
        String repo = fullRepo(args);
        int pr = prNumber(args);
        Object raw = request("GET", "/repos/" + repo + "/pulls/" + pr, null, "application/vnd.github+json");
        if (raw instanceof Map<?, ?> map) {
            return compactPullRequest(map);
        }
        return raw;
    }

    public Object getPullRequestFiles(Map<String, Object> args) {
        return listChangedFiles(Map.of("repo", args.get("owner") + "/" + args.get("repo"), "pr", args.get("pull_number")));
    }

    public Object getPrDiff(Map<String, Object> args) {
        String repo = fullRepo(args);
        int pr = prNumber(args);
        return requestText("GET", "/repos/" + repo + "/pulls/" + pr, null, "application/vnd.github.v3.diff");
    }

    public Object listChangedFiles(Map<String, Object> args) {
        String repo = fullRepo(args);
        int pr = prNumber(args);
        return requestPagedArray("/repos/" + repo + "/pulls/" + pr + "/files?per_page=100", "application/vnd.github+json");
    }

    public Object getCommitCompare(Map<String, Object> args) {
        String repo = fullRepo(args);
        String base = strArg(args, "base");
        String head = strArg(args, "head");
        return request("GET", "/repos/" + repo + "/compare/" + url(base) + "..." + url(head), null, "application/vnd.github+json");
    }

    public Object getCommitCompareDiff(Map<String, Object> args) {
        String repo = fullRepo(args);
        String base = strArg(args, "base");
        String head = strArg(args, "head");
        return requestText("GET", "/repos/" + repo + "/compare/" + url(base) + "..." + url(head), null, "application/vnd.github.v3.diff");
    }

    public Object listCommits(Map<String, Object> args) {
        String repo = args.get("owner") + "/" + args.get("repo");
        String sha = (String) args.getOrDefault("sha", "");
        int perPage = intArg(args, "per_page", 10);
        String path = "/repos/" + repo + "/commits?per_page=" + Math.min(perPage, 100);
        if (!sha.isBlank()) {
            path += "&sha=" + url(sha);
        }
        return requestPagedArray(path, "application/vnd.github+json").stream().limit(perPage).toList();
    }

    public Object getFileContents(Map<String, Object> args) {
        String repo = fullRepo(args);
        String path = strArg(args, "path");
        String ref = (String) args.getOrDefault("ref", args.getOrDefault("branch", ""));
        String apiPath = "/repos/" + repo + "/contents/" + path + (ref.isBlank() ? "" : "?ref=" + url(ref));
        Object raw = request("GET", apiPath, null, "application/vnd.github+json");
        if (raw instanceof Map<?, ?> map && "base64".equals(map.get("encoding"))) {
            Map<String, Object> copy = new LinkedHashMap<>((Map<String, Object>) raw);
            copy.put("decoded_content", new String(Base64.getMimeDecoder().decode(String.valueOf(map.get("content"))), StandardCharsets.UTF_8));
            return copy;
        }
        return raw;
    }

    public Object listReviewComments(Map<String, Object> args) {
        if (!available()) {
            return List.of();
        }
        return requestPagedArray("/repos/" + fullRepo(args) + "/pulls/" + prNumber(args) + "/comments?per_page=100", "application/vnd.github+json");
    }

    @SuppressWarnings("unchecked")
    public Object listChecks(Map<String, Object> args) {
        Map<String, Object> pr = (Map<String, Object>) getPullRequest(args);
        Object head = pr.get("head");
        String sha = head instanceof Map<?, ?> h ? String.valueOf(h.get("sha")) : String.valueOf(pr.getOrDefault("head_sha", ""));
        return request("GET", "/repos/" + fullRepo(args) + "/commits/" + sha + "/check-runs", null, "application/vnd.github+json");
    }

    public Object getPullRequestStatus(Map<String, Object> args) {
        return listChecks(Map.of("repo", args.get("owner") + "/" + args.get("repo"), "pr", args.get("pull_number")));
    }

    public Object searchCode(Map<String, Object> args) {
        if (!available()) {
            return Map.of("items", List.of());
        }
        return request("GET", "/search/code?q=" + url(args.get("query") + " repo:" + fullRepo(args)) + "&per_page=100", null, "application/vnd.github+json");
    }

    public Object listRepositoryTree(Map<String, Object> args) {
        String branch = String.valueOf(args.getOrDefault("branch", "HEAD"));
        boolean recursive = Boolean.parseBoolean(String.valueOf(args.getOrDefault("recursive", true)));
        String suffix = recursive ? "?recursive=1" : "";
        return request("GET", "/repos/" + fullRepo(args) + "/git/trees/" + url(branch) + suffix, null, "application/vnd.github+json");
    }

    public Object getSurroundingLines(Map<String, Object> args) {
        int target = intArg(args, "line", 1);
        int context = Math.max(0, Math.min(intArg(args, "context", 8), 40));
        Object raw = getFileContents(args);
        String content = contentFromFileResult(raw);
        String[] lines = content.split("\\R", -1);
        int start = Math.max(1, target - context);
        int end = Math.min(lines.length, target + context);
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = start; i <= end; i++) {
            out.add(Map.of("line", i, "text", lines[i - 1]));
        }
        return Map.of("path", args.get("path"), "target_line", target, "start_line", start, "end_line", end, "lines", out);
    }

    public Object findRelatedTests(Map<String, Object> args) {
        String source = strArg(args, "source_path");
        String stem = source.substring(source.lastIndexOf('/') + 1).replaceAll("\\.[^.]+$", "").toLowerCase();
        Object tree = listRepositoryTree(args);
        List<Map<String, Object>> matches = treePaths(tree).stream()
                .filter(path -> {
                    String lower = path.toLowerCase();
                    return (lower.contains("test") || lower.contains("spec")) && lower.contains(stem);
                })
                .limit(25)
                .map(path -> Map.<String, Object>of("path", path, "reason", "name matches changed source file"))
                .toList();
        return Map.of("source_path", source, "related_tests", matches, "count", matches.size());
    }

    public Object getDependencyManifests(Map<String, Object> args) {
        List<String> manifests = List.of(
                "package.json", "pnpm-lock.yaml", "yarn.lock", "package-lock.json",
                "requirements.txt", "pyproject.toml", "setup.cfg",
                "pom.xml", "build.gradle", "build.gradle.kts",
                "Cargo.toml", "Cargo.lock", "go.mod", "go.sum",
                "composer.json", "Gemfile", "App.csproj", "Directory.Packages.props", "Package.swift"
        );
        List<Map<String, Object>> found = new ArrayList<>();
        for (String manifest : manifests) {
            try {
                Object raw = getFileContents(withExtra(args, Map.of("path", manifest)));
                String content = contentFromFileResult(raw);
                if (!content.isBlank()) {
                    found.add(Map.of("path", manifest, "content", content.length() > 6000 ? content.substring(0, 6000) : content));
                }
            } catch (RuntimeException ignored) {
                // Missing manifests are expected.
            }
        }
        return Map.of("manifests", found, "count", found.size());
    }

    public Object scanSensitivePaths(Map<String, Object> args) {
        int limit = Math.max(1, Math.min(intArg(args, "limit", 100), 500));
        Set<String> needles = Set.of("auth", "security", "permission", "access", "secret", "credential", "password",
                "token", "payment", "billing", "checkout", "migration", "config", "oauth", "jwt", "session");
        List<Map<String, Object>> matches = treePaths(listRepositoryTree(args)).stream()
                .filter(path -> {
                    String lower = path.toLowerCase();
                    return needles.stream().anyMatch(lower::contains);
                })
                .limit(limit)
                .map(path -> Map.<String, Object>of("path", path, "reason", "sensitive path keyword"))
                .toList();
        return Map.of("paths", matches, "count", matches.size());
    }

    public Object submitReviewComments(Map<String, Object> args) {
        String[] parts = fullRepo(args).split("/", 2);
        return createPullRequestReview(Map.of(
                "owner", parts[0],
                "repo", parts[1],
                "pull_number", args.get("pr"),
                "event", "COMMENT",
                "body", args.get("body"),
                "comments", args.getOrDefault("comments", List.of())
        ));
    }

    public Object createPullRequestReview(Map<String, Object> args) {
        String repo = fullRepo(args);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("event", args.getOrDefault("event", "COMMENT"));
        payload.put("body", args.get("body"));
        payload.put("comments", args.getOrDefault("comments", List.of()));
        return request("POST", "/repos/" + repo + "/pulls/" + prNumber(args) + "/reviews", payload, "application/vnd.github+json");
    }

    public Object createBranch(Map<String, Object> args) {
        String repo = fullRepo(args);
        Object fromSha = args.get("from_sha");
        if (fromSha == null) {
            String fromBranch = String.valueOf(args.getOrDefault("from_branch", ""));
            if (fromBranch.isBlank()) {
                Map<?, ?> repoInfo = (Map<?, ?>) request("GET", "/repos/" + repo, null, "application/vnd.github+json");
                Object defaultBranch = repoInfo.get("default_branch");
                fromBranch = defaultBranch == null ? "main" : String.valueOf(defaultBranch);
            }
            Map<?, ?> branchInfo = (Map<?, ?>) request("GET", "/repos/" + repo + "/branches/" + url(fromBranch), null, "application/vnd.github+json");
            Object commit = branchInfo.get("commit");
            if (commit instanceof Map<?, ?> c) {
                fromSha = c.get("sha");
            }
        }
        if (fromSha == null || String.valueOf(fromSha).isBlank()) {
            throw new IllegalStateException("Unable to resolve source branch SHA for " + repo);
        }
        return request("POST", "/repos/" + repo + "/git/refs", Map.of("ref", "refs/heads/" + args.get("branch"), "sha", fromSha), "application/vnd.github+json");
    }

    public Object createOrUpdateFile(Map<String, Object> args) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("message", args.get("message"));
        payload.put("content", Base64.getEncoder().encodeToString(String.valueOf(args.get("content")).getBytes(StandardCharsets.UTF_8)));
        payload.put("branch", args.get("branch"));
        if (args.get("sha") != null) {
            payload.put("sha", args.get("sha"));
        }
        return request("PUT", "/repos/" + fullRepo(args) + "/contents/" + args.get("path"), payload, "application/vnd.github+json");
    }

    public Object createPullRequest(Map<String, Object> args) {
        return request("POST", "/repos/" + fullRepo(args) + "/pulls", Map.of("title", args.get("title"), "head", args.get("head"), "base", args.get("base"), "body", args.getOrDefault("body", "")), "application/vnd.github+json");
    }

    public Object addIssueComment(Map<String, Object> args) {
        return request("POST", "/repos/" + args.get("owner") + "/" + args.get("repo") + "/issues/" + args.get("issue_number") + "/comments", Map.of("body", args.get("body")), "application/vnd.github+json");
    }

    public Object detectTestFramework(Map<String, Object> args) {
        String branch = String.valueOf(args.getOrDefault("branch", ""));
        Map<String, Object> repoArgs = repoArgs(args);
        Map<String, Object> result = new LinkedHashMap<>();
        List<String> checked = new java.util.ArrayList<>();
        List<String> frameworks = new java.util.ArrayList<>();
        List<String> languages = new java.util.ArrayList<>();

        String requirements = readOptional(repoArgs, "requirements.txt", branch, checked);
        String pyproject = readOptional(repoArgs, "pyproject.toml", branch, checked);
        String setupCfg = readOptional(repoArgs, "setup.cfg", branch, checked);
        String packageJson = readOptional(repoArgs, "package.json", branch, checked);
        String pomXml = readOptional(repoArgs, "pom.xml", branch, checked);
        String gradle = readOptional(repoArgs, "build.gradle", branch, checked);
        String gradleKts = readOptional(repoArgs, "build.gradle.kts", branch, checked);
        String cargoToml = readOptional(repoArgs, "Cargo.toml", branch, checked);
        String composerJson = readOptional(repoArgs, "composer.json", branch, checked);
        String gemfile = readOptional(repoArgs, "Gemfile", branch, checked);
        String packageSwift = readOptional(repoArgs, "Package.swift", branch, checked);
        String csproj = readOptional(repoArgs, "src/App/App.csproj", branch, checked)
                + "\n" + readOptional(repoArgs, "App.csproj", branch, checked)
                + "\n" + readOptional(repoArgs, "Directory.Packages.props", branch, checked);

        String pythonConfig = (requirements + "\n" + pyproject + "\n" + setupCfg).toLowerCase();
        if (!pythonConfig.isBlank()) {
            languages.add("python");
            if (pythonConfig.contains("pytest")) {
                frameworks.add("pytest");
            }
            if (pythonConfig.contains("unittest")) {
                frameworks.add("unittest");
            }
            if (pythonConfig.contains("nose")) {
                frameworks.add("nose");
            }
        }
        String javaConfig = (pomXml + "\n" + gradle + "\n" + gradleKts).toLowerCase();
        if (!javaConfig.isBlank()) {
            languages.add(javaConfig.contains("kotlin") ? "kotlin" : "java");
            if (javaConfig.contains("junit-jupiter") || javaConfig.contains("junitplatform") || javaConfig.contains("usejunitplatform")) {
                frameworks.add("junit5");
            } else if (javaConfig.contains("junit")) {
                frameworks.add("junit4");
            }
            if (javaConfig.contains("testng")) {
                frameworks.add("testng");
            }
            if (javaConfig.contains("spring-boot-starter-test")) {
                frameworks.add("spring-boot-test");
            }
            if (javaConfig.contains("kotest")) {
                frameworks.add("kotest");
            }
        }
        String rustConfig = cargoToml.toLowerCase();
        if (!rustConfig.isBlank()) {
            languages.add("rust");
            frameworks.add("cargo-test");
            if (rustConfig.contains("tokio")) {
                frameworks.add("tokio-test");
            }
            if (rustConfig.contains("rstest")) {
                frameworks.add("rstest");
            }
        }
        String nodeConfig = packageJson.toLowerCase();
        if (!nodeConfig.isBlank()) {
            languages.add(nodeConfig.contains("typescript") || nodeConfig.contains("ts-jest") ? "typescript" : "javascript");
            if (nodeConfig.contains("vitest")) {
                frameworks.add("vitest");
            }
            if (nodeConfig.contains("jest")) {
                frameworks.add("jest");
            }
            if (nodeConfig.contains("mocha")) {
                frameworks.add("mocha");
            }
            if (nodeConfig.contains("jasmine")) {
                frameworks.add("jasmine");
            }
            if (nodeConfig.contains("react")) {
                frameworks.add("react-testing-library");
            }
            if (nodeConfig.contains("vue")) {
                frameworks.add("vue-test-utils");
            }
            if (nodeConfig.contains("@angular/core")) {
                frameworks.add("angular-testing");
            }
            if (nodeConfig.contains("cypress")) {
                frameworks.add("cypress");
            }
            if (nodeConfig.contains("playwright")) {
                frameworks.add("playwright");
            }
        }
        String phpConfig = composerJson.toLowerCase();
        if (!phpConfig.isBlank()) {
            languages.add("php");
            if (phpConfig.contains("phpunit")) {
                frameworks.add("phpunit");
            }
            if (phpConfig.contains("pestphp") || phpConfig.contains("\"pest\"")) {
                frameworks.add("pest");
            }
            if (phpConfig.contains("laravel/framework")) {
                frameworks.add("laravel-test");
            }
        }
        String rubyConfig = gemfile.toLowerCase();
        if (!rubyConfig.isBlank()) {
            languages.add("ruby");
            if (rubyConfig.contains("rspec")) {
                frameworks.add("rspec");
            }
            if (rubyConfig.contains("minitest")) {
                frameworks.add("minitest");
            }
            if (rubyConfig.contains("rails")) {
                frameworks.add("rails-test");
            }
        }
        String dotnetConfig = csproj.toLowerCase();
        if (!dotnetConfig.isBlank()) {
            languages.add("csharp");
            if (dotnetConfig.contains("xunit")) {
                frameworks.add("xunit");
            }
            if (dotnetConfig.contains("nunit")) {
                frameworks.add("nunit");
            }
            if (dotnetConfig.contains("mstest")) {
                frameworks.add("mstest");
            }
            if (dotnetConfig.contains("moq")) {
                frameworks.add("moq");
            }
        }
        String swiftConfig = packageSwift.toLowerCase();
        if (!swiftConfig.isBlank()) {
            languages.add("swift");
            frameworks.add(swiftConfig.contains("quick") ? "quick-nimble" : "swift-testing");
            if (swiftConfig.contains("xctest")) {
                frameworks.add("xctest");
            }
        }
        List<String> unique = frameworks.stream().distinct().toList();
        List<String> uniqueLanguages = languages.stream().distinct().toList();
        String language = uniqueLanguages.isEmpty() ? "unknown" : (uniqueLanguages.size() == 1 ? uniqueLanguages.getFirst() : "mixed");
        result.put("language", language);
        result.put("languages", uniqueLanguages);
        result.put("frameworks", unique);
        result.put("primary_framework", unique.isEmpty() ? "unknown" : unique.getFirst());
        result.put("config_files_checked", checked);
        return result;
    }

    private String readOptional(Map<String, Object> repoArgs, String path, String branch, List<String> checked) {
        try {
            checked.add(path);
            Map<String, Object> args = new LinkedHashMap<>(repoArgs);
            args.put("path", path);
            args.put("branch", branch);
            Object raw = getFileContents(args);
            if (raw instanceof Map<?, ?> map) {
                Object decoded = map.get("decoded_content");
                if (decoded == null) {
                    decoded = map.get("content");
                }
                return decoded == null ? "" : String.valueOf(decoded);
            }
        } catch (RuntimeException ignored) {
            return "";
        }
        return "";
    }

    private static Map<String, Object> withExtra(Map<String, Object> base, Map<String, Object> extra) {
        Map<String, Object> merged = new LinkedHashMap<>(base);
        merged.putAll(extra);
        return merged;
    }

    private static String contentFromFileResult(Object raw) {
        if (raw instanceof Map<?, ?> map) {
            Object decoded = map.get("decoded_content");
            if (decoded == null) {
                decoded = map.get("content");
            }
            return decoded == null ? "" : String.valueOf(decoded);
        }
        return raw == null ? "" : String.valueOf(raw);
    }

    @SuppressWarnings("unchecked")
    private static List<String> treePaths(Object treeResult) {
        if (treeResult instanceof Map<?, ?> map && map.get("tree") instanceof List<?> tree) {
            return tree.stream()
                    .filter(Map.class::isInstance)
                    .map(item -> (Map<String, Object>) item)
                    .filter(item -> "blob".equals(String.valueOf(item.getOrDefault("type", "blob"))))
                    .map(item -> String.valueOf(item.get("path")))
                    .filter(path -> path != null && !path.isBlank() && !"null".equals(path))
                    .toList();
        }
        return List.of();
    }

    private static Map<String, Object> repoArgs(Map<String, Object> args) {
        String repo = fullRepo(args);
        String[] parts = repo.split("/", 2);
        if (parts.length == 2) {
            return Map.of("owner", parts[0], "repo", parts[1]);
        }
        return Map.of("repo", repo);
    }

    private Object request(String method, String path, Object body, String accept) {
        String text = requestText(method, path, body, accept);
        try {
            return Jsons.MAPPER.readValue(text, new TypeReference<>() {
            });
        } catch (IOException e) {
            throw new IllegalStateException("Unable to parse GitHub response", e);
        }
    }

    private List<Map<String, Object>> requestPagedArray(String path, String accept) {
        List<Map<String, Object>> all = new ArrayList<>();
        String next = path;
        for (int page = 0; page < 10 && next != null; page++) {
            PageResponse response = requestTextWithHeaders("GET", next, null, accept);
            try {
                List<Map<String, Object>> items = Jsons.MAPPER.readValue(response.body(), new TypeReference<>() {
                });
                all.addAll(items);
            } catch (IOException e) {
                throw new IllegalStateException("Unable to parse GitHub paged response", e);
            }
            next = nextLink(response.linkHeader());
        }
        return all;
    }

    private String requestText(String method, String path, Object body, String accept) {
        return requestTextWithHeaders(method, path, body, accept).body();
    }

    private PageResponse requestTextWithHeaders(String method, String path, Object body, String accept) {
        if (!available()) {
            throw new IllegalStateException("GITHUB_TOKEN is required for live GitHub API calls");
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create("https://api.github.com" + path))
                .header("Authorization", "Bearer " + token)
                .header("Accept", accept)
                .header("X-GitHub-Api-Version", "2022-11-28");
        if (body == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            builder.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(Jsons.stringify(body)));
        }
        return Retry.run("GitHub request", () -> {
            HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                if (Retry.retryableStatus(response.statusCode())) {
                    throw new IOException("GitHub retryable HTTP " + response.statusCode() + " " + response.body());
                }
                throw new IllegalStateException("GitHub request failed: HTTP " + response.statusCode() + " " + response.body());
            }
            return new PageResponse(response.body(), response.headers().firstValue("Link").orElse(""));
        });
    }

    private static String nextLink(String linkHeader) {
        if (linkHeader == null || linkHeader.isBlank()) {
            return null;
        }
        for (String part : linkHeader.split(",")) {
            if (part.contains("rel=\"next\"")) {
                int start = part.indexOf('<');
                int end = part.indexOf('>');
                if (start >= 0 && end > start) {
                    String url = part.substring(start + 1, end);
                    return url.replace("https://api.github.com", "");
                }
            }
        }
        return null;
    }

    private record PageResponse(String body, String linkHeader) {
    }

    private static Map<String, Object> compactPullRequest(Map<?, ?> raw) {
        Map<String, Object> out = new LinkedHashMap<>();
        copy(raw, out, "number");
        copy(raw, out, "title");
        copy(raw, out, "body");
        copy(raw, out, "state");
        copy(raw, out, "draft");
        copy(raw, out, "html_url");
        copy(raw, out, "additions");
        copy(raw, out, "deletions");
        copy(raw, out, "changed_files");
        copy(raw, out, "mergeable");
        copy(raw, out, "mergeable_state");
        out.put("user", compactUser(raw.get("user")));
        out.put("head", compactBranch(raw.get("head")));
        out.put("base", compactBranch(raw.get("base")));
        return out;
    }

    private static Map<String, Object> compactUser(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            copy(map, out, "login");
            copy(map, out, "type");
            return out;
        }
        return Map.of();
    }

    private static Map<String, Object> compactBranch(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            copy(map, out, "ref");
            copy(map, out, "sha");
            if (map.get("repo") instanceof Map<?, ?> repo) {
                Map<String, Object> repoOut = new LinkedHashMap<>();
                copy(repo, repoOut, "full_name");
                copy(repo, repoOut, "default_branch");
                out.put("repo", repoOut);
            }
            return out;
        }
        return Map.of();
    }

    private static void copy(Map<?, ?> from, Map<String, Object> to, String key) {
        if (from.containsKey(key)) {
            to.put(key, from.get(key));
        }
    }

    private static String strArg(Map<String, Object> args, String key) {
        return String.valueOf(args.getOrDefault(key, ""));
    }

    private static String fullRepo(Map<String, Object> args) {
        Object repo = args.get("repo");
        Object owner = args.get("owner");
        if (repo != null && String.valueOf(repo).contains("/")) {
            return String.valueOf(repo);
        }
        if (owner != null && repo != null) {
            return owner + "/" + repo;
        }
        return String.valueOf(repo);
    }

    private static int prNumber(Map<String, Object> args) {
        if (args.containsKey("pull_number")) {
            return intArg(args, "pull_number");
        }
        return intArg(args, "pr");
    }

    private static int intArg(Map<String, Object> args, String key) {
        return intArg(args, key, 0);
    }

    private static int intArg(Map<String, Object> args, String key, int fallback) {
        Object value = args.get(key);
        if (value == null) {
            return fallback;
        }
        return value instanceof Number n ? n.intValue() : Integer.parseInt(String.valueOf(value));
    }

    private static String url(Object value) {
        return URLEncoder.encode(String.valueOf(value), StandardCharsets.UTF_8);
    }
}
