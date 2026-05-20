package com.cragent.tools;

import com.cragent.agent.LspServerRegistry;
import com.cragent.agent.RepoAuditIndexer;
import com.cragent.config.Settings;
import com.cragent.util.Jsons;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static com.cragent.tools.ToolSchemas.array;
import static com.cragent.tools.ToolSchemas.integer;
import static com.cragent.tools.ToolSchemas.object;
import static com.cragent.tools.ToolSchemas.str;

public class AdvancedReviewTools {
    private final Settings settings;

    public AdvancedReviewTools(Settings settings) {
        this.settings = settings;
    }

    public void register(ToolRouter router) {
        router.register(new ToolSpec("run_codeql_scan", "Run read-only CodeQL analysis into a temporary database and return summarized alerts.", object(Map.of(
                "repo_path", str("Local repository path"),
                "language", str("Optional CodeQL language, e.g. java-kotlin, javascript-typescript, python")
        ), List.of("repo_path")), this::runCodeqlScan, false));
        router.register(new ToolSpec("run_semgrep_scan", "Run read-only Semgrep scan and return summarized JSON findings.", object(Map.of(
                "repo_path", str("Local repository path"),
                "config", str("Semgrep config, default auto")
        ), List.of("repo_path")), this::runSemgrepScan, false));
        router.register(new ToolSpec("run_secret_scan", "Run read-only secret scan using gitleaks and return redacted findings.", object(Map.of(
                "repo_path", str("Local repository path")
        ), List.of("repo_path")), this::runSecretScan, false));
        router.register(new ToolSpec("run_dependency_vulnerability_scan", "Run read-only dependency vulnerability scan using osv-scanner.", object(Map.of(
                "repo_path", str("Local repository path")
        ), List.of("repo_path")), this::runDependencyVulnerabilityScan, false));
        router.register(new ToolSpec("candidate_evidence_bundle", "Bundle local evidence for a candidate issue: hunk, source excerpt, tests, blame, churn, static evidence.", object(Map.of(
                "repo_path", str("Local repository path"),
                "issue", object(Map.of(), List.of()),
                "changed_files", array("Changed files with filename/path and patch"),
                "static_findings", array("Optional static/SAST findings")
        ), List.of("repo_path", "issue")), this::candidateEvidenceBundle, false));
        router.register(new ToolSpec("git_blame_context", "Read git blame metadata around a target file line.", object(Map.of(
                "repo_path", str("Local repository path"),
                "path", str("Relative file path"),
                "line", integer("1-based target line"),
                "context", integer("Number of surrounding lines")
        ), List.of("repo_path", "path", "line")), this::gitBlameContext, false));
        router.register(new ToolSpec("git_recent_file_changes", "List recent commits that touched a file.", object(Map.of(
                "repo_path", str("Local repository path"),
                "path", str("Relative file path"),
                "limit", integer("Maximum commits")
        ), List.of("repo_path", "path")), this::gitRecentFileChanges, false));
        router.register(new ToolSpec("git_churn_hotspots", "Find files with high recent churn from git numstat.", object(Map.of(
                "repo_path", str("Local repository path"),
                "since", str("Git --since value, e.g. 90 days ago"),
                "limit", integer("Maximum files")
        ), List.of("repo_path")), this::gitChurnHotspots, false));
        router.register(new ToolSpec("git_bugfix_history_search", "Search recent bugfix/security commits, optionally constrained to a path.", object(Map.of(
                "repo_path", str("Local repository path"),
                "path", str("Optional relative path"),
                "limit", integer("Maximum commits")
        ), List.of("repo_path")), this::gitBugfixHistorySearch, false));
        router.register(new ToolSpec("detect_public_api_changes", "Detect likely public API surface changes in changed files.", object(Map.of(
                "changed_files", array("Changed files with filename/path and patch")
        ), List.of("changed_files")), this::detectPublicApiChanges, false));
        router.register(new ToolSpec("route_contract_diff", "Detect likely route/API contract changes from changed file patches.", object(Map.of(
                "changed_files", array("Changed files with filename/path and patch")
        ), List.of("changed_files")), this::routeContractDiff, false));
        router.register(new ToolSpec("openapi_schema_diff", "Analyze OpenAPI schema diffs for breaking route/schema changes.", object(Map.of(
                "repo_path", str("Local repository path"),
                "base", str("Optional base ref"),
                "head", str("Optional head ref"),
                "path", str("Optional OpenAPI file path"),
                "changed_files", array("Changed files with patch")
        ), List.of("repo_path")), this::openapiSchemaDiff, false));
        router.register(new ToolSpec("protobuf_schema_diff", "Analyze protobuf schema diffs for tag/type/removal compatibility risks.", object(Map.of(
                "changed_files", array("Changed .proto files with patch")
        ), List.of("changed_files")), this::protobufSchemaDiff, false));
        router.register(new ToolSpec("db_migration_risk_analyzer", "Analyze migration diffs for lock, rollback, destructive, and backfill risks.", object(Map.of(
                "changed_files", array("Changed migration files with patch")
        ), List.of("changed_files")), this::dbMigrationRiskAnalyzer, false));
        router.register(new ToolSpec("select_impacted_tests", "Select likely impacted tests for changed source files.", object(Map.of(
                "repo_path", str("Local repository path"),
                "changed_files", array("Changed files with filename/path")
        ), List.of("repo_path", "changed_files")), this::selectImpactedTests, false));
        router.register(new ToolSpec("run_targeted_tests_readonly", "Run a targeted test command or inferred test files without modifying source.", object(Map.of(
                "repo_path", str("Local repository path"),
                "test_paths", array("Test files to run"),
                "command", str("Optional explicit test command")
        ), List.of("repo_path")), this::runTargetedTestsReadonly, false));
        router.register(new ToolSpec("coverage_for_changed_symbols", "Inspect existing coverage artifacts for changed files/symbols.", object(Map.of(
                "repo_path", str("Local repository path"),
                "changed_files", array("Changed files with filename/path")
        ), List.of("repo_path", "changed_files")), this::coverageForChangedSymbols, false));
        router.register(new ToolSpec("mutation_probe_plan", "Generate mutation-style probes for changed code paths without executing mutations.", object(Map.of(
                "changed_files", array("Changed files with filename/path and patch")
        ), List.of("changed_files")), this::mutationProbePlan, false));
        router.register(new ToolSpec("github_actions_permission_audit", "Audit GitHub Actions workflows for token, pull_request_target, pinning, and secret risks.", object(Map.of(
                "repo_path", str("Local repository path")
        ), List.of("repo_path")), this::githubActionsPermissionAudit, false));
        router.register(new ToolSpec("dockerfile_risk_scan", "Scan Dockerfiles for common build/runtime security and reproducibility risks.", object(Map.of(
                "repo_path", str("Local repository path")
        ), List.of("repo_path")), this::dockerfileRiskScan, false));
        router.register(new ToolSpec("lockfile_diff_summary", "Summarize dependency additions/removals from lockfile or manifest patches.", object(Map.of(
                "changed_files", array("Changed lockfile/manifest files with patch")
        ), List.of("changed_files")), this::lockfileDiffSummary, false));
        router.register(new ToolSpec("sbom_generate", "Generate an SBOM with syft when available, otherwise summarize dependency manifests.", object(Map.of(
                "repo_path", str("Local repository path")
        ), List.of("repo_path")), this::sbomGenerate, false));
        router.register(new ToolSpec("license_policy_check", "Inspect dependency manifests for declared licenses and obvious policy review needs.", object(Map.of(
                "repo_path", str("Local repository path")
        ), List.of("repo_path")), this::licensePolicyCheck, false));
    }

    private Object runCodeqlScan(Map<String, Object> args) {
        Path root = path(args, "repo_path");
        if (!LspServerRegistry.commandExists("codeql")) {
            return unavailable("run_codeql_scan", "codeql", "Install CodeQL CLI and ensure `codeql` is on PATH.");
        }
        String language = strArg(args, "language", detectCodeqlLanguage(root));
        if (language.isBlank()) {
            return Map.of("status", "skipped", "reason", "Unable to infer a CodeQL-supported language.");
        }
        try {
            Path temp = Files.createTempDirectory("cr-agent-codeql-");
            Path db = temp.resolve("db");
            Path sarif = temp.resolve("results.sarif");
            CommandResult create = run(root, Duration.ofMinutes(5), "codeql", "database", "create", db.toString(), "--source-root", root.toString(), "--language", language, "--overwrite");
            if (create.exitCode != 0) {
                return Map.of("status", "failed", "language", language, "stage", "database_create", "command", create.command, "output", truncate(create.output, 12000));
            }
            CommandResult analyze = run(root, Duration.ofMinutes(5), "codeql", "database", "analyze", db.toString(), "--format=sarif-latest", "--output", sarif.toString());
            Map<String, Object> parsed = Files.exists(sarif) ? summarizeSarif(Files.readString(sarif)) : Map.of("alerts", List.of(), "count", 0);
            return Map.of("status", analyze.exitCode == 0 ? "completed" : "failed", "language", language, "command", analyze.command, "summary", parsed, "output", truncate(analyze.output, 8000));
        } catch (Exception e) {
            return error("run_codeql_scan", e);
        }
    }

    private Object runSemgrepScan(Map<String, Object> args) {
        Path root = path(args, "repo_path");
        if (!LspServerRegistry.commandExists("semgrep")) {
            return unavailable("run_semgrep_scan", "semgrep", "Install Semgrep CLI and ensure `semgrep` is on PATH.");
        }
        try {
            Path out = Files.createTempFile("cr-agent-semgrep-", ".json");
            String config = strArg(args, "config", "auto");
            CommandResult result = run(root, Duration.ofMinutes(3), "semgrep", "scan", "--config", config, "--json", "--quiet", "--output", out.toString());
            String json = Files.exists(out) ? Files.readString(out) : "{}";
            return Map.of("status", result.exitCode <= 1 ? "completed" : "failed", "command", result.command, "summary", summarizeSemgrep(json), "output", truncate(result.output, 8000));
        } catch (Exception e) {
            return error("run_semgrep_scan", e);
        }
    }

    private Object runSecretScan(Map<String, Object> args) {
        Path root = path(args, "repo_path");
        if (!LspServerRegistry.commandExists("gitleaks")) {
            return unavailable("run_secret_scan", "gitleaks", "Install gitleaks and ensure `gitleaks` is on PATH.");
        }
        try {
            Path out = Files.createTempFile("cr-agent-gitleaks-", ".json");
            CommandResult result = run(root, Duration.ofMinutes(2), "gitleaks", "detect", "--source", root.toString(), "--no-git", "--redact", "--report-format", "json", "--report-path", out.toString());
            String json = Files.exists(out) ? Files.readString(out) : "[]";
            return Map.of("status", result.exitCode <= 1 ? "completed" : "failed", "command", result.command, "summary", summarizeJsonArray(json, "findings"), "output", truncate(result.output, 8000));
        } catch (Exception e) {
            return error("run_secret_scan", e);
        }
    }

    private Object runDependencyVulnerabilityScan(Map<String, Object> args) {
        Path root = path(args, "repo_path");
        if (!LspServerRegistry.commandExists("osv-scanner")) {
            return unavailable("run_dependency_vulnerability_scan", "osv-scanner", "Install OSV-Scanner and ensure `osv-scanner` is on PATH.");
        }
        CommandResult first = run(root, Duration.ofMinutes(3), "osv-scanner", "--format", "json", "-r", root.toString());
        CommandResult result = first.exitCode == 127 || first.output.contains("unknown")
                ? run(root, Duration.ofMinutes(3), "osv-scanner", "scan", "source", "-r", root.toString(), "--format", "json")
                : first;
        return Map.of("status", result.exitCode <= 1 ? "completed" : "failed", "command", result.command, "summary", summarizeOsv(result.output), "output", truncate(result.output, 12000));
    }

    @SuppressWarnings("unchecked")
    private Object candidateEvidenceBundle(Map<String, Object> args) {
        Path root = path(args, "repo_path");
        Map<String, Object> issue = args.get("issue") instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
        String file = String.valueOf(issue.getOrDefault("file", ""));
        int line = intArg(issue, "line", 1);
        List<Map<String, Object>> changed = listOfMaps(args.get("changed_files"));
        Map<String, Object> bundle = new LinkedHashMap<>();
        bundle.put("issue", issue);
        bundle.put("changed_hunk", changed.stream().filter(item -> file.equals(String.valueOf(item.getOrDefault("filename", item.get("path"))))).findFirst().orElse(Map.of()));
        bundle.put("source_excerpt", readExcerpt(root, file, line, 8));
        bundle.put("related_tests", relatedTests(root, file));
        bundle.put("blame", gitBlameContext(Map.of("repo_path", root.toString(), "path", file, "line", line, "context", 2)));
        bundle.put("recent_changes", gitRecentFileChanges(Map.of("repo_path", root.toString(), "path", file, "limit", 5)));
        bundle.put("static_findings", filterFindings(args.get("static_findings"), file, line));
        return bundle;
    }

    private Object gitBlameContext(Map<String, Object> args) {
        Path root = path(args, "repo_path");
        String file = strArg(args, "path", "");
        int line = intArg(args, "line", 1);
        int context = intArg(args, "context", 3);
        int start = Math.max(1, line - context);
        int end = Math.max(start, line + context);
        CommandResult result = run(root, Duration.ofSeconds(20), "git", "blame", "-L", start + "," + end, "--line-porcelain", "--", file);
        return Map.of("path", file, "line", line, "range", List.of(start, end), "status", result.exitCode == 0 ? "completed" : "failed", "entries", parseBlame(result.output), "output", truncate(result.output, 8000));
    }

    private Object gitRecentFileChanges(Map<String, Object> args) {
        Path root = path(args, "repo_path");
        String file = strArg(args, "path", "");
        int limit = intArg(args, "limit", 10);
        CommandResult result = run(root, Duration.ofSeconds(20), "git", "log", "--follow", "--date=iso", "--pretty=format:%H%x09%an%x09%ad%x09%s", "-" + Math.max(1, limit), "--", file);
        return Map.of("path", file, "status", result.exitCode == 0 ? "completed" : "failed", "commits", parseLogRows(result.output), "output", truncate(result.output, 8000));
    }

    private Object gitChurnHotspots(Map<String, Object> args) {
        Path root = path(args, "repo_path");
        String since = strArg(args, "since", "90 days ago");
        int limit = intArg(args, "limit", 20);
        CommandResult result = run(root, Duration.ofSeconds(30), "git", "log", "--since", since, "--numstat", "--pretty=format:");
        Map<String, int[]> stats = new HashMap<>();
        for (String line : result.output.split("\\R")) {
            String[] parts = line.split("\\t");
            if (parts.length == 3 && parts[0].matches("\\d+") && parts[1].matches("\\d+")) {
                int[] item = stats.computeIfAbsent(parts[2], ignored -> new int[3]);
                item[0] += Integer.parseInt(parts[0]);
                item[1] += Integer.parseInt(parts[1]);
                item[2]++;
            }
        }
        List<Map<String, Object>> hotspots = stats.entrySet().stream()
                .map(entry -> Map.<String, Object>of("path", entry.getKey(), "additions", entry.getValue()[0], "deletions", entry.getValue()[1], "touches", entry.getValue()[2], "churn", entry.getValue()[0] + entry.getValue()[1]))
                .sorted(Comparator.comparingInt(item -> -((Number) item.get("churn")).intValue()))
                .limit(limit)
                .toList();
        return Map.of("status", result.exitCode == 0 ? "completed" : "failed", "since", since, "hotspots", hotspots);
    }

    private Object gitBugfixHistorySearch(Map<String, Object> args) {
        Path root = path(args, "repo_path");
        String file = strArg(args, "path", "");
        int limit = intArg(args, "limit", 20);
        List<String> command = new ArrayList<>(List.of("git", "log", "--date=iso", "--extended-regexp", "--grep=(fix|bug|regression|security|hotfix|crash|leak|race)", "--pretty=format:%H%x09%an%x09%ad%x09%s", "-" + limit));
        if (!file.isBlank()) {
            command.add("--");
            command.add(file);
        }
        CommandResult result = run(root, Duration.ofSeconds(30), command.toArray(String[]::new));
        return Map.of("status", result.exitCode == 0 ? "completed" : "failed", "path", file, "commits", parseLogRows(result.output));
    }

    private Object detectPublicApiChanges(Map<String, Object> args) {
        List<Map<String, Object>> findings = new ArrayList<>();
        for (Map<String, Object> file : listOfMaps(args.get("changed_files"))) {
            String path = filePath(file);
            String patch = patch(file);
            for (String row : patch.split("\\R")) {
                String lower = row.toLowerCase(Locale.ROOT);
                if (row.startsWith("+") && containsAny(lower, "public ", "export ", "func ", "class ", "interface ", "def ", "route", "endpoint", "protobuf", "rpc ")) {
                    findings.add(Map.of("path", path, "line", row, "risk", "public_api_surface", "reason", "Added or changed exported/public API-like declaration."));
                }
                if (row.startsWith("-") && containsAny(lower, "public ", "export ", "func ", "class ", "interface ", "rpc ")) {
                    findings.add(Map.of("path", path, "line", row, "risk", "possible_breaking_removal", "reason", "Removed public/API-like declaration."));
                }
            }
        }
        return Map.of("changes", findings, "count", findings.size());
    }

    private Object routeContractDiff(Map<String, Object> args) {
        List<Map<String, Object>> routes = new ArrayList<>();
        Pattern routePattern = Pattern.compile("(GET|POST|PUT|PATCH|DELETE|router\\.|app\\.|@(?:Get|Post|Put|Patch|Delete|Request)Mapping|Route\\(|routes?\\.)", Pattern.CASE_INSENSITIVE);
        for (Map<String, Object> file : listOfMaps(args.get("changed_files"))) {
            String path = filePath(file);
            for (String row : patch(file).split("\\R")) {
                if ((row.startsWith("+") || row.startsWith("-")) && routePattern.matcher(row).find()) {
                    routes.add(Map.of("path", path, "change", row.startsWith("+") ? "added_or_changed" : "removed", "line", row, "risk", "route_contract"));
                }
            }
        }
        return Map.of("routes", routes, "count", routes.size());
    }

    private Object openapiSchemaDiff(Map<String, Object> args) {
        String diff = diffFromArgs(args, Set.of("openapi", "swagger", ".yaml", ".yml", ".json"));
        List<Map<String, Object>> risks = new ArrayList<>();
        for (String row : diff.split("\\R")) {
            String lower = row.toLowerCase(Locale.ROOT);
            if (row.startsWith("-") && containsAny(lower, "required:", "enum:", "type:", "paths:", "responses:", "schema:")) {
                risks.add(Map.of("change", "removed_schema_contract", "line", row));
            }
            if (row.startsWith("+") && containsAny(lower, "required:", "deprecated:", "security:", "additionalproperties:", "nullable: false")) {
                risks.add(Map.of("change", "added_or_tightened_contract", "line", row));
            }
        }
        return Map.of("risks", risks, "count", risks.size());
    }

    private Object protobufSchemaDiff(Map<String, Object> args) {
        List<Map<String, Object>> risks = new ArrayList<>();
        Pattern field = Pattern.compile("[+-]\\s*(optional|required|repeated)?\\s*([A-Za-z0-9_.<>]+)\\s+([A-Za-z0-9_]+)\\s*=\\s*(\\d+)");
        for (Map<String, Object> file : listOfMaps(args.get("changed_files"))) {
            if (!filePath(file).endsWith(".proto")) continue;
            for (String row : patch(file).split("\\R")) {
                String lower = row.toLowerCase(Locale.ROOT);
                Matcher matcher = field.matcher(row);
                if (row.startsWith("-") && matcher.find()) {
                    risks.add(Map.of("path", filePath(file), "risk", "removed_field_or_tag", "tag", matcher.group(4), "line", row));
                } else if (row.startsWith("+") && containsAny(lower, "reserved", "required ")) {
                    risks.add(Map.of("path", filePath(file), "risk", "schema_compatibility", "line", row));
                }
            }
        }
        return Map.of("risks", risks, "count", risks.size());
    }

    private Object dbMigrationRiskAnalyzer(Map<String, Object> args) {
        List<Map<String, Object>> risks = new ArrayList<>();
        for (Map<String, Object> file : listOfMaps(args.get("changed_files"))) {
            String path = filePath(file).toLowerCase(Locale.ROOT);
            if (!containsAny(path, "migration", "db/migrate", "schema", "liquibase", "flyway")) continue;
            for (String row : patch(file).split("\\R")) {
                String lower = row.toLowerCase(Locale.ROOT);
                if (row.startsWith("+") && containsAny(lower, "drop table", "drop column", "rename column", "alter column", "not null", "create index", "update ")) {
                    risks.add(Map.of("path", filePath(file), "risk", migrationRisk(lower), "line", row));
                }
            }
        }
        return Map.of("risks", risks, "count", risks.size());
    }

    private Object selectImpactedTests(Map<String, Object> args) {
        Path root = path(args, "repo_path");
        List<Map<String, Object>> selected = new ArrayList<>();
        for (Map<String, Object> changed : listOfMaps(args.get("changed_files"))) {
            String source = filePath(changed);
            selected.add(Map.of("source", source, "tests", relatedTests(root, source)));
        }
        return Map.of("impacted_tests", selected);
    }

    private Object runTargetedTestsReadonly(Map<String, Object> args) {
        Path root = path(args, "repo_path");
        String explicit = strArg(args, "command", "");
        List<String> tests = listOfStrings(args.get("test_paths"));
        String command = explicit.isBlank() ? inferTestCommand(root, tests) : explicit;
        if (command.isBlank()) {
            return Map.of("status", "skipped", "reason", "No command provided and no supported test command inferred.", "test_paths", tests);
        }
        CommandResult result = runShell(root, Duration.ofMinutes(5), command);
        return Map.of("status", result.exitCode == 0 ? "passed" : "failed", "command", command, "exit_code", result.exitCode, "output", truncate(result.output, 12000), "test_paths", tests);
    }

    private Object coverageForChangedSymbols(Map<String, Object> args) {
        Path root = path(args, "repo_path");
        List<Path> artifacts = findCoverageArtifacts(root);
        List<Map<String, Object>> coverage = new ArrayList<>();
        for (Map<String, Object> changed : listOfMaps(args.get("changed_files"))) {
            String file = filePath(changed);
            boolean mentioned = artifacts.stream().anyMatch(artifact -> safeRead(artifact).contains(file) || safeRead(artifact).contains(file.replace("\\", "/")));
            coverage.add(Map.of("path", file, "mentioned_in_existing_coverage_artifact", mentioned));
        }
        return Map.of("coverage_artifacts", artifacts.stream().map(root::relativize).map(Path::toString).toList(), "files", coverage);
    }

    private Object mutationProbePlan(Map<String, Object> args) {
        List<Map<String, Object>> probes = new ArrayList<>();
        for (Map<String, Object> file : listOfMaps(args.get("changed_files"))) {
            String path = filePath(file);
            String patch = patch(file).toLowerCase(Locale.ROOT);
            if (containsAny(patch, "&&", "||", "!", "==", "!=", "<", ">")) probes.add(probe(path, "boolean_boundary", "Flip condition/operator and expect tests to fail."));
            if (containsAny(patch, "null", "undefined", "optional", "nil")) probes.add(probe(path, "nullability", "Inject null/empty value and assert explicit handling."));
            if (containsAny(patch, "catch", "throw", "error", "exception")) probes.add(probe(path, "error_path", "Force dependency failure and assert error propagation."));
            if (containsAny(patch, "permission", "auth", "role", "token")) probes.add(probe(path, "auth_boundary", "Mutate role/token boundary and assert denial."));
            if (containsAny(patch, "insert", "update", "transaction", "migration")) probes.add(probe(path, "data_integrity", "Mutate partial failure and assert rollback/idempotency."));
        }
        return Map.of("mutation_probes", probes, "count", probes.size());
    }

    private Object githubActionsPermissionAudit(Map<String, Object> args) {
        Path root = path(args, "repo_path");
        List<Map<String, Object>> findings = new ArrayList<>();
        Path workflows = root.resolve(".github/workflows");
        if (Files.isDirectory(workflows)) {
            try (Stream<Path> stream = Files.walk(workflows)) {
                stream.filter(Files::isRegularFile).filter(path -> path.toString().matches(".*\\.(ya?ml)$")).forEach(path -> auditWorkflow(root, path, findings));
            } catch (IOException e) {
                return error("github_actions_permission_audit", e);
            }
        }
        return Map.of("findings", findings, "count", findings.size());
    }

    private Object dockerfileRiskScan(Map<String, Object> args) {
        Path root = path(args, "repo_path");
        List<Map<String, Object>> findings = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(root, 6)) {
            stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().equalsIgnoreCase("Dockerfile") || path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".dockerfile"))
                    .forEach(path -> auditDockerfile(root, path, findings));
        } catch (IOException e) {
            return error("dockerfile_risk_scan", e);
        }
        return Map.of("findings", findings, "count", findings.size());
    }

    private Object lockfileDiffSummary(Map<String, Object> args) {
        List<Map<String, Object>> changes = new ArrayList<>();
        for (Map<String, Object> file : listOfMaps(args.get("changed_files"))) {
            String path = filePath(file);
            if (!containsAny(path.toLowerCase(Locale.ROOT), "lock", "package.json", "pom.xml", "build.gradle", "go.mod", "cargo.toml", "composer.json", "gemfile", "package.swift")) {
                continue;
            }
            int additions = 0;
            int removals = 0;
            List<String> addedLines = new ArrayList<>();
            for (String row : patch(file).split("\\R")) {
                if (row.startsWith("+") && !row.startsWith("+++")) {
                    additions++;
                    if (addedLines.size() < 20) addedLines.add(row);
                } else if (row.startsWith("-") && !row.startsWith("---")) {
                    removals++;
                }
            }
            changes.add(Map.of("path", path, "added_lines", additions, "removed_lines", removals, "added_preview", addedLines));
        }
        return Map.of("dependency_changes", changes, "count", changes.size());
    }

    private Object sbomGenerate(Map<String, Object> args) {
        Path root = path(args, "repo_path");
        if (LspServerRegistry.commandExists("syft")) {
            CommandResult result = run(root, Duration.ofMinutes(3), "syft", "dir:" + root, "-o", "json");
            return Map.of("status", result.exitCode == 0 ? "completed" : "failed", "tool", "syft", "summary", summarizeSyft(result.output), "output", truncate(result.output, 12000));
        }
        return Map.of("status", "manifest_summary", "tool", "built_in", "summary", dependencyManifestSummary(root));
    }

    private Object licensePolicyCheck(Map<String, Object> args) {
        Path root = path(args, "repo_path");
        List<Map<String, Object>> findings = new ArrayList<>();
        for (Path manifest : dependencyManifests(root)) {
            String text = safeRead(manifest);
            String lower = text.toLowerCase(Locale.ROOT);
            if (manifest.getFileName().toString().equals("package.json") && !lower.contains("\"license\"")) {
                findings.add(Map.of("path", rel(root, manifest), "risk", "missing_declared_license", "reason", "package.json has no top-level license field."));
            }
            if (containsAny(lower, "gpl", "agpl", "lgpl")) {
                findings.add(Map.of("path", rel(root, manifest), "risk", "copyleft_license_review", "reason", "Manifest mentions GPL/AGPL/LGPL; confirm policy compatibility."));
            }
        }
        return Map.of("findings", findings, "count", findings.size());
    }

    private static Map<String, Object> unavailable(String tool, String command, String hint) {
        return Map.of("status", "unavailable", "tool", tool, "command", command, "install_hint", hint);
    }

    private static Map<String, Object> error(String tool, Exception e) {
        return Map.of("status", "failed", "tool", tool, "error", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
    }

    private static CommandResult run(Path dir, Duration timeout, String... command) {
        return runProcess(dir, timeout, List.of(command), false);
    }

    private static CommandResult runShell(Path dir, Duration timeout, String command) {
        return runProcess(dir, timeout, List.of("/bin/sh", "-lc", command), true);
    }

    private static CommandResult runProcess(Path dir, Duration timeout, List<String> command, boolean shell) {
        String joined = shell ? command.get(command.size() - 1) : String.join(" ", command);
        try {
            Process process = new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true).start();
            boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                return new CommandResult(joined, 124, "Timed out after " + timeout.toSeconds() + "s");
            }
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return new CommandResult(joined, process.exitValue(), output);
        } catch (Exception e) {
            return new CommandResult(joined, 127, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    private static Map<String, Object> summarizeSarif(String json) {
        try {
            JsonNode root = Jsons.parseNode(json);
            List<Map<String, Object>> alerts = new ArrayList<>();
            for (JsonNode run : root.path("runs")) {
                for (JsonNode result : run.path("results")) {
                    JsonNode loc = result.path("locations").path(0).path("physicalLocation");
                    alerts.add(Map.of(
                            "rule_id", result.path("ruleId").asText(""),
                            "message", result.path("message").path("text").asText(""),
                            "path", loc.path("artifactLocation").path("uri").asText(""),
                            "line", loc.path("region").path("startLine").asInt(0)
                    ));
                    if (alerts.size() >= 50) break;
                }
            }
            return Map.of("count", alerts.size(), "alerts", alerts);
        } catch (Exception e) {
            return Map.of("count", 0, "alerts", List.of(), "parse_error", e.getMessage());
        }
    }

    private static Map<String, Object> summarizeSemgrep(String json) {
        try {
            JsonNode root = Jsons.parseNode(json);
            List<Map<String, Object>> findings = new ArrayList<>();
            for (JsonNode result : root.path("results")) {
                findings.add(Map.of(
                        "check_id", result.path("check_id").asText(""),
                        "path", result.path("path").asText(""),
                        "line", result.path("start").path("line").asInt(0),
                        "message", result.path("extra").path("message").asText("")
                ));
                if (findings.size() >= 100) break;
            }
            return Map.of("count", root.path("results").size(), "findings", findings);
        } catch (Exception e) {
            return Map.of("count", 0, "findings", List.of(), "parse_error", e.getMessage());
        }
    }

    private static Map<String, Object> summarizeJsonArray(String json, String key) {
        try {
            JsonNode root = Jsons.parseNode(json);
            List<Object> items = new ArrayList<>();
            if (root.isArray()) {
                for (JsonNode item : root) {
                    items.add(Jsons.MAPPER.convertValue(item, Map.class));
                    if (items.size() >= 100) break;
                }
            }
            return Map.of("count", root.isArray() ? root.size() : 0, key, items);
        } catch (Exception e) {
            return Map.of("count", 0, key, List.of(), "parse_error", e.getMessage());
        }
    }

    private static Map<String, Object> summarizeOsv(String output) {
        try {
            JsonNode root = Jsons.parseNode(extractJson(output));
            int count = root.path("results").isArray() ? root.path("results").size() : root.path("runs").size();
            return Map.of("result_groups", count, "preview", truncate(output, 8000));
        } catch (Exception e) {
            return Map.of("result_groups", 0, "preview", truncate(output, 8000));
        }
    }

    private static Map<String, Object> summarizeSyft(String output) {
        try {
            JsonNode root = Jsons.parseNode(output);
            List<Map<String, Object>> artifacts = new ArrayList<>();
            for (JsonNode item : root.path("artifacts")) {
                artifacts.add(Map.of("name", item.path("name").asText(""), "version", item.path("version").asText(""), "type", item.path("type").asText("")));
                if (artifacts.size() >= 100) break;
            }
            return Map.of("package_count", root.path("artifacts").size(), "packages", artifacts);
        } catch (Exception e) {
            return Map.of("package_count", 0, "parse_error", e.getMessage());
        }
    }

    private static List<Map<String, Object>> parseBlame(String output) {
        List<Map<String, Object>> entries = new ArrayList<>();
        Map<String, Object> current = new LinkedHashMap<>();
        for (String line : output.split("\\R")) {
            if (line.matches("^[0-9a-f]{8,40} .*")) {
                if (!current.isEmpty()) entries.add(current);
                current = new LinkedHashMap<>();
                current.put("commit", line.split(" ")[0]);
            } else if (line.startsWith("author ")) {
                current.put("author", line.substring(7));
            } else if (line.startsWith("summary ")) {
                current.put("summary", line.substring(8));
            } else if (line.startsWith("\t")) {
                current.put("text", line.substring(1));
            }
        }
        if (!current.isEmpty()) entries.add(current);
        return entries.stream().limit(20).toList();
    }

    private static List<Map<String, Object>> parseLogRows(String output) {
        List<Map<String, Object>> commits = new ArrayList<>();
        for (String row : output.split("\\R")) {
            String[] parts = row.split("\\t", 4);
            if (parts.length == 4) {
                commits.add(Map.of("sha", parts[0], "author", parts[1], "date", parts[2], "subject", parts[3]));
            }
        }
        return commits;
    }

    private static List<Map<String, Object>> relatedTests(Path root, String source) {
        String stem = stem(source).toLowerCase(Locale.ROOT);
        List<Map<String, Object>> tests = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(root, 8)) {
            stream.filter(Files::isRegularFile)
                    .filter(path -> isTestPath(root.relativize(path).toString()))
                    .filter(path -> {
                        String rel = root.relativize(path).toString().toLowerCase(Locale.ROOT);
                        String text = safeRead(path).toLowerCase(Locale.ROOT);
                        return rel.contains(stem) || text.contains(stem);
                    })
                    .limit(25)
                    .forEach(path -> tests.add(Map.of("path", rel(root, path), "match_reason", "path/content references source stem")));
        } catch (IOException ignored) {
        }
        return tests;
    }

    private static List<Path> findCoverageArtifacts(Path root) {
        List<Path> out = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(root, 8)) {
            stream.filter(Files::isRegularFile)
                    .filter(path -> {
                        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
                        return name.equals("lcov.info") || name.endsWith("coverage.xml") || name.equals("jacoco.xml") || name.endsWith(".lcov");
                    })
                    .limit(20)
                    .forEach(out::add);
        } catch (IOException ignored) {
        }
        return out;
    }

    private static void auditWorkflow(Path root, Path path, List<Map<String, Object>> findings) {
        String text = safeRead(path);
        String rel = rel(root, path);
        if (text.contains("pull_request_target")) findings.add(Map.of("path", rel, "risk", "pull_request_target", "reason", "Workflow runs in privileged PR context."));
        if (text.matches("(?s).*permissions:\\s*(write-all|read-all).*")) findings.add(Map.of("path", rel, "risk", "broad_permissions", "reason", "Workflow declares broad token permissions."));
        if (text.matches("(?s).*uses:\\s+[^\\s@]+/[^\\s@]+\\s.*")) findings.add(Map.of("path", rel, "risk", "unpinned_action", "reason", "Action is not pinned to a ref/SHA."));
        if (text.toLowerCase(Locale.ROOT).contains("secrets.") && text.contains("pull_request")) findings.add(Map.of("path", rel, "risk", "secrets_in_pr_flow", "reason", "Workflow references secrets in a PR-triggered flow."));
    }

    private static void auditDockerfile(Path root, Path path, List<Map<String, Object>> findings) {
        String rel = rel(root, path);
        int lineNo = 0;
        boolean hasUser = false;
        for (String line : safeRead(path).split("\\R")) {
            lineNo++;
            String lower = line.toLowerCase(Locale.ROOT).trim();
            if (lower.startsWith("user ")) hasUser = true;
            if (lower.startsWith("from ") && (lower.endsWith(":latest") || !lower.contains(":"))) findings.add(Map.of("path", rel, "line", lineNo, "risk", "unpinned_base_image", "text", line));
            if (lower.startsWith("add ")) findings.add(Map.of("path", rel, "line", lineNo, "risk", "add_instruction", "text", line));
            if (lower.contains("curl ") && lower.contains("|") && lower.contains("sh")) findings.add(Map.of("path", rel, "line", lineNo, "risk", "curl_pipe_shell", "text", line));
            if (lower.contains("secret") || lower.contains("token")) findings.add(Map.of("path", rel, "line", lineNo, "risk", "possible_secret_in_image", "text", line));
        }
        if (!hasUser) findings.add(Map.of("path", rel, "risk", "runs_as_root", "reason", "Dockerfile has no USER instruction."));
    }

    private static Map<String, Object> dependencyManifestSummary(Path root) {
        List<Map<String, Object>> manifests = dependencyManifests(root).stream()
                .map(path -> Map.<String, Object>of("path", rel(root, path), "bytes", safeSize(path)))
                .toList();
        return Map.of("manifests", manifests, "count", manifests.size());
    }

    private static List<Path> dependencyManifests(Path root) {
        Set<String> names = Set.of("package.json", "package-lock.json", "pnpm-lock.yaml", "yarn.lock", "pom.xml", "build.gradle", "build.gradle.kts", "go.mod", "go.sum", "Cargo.toml", "Cargo.lock", "composer.json", "composer.lock", "Gemfile", "Gemfile.lock", "requirements.txt", "pyproject.toml", "Package.swift");
        List<Path> out = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(root, 6)) {
            stream.filter(Files::isRegularFile).filter(path -> names.contains(path.getFileName().toString())).limit(100).forEach(out::add);
        } catch (IOException ignored) {
        }
        return out;
    }

    private static List<Map<String, Object>> filterFindings(Object raw, String file, int line) {
        return listOfMaps(raw).stream()
                .filter(item -> String.valueOf(item.getOrDefault("path", item.getOrDefault("file", ""))).equals(file))
                .filter(item -> Math.abs(intArg(item, "line", line) - line) <= 20)
                .limit(20)
                .toList();
    }

    private static String readExcerpt(Path root, String file, int line, int context) {
        Path path = safeResolve(root, file);
        if (!Files.exists(path)) return "";
        String[] lines = safeRead(path).split("\\R", -1);
        int start = Math.max(1, line - context);
        int end = Math.min(lines.length, line + context);
        StringBuilder out = new StringBuilder();
        for (int i = start; i <= end; i++) out.append(i).append(": ").append(lines[i - 1]).append('\n');
        return out.toString();
    }

    private static String diffFromArgs(Map<String, Object> args, Set<String> pathNeedles) {
        String base = strArg(args, "base", "");
        String head = strArg(args, "head", "");
        String specPath = strArg(args, "path", "");
        Path root = args.containsKey("repo_path") ? path(args, "repo_path") : Path.of(".").toAbsolutePath();
        if (!base.isBlank() && !head.isBlank() && !specPath.isBlank()) {
            return run(root, Duration.ofSeconds(30), "git", "diff", base + ".." + head, "--", specPath).output;
        }
        StringBuilder out = new StringBuilder();
        for (Map<String, Object> file : listOfMaps(args.get("changed_files"))) {
            String path = filePath(file).toLowerCase(Locale.ROOT);
            if (pathNeedles.stream().anyMatch(path::contains)) {
                out.append(patch(file)).append('\n');
            }
        }
        return out.toString();
    }

    private static String detectCodeqlLanguage(Path root) {
        RepoAuditIndexer.AuditIndex index = new RepoAuditIndexer(new Settings("", "", "", "", true, root.resolve(".trace"), root.resolve(".memory"), root.resolve(".report"), 1, 12000, false, false, 1)).index(root);
        Map<String, Object> stack = index.stack();
        if (stack.containsKey("java") || stack.containsKey("kotlin")) return "java-kotlin";
        if (stack.containsKey("typescript") || stack.containsKey("javascript")) return "javascript-typescript";
        if (stack.containsKey("python")) return "python";
        if (stack.containsKey("go")) return "go";
        if (stack.containsKey("rust")) return "";
        if (stack.containsKey("csharp")) return "csharp";
        if (stack.containsKey("cpp") || stack.containsKey("c")) return "c-cpp";
        if (stack.containsKey("ruby")) return "ruby";
        if (stack.containsKey("swift")) return "swift";
        return "";
    }

    private static String inferTestCommand(Path root, List<String> tests) {
        if (tests.isEmpty()) return "";
        if (Files.exists(root.resolve("package.json"))) return "npm test -- " + String.join(" ", tests);
        if (Files.exists(root.resolve("pyproject.toml")) || Files.exists(root.resolve("pytest.ini"))) return "pytest " + String.join(" ", tests);
        if (Files.exists(root.resolve("go.mod"))) return "go test ./...";
        if (Files.exists(root.resolve("Cargo.toml"))) return "cargo test";
        if (Files.exists(root.resolve("Gemfile"))) return "bundle exec rspec " + String.join(" ", tests);
        if (Files.exists(root.resolve("composer.json"))) return "vendor/bin/phpunit " + String.join(" ", tests);
        return "";
    }

    private static Map<String, Object> probe(String path, String type, String description) {
        return Map.of("path", path, "type", type, "description", description);
    }

    private static String migrationRisk(String lower) {
        if (containsAny(lower, "drop table", "drop column")) return "destructive_change";
        if (containsAny(lower, "not null", "alter column")) return "table_rewrite_or_backfill";
        if (containsAny(lower, "create index")) return "locking_index_creation";
        if (containsAny(lower, "update ")) return "large_backfill";
        return "migration_risk";
    }

    private static boolean isTestPath(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        return lower.contains("/test/") || lower.contains("/tests/") || lower.startsWith("test/") || lower.startsWith("tests/")
                || lower.endsWith("_test.go") || lower.endsWith("_test.rs") || lower.endsWith("_spec.rb") || lower.endsWith("_test.rb")
                || lower.endsWith(".spec.ts") || lower.endsWith(".test.ts") || lower.endsWith(".test.tsx") || lower.endsWith("test.java")
                || lower.endsWith("tests.cs") || lower.endsWith("tests.swift") || lower.endsWith("_test.cpp");
    }

    private static boolean containsAny(String text, String... needles) {
        String lower = text == null ? "" : text.toLowerCase(Locale.ROOT);
        for (String needle : needles) if (lower.contains(needle.toLowerCase(Locale.ROOT))) return true;
        return false;
    }

    private static List<Map<String, Object>> listOfMaps(Object raw) {
        if (!(raw instanceof List<?> list)) return List.of();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object item : list) if (item instanceof Map<?, ?> map) out.add((Map<String, Object>) map);
        return out;
    }

    private static List<String> listOfStrings(Object raw) {
        if (!(raw instanceof List<?> list)) return List.of();
        return list.stream().map(String::valueOf).filter(value -> !value.isBlank()).toList();
    }

    private static String filePath(Map<String, Object> file) {
        return String.valueOf(file.getOrDefault("filename", file.getOrDefault("path", "")));
    }

    private static String patch(Map<String, Object> file) {
        return String.valueOf(file.getOrDefault("patch", ""));
    }

    private static Path path(Map<String, Object> args, String key) {
        return Path.of(String.valueOf(args.get(key))).toAbsolutePath().normalize();
    }

    private static Path safeResolve(Path root, String relative) {
        Path resolved = root.resolve(relative).normalize();
        if (!resolved.startsWith(root)) throw new IllegalArgumentException("Path escapes repository root");
        return resolved;
    }

    private static int intArg(Map<String, Object> args, String key, int fallback) {
        Object value = args.get(key);
        if (value instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Exception e) {
            return fallback;
        }
    }

    private static String strArg(Map<String, Object> args, String key, String fallback) {
        Object value = args.get(key);
        return value == null ? fallback : String.valueOf(value);
    }

    private static String safeRead(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    private static long safeSize(Path path) {
        try {
            return Files.size(path);
        } catch (Exception e) {
            return 0L;
        }
    }

    private static String rel(Path root, Path path) {
        try {
            return root.relativize(path).toString();
        } catch (Exception e) {
            return path.toString();
        }
    }

    private static String stem(String path) {
        String name = path.contains("/") ? path.substring(path.lastIndexOf('/') + 1) : path;
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max) + "\n...[truncated]";
    }

    private static String extractJson(String output) {
        int object = output.indexOf('{');
        int array = output.indexOf('[');
        int start = object < 0 ? array : (array < 0 ? object : Math.min(object, array));
        return start < 0 ? "{}" : output.substring(start);
    }

    private record CommandResult(String command, int exitCode, String output) {
    }
}
