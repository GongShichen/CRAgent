package com.cragent.agent;

import com.cragent.config.Settings;
import com.cragent.cli.GitEnvironment;
import com.cragent.llm.LlmClient;
import com.cragent.llm.OpenAiCompatibleClient;
import com.cragent.memory.MemoryStore;
import com.cragent.model.AgentRunResult;
import com.cragent.model.ChatMessage;
import com.cragent.model.Phase;
import com.cragent.model.ReviewIssue;
import com.cragent.model.ReviewResult;
import com.cragent.model.Severity;
import com.cragent.model.ToolCall;
import com.cragent.model.ToolResult;
import com.cragent.skills.SkillLoader;
import com.cragent.tools.GitHubTools;
import com.cragent.tools.AdvancedReviewTools;
import com.cragent.tools.LspTools;
import com.cragent.tools.MemoryTools;
import com.cragent.tools.RepoAuditTools;
import com.cragent.tools.TestGenerationTools;
import com.cragent.tools.ToolRouter;
import com.cragent.trace.TraceRecorder;
import com.cragent.util.Jsons;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

public class CodeReviewAgent {
    private static final Set<String> RECOVERY_TOOLS = Set.of(
            "search_code",
            "get_surrounding_lines",
            "find_related_tests",
            "candidate_evidence_bundle",
            "lsp_evidence_bundle",
            "lsp_symbol_at_position",
            "lsp_call_graph"
    );
    private static final Set<String> VERIFIER_TOOLS = Set.of(
            "candidate_evidence_bundle",
            "lsp_evidence_bundle",
            "get_surrounding_lines",
            "find_related_tests",
            "search_code"
    );
    private static final Set<String> REPO_BATCH_TOOLS = Set.of(
            "read_repo_file_slice",
            "search_repo_text",
            "apple_xcode_context",
            "lsp_symbol_at_position",
            "lsp_call_graph",
            "lsp_evidence_bundle",
            "run_semgrep_scan",
            "run_secret_scan",
            "run_dependency_vulnerability_scan",
            "detect_public_api_changes",
            "route_contract_diff",
            "openapi_schema_diff",
            "protobuf_schema_diff",
            "db_migration_risk_analyzer",
            "github_actions_permission_audit",
            "dockerfile_risk_scan",
            "lockfile_diff_summary",
            "license_policy_check"
    );
    private final Settings settings;
    private final LlmClient llm;
    private final TraceRecorder trace;
    private final ToolRouter router;

    public CodeReviewAgent(Settings settings, LlmClient llm) {
        this(settings, llm, new TraceRecorder(settings.traceDir()));
        registerToolsSafely();
    }

    public CodeReviewAgent(Settings settings, LlmClient llm, TraceRecorder trace, ToolRouter router) {
        this.settings = settings;
        this.llm = llm;
        this.trace = trace;
        this.router = router;
    }

    private CodeReviewAgent(Settings settings, LlmClient llm, TraceRecorder trace) {
        this.settings = settings;
        this.llm = llm;
        this.trace = trace;
        this.router = new ToolRouter(settings.dryRun(), trace, settings.maxToolResultChars());
    }

    private void registerToolsSafely() {
        try {
            new GitHubTools(settings.githubToken()).register(router);
        } catch (Exception e) {
            trace.record("warning", Map.of("warning", "GitHub tools were not registered", "error", safeMessage(e)));
        }
        try {
            new MemoryTools(new MemoryStore(settings.memoryDir())).register(router);
        } catch (Exception e) {
            trace.record("warning", Map.of("warning", "Memory tools were not registered", "error", safeMessage(e)));
        }
        try {
            new TestGenerationTools().register(router);
        } catch (Exception e) {
            trace.record("warning", Map.of("warning", "Test generation tools were not registered", "error", safeMessage(e)));
        }
        try {
            new RepoAuditTools(settings).register(router);
        } catch (Exception e) {
            trace.record("warning", Map.of("warning", "Repo audit tools were not registered", "error", safeMessage(e)));
        }
        try {
            new LspTools(settings).register(router);
        } catch (Exception e) {
            trace.record("warning", Map.of("warning", "LSP tools were not registered", "error", safeMessage(e)));
        }
        try {
            new AdvancedReviewTools(settings).register(router);
        } catch (Exception e) {
            trace.record("warning", Map.of("warning", "Advanced review tools were not registered", "error", safeMessage(e)));
        }
    }

    public AgentRunResult review(String repo, int pr) {
        trace.record("session_start", Map.of("repo", repo, "pr", pr, "dry_run", settings.dryRun()));
        ReviewResult reviewResult;
        List<Map<String, Object>> actions = new ArrayList<>();
        String status = "completed";
        Object languageSkillSelection = List.of();
        Object contextEngine = Map.of();
        try {
            Map<String, Object> triage = triage(repo, pr);
            if (Boolean.TRUE.equals(triage.get("human_required")) || !Boolean.TRUE.equals(triage.get("should_review"))) {
                reviewResult = skippedResult(triage);
                actions = actOnTriageDecision(repo, pr, triage, reviewResult);
                return finishRun(repo, pr, status, reviewResult, actions, Map.of("target", "pull_request", "pr", pr));
            }
            Map<String, Object> analysis = analyze(repo, pr, triage);
            reviewResult = reviewPhase(repo, pr, triage, analysis);
            languageSkillSelection = analysis.getOrDefault("language_skill_selection", List.of());
            contextEngine = analysis.getOrDefault("context_engine", Map.of());
            for (ReviewIssue issue : reviewResult.issues) {
                trace.record("issue_found", Map.of("issue", issue));
            }
            actions = act(repo, pr, triage, analysis, reviewResult);
        } catch (Exception e) {
            status = "failed";
            reviewResult = new ReviewResult();
            String message = safeMessage(e);
            reviewResult.summary = "Review failed: " + message;
            trace.record("error", Map.of("error", message, "exception", e.getClass().getName(), "stack_trace", stackTrace(e)));
        }
        return finishRun(repo, pr, status, reviewResult, actions, targetWithLanguageSkills(Map.of("target", "pull_request", "pr", pr), languageSkillSelection, contextEngine));
    }

    public AgentRunResult reviewCommits(String repo, String base, String head) {
        trace.record("session_start", Map.of("repo", repo, "base", base, "head", head, "target", "commit_range", "dry_run", settings.dryRun()));
        ReviewResult reviewResult;
        List<Map<String, Object>> actions = new ArrayList<>();
        String status = "completed";
        Object languageSkillSelection = List.of();
        Object contextEngine = Map.of();
        try {
            Map<String, Object> triage = triageCommits(repo, base, head);
            if (Boolean.TRUE.equals(triage.get("human_required")) || !Boolean.TRUE.equals(triage.get("should_review"))) {
                reviewResult = skippedResult(triage);
            } else {
                Map<String, Object> analysis = analyzeCommits(repo, base, head, triage);
                reviewResult = reviewPhase(repo, "commits:" + base + "..." + head, triage, analysis);
                languageSkillSelection = analysis.getOrDefault("language_skill_selection", List.of());
                contextEngine = analysis.getOrDefault("context_engine", Map.of());
                for (ReviewIssue issue : reviewResult.issues) {
                    trace.record("issue_found", Map.of("issue", issue));
                }
                actions = actOnCommitRange(repo, triage, analysis, reviewResult);
            }
        } catch (Exception e) {
            status = "failed";
            reviewResult = new ReviewResult();
            String message = safeMessage(e);
            reviewResult.summary = "Review failed: " + message;
            trace.record("error", Map.of("error", message, "exception", e.getClass().getName(), "stack_trace", stackTrace(e)));
        }
        return finishRun(repo, 0, status, reviewResult, actions, targetWithLanguageSkills(Map.of("target", "commit_range", "base", base, "head", head), languageSkillSelection, contextEngine));
    }

    public AgentRunResult reviewLocalGitCommits(String repo, String base, String head, List<Map<String, Object>> changedFiles,
                                                String diff, List<Map<String, Object>> commits, String author) {
        return reviewLocalGitCommits(repo, base, head, changedFiles, diff, commits, author, null);
    }

    public AgentRunResult reviewLocalGitCommits(String repo, String base, String head, List<Map<String, Object>> changedFiles,
                                                String diff, List<Map<String, Object>> commits, String author, Path repoPath) {
        trace.record("session_start", Map.of("repo", repo, "base", base, "head", head, "target", "local_git_commit_range", "dry_run", settings.dryRun()));
        ReviewResult reviewResult;
        List<Map<String, Object>> actions = new ArrayList<>();
        String status = "completed";
        Object languageSkillSelection = List.of();
        Object contextEngine = Map.of();
        try {
            Map<String, Object> triage = triageProvidedCommits(repo, base, head, changedFiles, author);
            if (Boolean.TRUE.equals(triage.get("human_required")) || !Boolean.TRUE.equals(triage.get("should_review"))) {
                reviewResult = skippedResult(triage);
            } else {
                Map<String, Object> analysis = analyzeProvidedCommits(repo, triage, diff, commits, repoPath);
                reviewResult = reviewPhase(repo, "local-git:" + base + "..." + head, triage, analysis);
                languageSkillSelection = analysis.getOrDefault("language_skill_selection", List.of());
                contextEngine = analysis.getOrDefault("context_engine", Map.of());
                for (ReviewIssue issue : reviewResult.issues) {
                    trace.record("issue_found", Map.of("issue", issue));
                }
                actions = actOnCommitRange(repo, triage, analysis, reviewResult);
            }
        } catch (Exception e) {
            status = "failed";
            reviewResult = new ReviewResult();
            String message = safeMessage(e);
            reviewResult.summary = "Review failed: " + message;
            trace.record("error", Map.of("error", message, "exception", e.getClass().getName(), "stack_trace", stackTrace(e)));
        }
        return finishRun(repo, 0, status, reviewResult, actions, targetWithLanguageSkills(Map.of("target", "local_git_commit_range", "base", base, "head", head), languageSkillSelection, contextEngine));
    }

    public AgentRunResult reviewRepository(String repo) {
        trace.record("session_start", Map.of("repo", repo, "target", "repo_audit", "dry_run", settings.dryRun()));
        ReviewResult reviewResult;
        List<Map<String, Object>> actions = new ArrayList<>();
        String status = "completed";
        Map<String, Object> auditContext = new LinkedHashMap<>();
        try (GitEnvironment.RepositoryLease lease = acquireRepo(repo)) {
            trace.record("phase_start", Map.of("phase", Phase.REPO_INDEX.name()));
            RepoAuditIndexer indexer = new RepoAuditIndexer(settings);
            RepoAuditIndexer.AuditIndex index = indexer.index(lease.repoPath());
            List<Map<String, Object>> manifest = index.files().stream().map(RepoAuditIndexer.AuditFile::manifest).toList();
            List<Map<String, Object>> coverage = initialCoverage(index);
            trace.record("repo_index", Map.of(
                    "repo_path", lease.repoPath().toString(),
                    "temporary_clone", lease.temporaryClone(),
                    "files", manifest.size(),
                    "slices", index.slices().size(),
                    "skipped", index.skipped()
            ));
            trace.record("phase_end", Map.of("phase", Phase.REPO_INDEX.name(), "files", manifest.size(), "slices", index.slices().size()));

            trace.record("phase_start", Map.of("phase", Phase.RISK_MODEL.name()));
            Map<String, Object> lspContext = repoLspContext(lease.repoPath(), index);
            Map<String, Object> appleContext = AppleXcodeContext.probe(lease.repoPath());
            if (Boolean.TRUE.equals(appleContext.get("has_apple_markers"))) {
                trace.record("apple_platform_context", appleContext);
            }
            Map<String, Object> sharedAnalysis = repoAuditSharedAnalysis(repo, index, manifest, List.of(), lspContext);
            sharedAnalysis.put("apple_platform_context", appleContext);
            Map<String, Object> risk = repoAuditRiskModel(index, lspContext);
            if (Boolean.TRUE.equals(appleContext.get("has_apple_markers"))) {
                risk.put("apple_platform_context", appleContext);
            }
            risk.put("diff_node_risk_model", sharedAnalysis.getOrDefault("risk_model", Map.of()));
            risk.put("regression_test_reasoning", sharedAnalysis.getOrDefault("regression_test_reasoning", Map.of()));
            risk.put("context_expansion", sharedAnalysis.getOrDefault("context_expansion", Map.of()));
            LanguageSkillRouter.Selection repoSkillSelection = new LanguageSkillRouter(settings, llm, trace).selectForRepoAudit(manifest, risk, sharedAnalysis);
            sharedAnalysis.put("repo_language_skill_selection", repoSkillSelection.selectedSkills());
            trace.record("phase_end", Map.of("phase", Phase.RISK_MODEL.name(), "result", risk));

            trace.record("phase_start", Map.of("phase", Phase.STATIC_CHECKS.name()));
            List<Map<String, Object>> checks = settings.repoAuditRunChecks() ? new RepoStaticChecks().run(lease.repoPath(), index.stack()) : List.of();
            trace.record("static_check", Map.of("checks", checks));
            trace.record("phase_end", Map.of("phase", Phase.STATIC_CHECKS.name(), "checks", checks));
            sharedAnalysis.put("static_checks", checks);

            List<ReviewIssue> issues = progressiveRepoReview(repo, index, manifest, coverage, checks, risk, lspContext, sharedAnalysis);
            reviewResult = new ReviewResult();
            reviewResult.issues = repoEvidenceValidation(issues, index, checks, lspContext, sharedAnalysis);
            reviewResult.summary = "Full repository audit completed with " + reviewResult.issues.size() + " validated issue(s).";
            reviewResult.shouldComment = false;
            reviewResult.shouldCreateFixPr = false;
            reviewResult.shouldUpdateMemory = true;
            trace.record("repo_audit_result", Map.of("summary", reviewResult.summary, "issues", reviewResult.issues));

            auditContext.put("target", "repo_audit");
            auditContext.put("coverage_summary", coverageSummary(index, coverage));
            auditContext.put("risk_model", risk);
            auditContext.put("static_checks", checks);
            auditContext.put("lsp_context", lspContext);
            auditContext.put("shared_analysis", sharedAnalysis);
            auditContext.put("context_engine", sharedAnalysis.getOrDefault("context_engine", Map.of()));
            auditContext.put("language_skill_selection", sharedAnalysis.getOrDefault("repo_language_skill_selection", List.of()));
            auditContext.put("repo_path", lease.repoPath().toString());
            if (reviewResult.shouldUpdateMemory) {
                ToolResult patterns = call("memory_aggregate_patterns", Map.of("repo", repo, "issues", issueMaps(reviewResult.issues)));
                actions.add(Map.of("name", "memory_aggregate_patterns", "result", patterns));
            }
        } catch (Exception e) {
            status = "failed";
            reviewResult = new ReviewResult();
            String message = safeMessage(e);
            reviewResult.summary = "Repository audit failed: " + message;
            trace.record("error", Map.of("error", message, "exception", e.getClass().getName(), "stack_trace", stackTrace(e)));
        }
        if (auditContext.isEmpty()) {
            auditContext.put("target", "repo_audit");
        }
        return finishRun(repo, 0, status, reviewResult, actions, auditContext);
    }

    private GitEnvironment.RepositoryLease acquireRepo(String repo) {
        trace.record("phase_start", Map.of("phase", Phase.REPO_ACQUIRE.name(), "repo", repo));
        GitEnvironment.RepositoryLease lease = GitEnvironment.acquireRepository(repo);
        if (lease == null) {
            throw new IllegalStateException("Unable to acquire repository via local clone or temporary clone: " + repo);
        }
        trace.record("phase_end", Map.of("phase", Phase.REPO_ACQUIRE.name(), "repo_path", lease.repoPath().toString(), "temporary_clone", lease.temporaryClone()));
        return lease;
    }

    private AgentRunResult finishRun(String repo, int pr, String status, ReviewResult reviewResult,
                                     List<Map<String, Object>> actions, Map<String, Object> target) {
        AgentRunResult result = new AgentRunResult();
        result.sessionId = trace.sessionId();
        result.repo = repo;
        result.pr = pr;
        result.dryRun = settings.dryRun();
        result.status = status;
        result.summary = reviewResult.summary;
        result.issues = reviewResult.issues;
        result.actions = actions;
        result.tracePath = trace.path();
        result.reportPath = reportPhase(result, target);
        trace.record("session_end", Map.of(
                "status", status,
                "summary", reviewResult.summary,
                "issues_found", reviewResult.issues.size(),
                "actions", actions,
                "report_path", result.reportPath == null ? "" : result.reportPath.toString()
        ));
        return result;
    }

    private static Map<String, Object> targetWithLanguageSkills(Map<String, Object> target, Object languageSkillSelection, Object contextEngine) {
        Map<String, Object> out = new LinkedHashMap<>(target);
        if (languageSkillSelection != null) {
            out.put("language_skill_selection", languageSkillSelection);
        }
        if (contextEngine != null) {
            out.put("context_engine", contextEngine);
        }
        return out;
    }

    private void applyTriageAdvice(String target, Map<String, Object> triage) {
        Map<String, Object> advice = LlmAdvisoryNodes.triageAdvice(settings, llm, trace, target, triage);
        triage.put("triage_advice", advice);
        List<String> focus = new ArrayList<>(stringList(triage.getOrDefault("focus_areas", List.of())));
        for (String item : stringList(advice.get("review_focus"))) {
            addUnique(focus, item);
        }
        triage.put("focus_areas", focus);
        String riskLevel = String.valueOf(advice.getOrDefault("risk_level", ""));
        if ("high".equalsIgnoreCase(riskLevel) && !Boolean.TRUE.equals(triage.get("docs_only"))) {
            triage.put("high_risk", true);
        }
        if (advice.get("human_attention_advice") instanceof Boolean b) {
            triage.put("llm_human_attention_advice", b);
        }
    }

    private Map<String, Object> triage(String repo, int pr) {
        trace.record("phase_start", Map.of("phase", Phase.TRIAGE.name()));
        Map<String, Object> repoArgs = repoArgs(repo);
        Object pull = call("get_pull_request", withPr(repoArgs, pr)).result;
        Object files = call("list_changed_files", withPr(repoArgs, pr)).result;
        List<Map<String, Object>> fileMaps = listOfMaps(files);
        String title = textFromPull(pull, "title");
        String body = textFromPull(pull, "body");
        boolean draft = booleanFromPull(pull, "draft");
        int changedLines = totalChangedLines(pull, fileMaps);
        boolean docsOnly = !fileMaps.isEmpty() && fileMaps.stream().allMatch(file -> docsOrConfigOnly(String.valueOf(file.get("filename"))));
        List<String> securityFiles = fileMaps.stream()
                .map(file -> String.valueOf(file.get("filename")))
                .filter(CodeReviewAgent::securityCoreFile)
                .toList();
        boolean breakingOrDesign = containsAny(title + "\n" + body, "breaking change", "breaks compatibility", "rfc", "design doc", "architecture");
        boolean humanRequired = draft || breakingOrDesign || securityFiles.size() >= 3;
        boolean highRisk = changedLines > 300 || breakingOrDesign || !securityFiles.isEmpty();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("pull_request", pull);
        result.put("changed_files", files);
        result.put("docs_only", docsOnly);
        result.put("high_risk", highRisk);
        result.put("should_review", !docsOnly && !humanRequired);
        result.put("human_required", humanRequired);
        result.put("skip_reason", skipReason(docsOnly, draft, changedLines, breakingOrDesign, securityFiles.size()));
        result.put("focus_areas", focusAreas(fileMaps, securityFiles, highRisk));
        result.put("files_to_review", fileMaps.stream().map(file -> file.get("filename")).toList());
        result.put("estimated_complexity", complexity(changedLines, fileMaps.size(), highRisk));
        result.put("changed_lines", changedLines);
        result.put("hard_rule_triggered", humanRequired);
        result.put("confidence", 0.9);
        result.put("author", authorFromPull(pull));
        applyTriageAdvice("PR #" + pr, result);
        trace.record("phase_end", Map.of("phase", Phase.TRIAGE.name(), "result", result));
        return result;
    }

    private Map<String, Object> analyze(String repo, int pr, Map<String, Object> triage) {
        trace.record("phase_start", Map.of("phase", Phase.ANALYZE.name()));
        Map<String, Object> repoArgs = repoArgs(repo);
        String headRef = headRevision(triage.get("pull_request"));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("diff", call("get_pr_diff", withPr(repoArgs, pr)).result);
        result.put("comments", call("list_review_comments", withPr(repoArgs, pr)).result);
        result.put("checks", call("list_checks", withPr(repoArgs, pr)).result);
        result.put("commits", call("list_commits", withExtra(repoArgs, Map.of("sha", headRef, "per_page", 20))).result);
        result.put("test_framework", call("detect_test_framework", withExtra(repoArgs, Map.of("branch", headRef))).result);
        Map<String, Object> contextExpansion = contextExpansion(repoArgs, headRef, triage);
        result.put("context_expansion", contextExpansion);
        result.put("dependency_manifests", contextExpansion.get("dependency_manifests"));
        result.put("sensitive_paths", contextExpansion.get("sensitive_paths"));
        result.put("related_tests", contextExpansion.get("related_tests"));
        result.put("security_file_contents", contextExpansion.get("security_file_contents"));
        Map<String, Object> repoContext = diffRepoContext(repo, triage, null);
        result.put("repo_context", repoContext);
        result.put("repo_manifest", repoContext.getOrDefault("changed_manifest", List.of()));
        result.put("lsp_context", repoContext.getOrDefault("lsp_context", Map.of()));
        result.put("static_checks", repoContext.getOrDefault("static_checks", List.of()));
        result.put("risk_model", riskModel(triage, result));
        result.put("regression_test_reasoning", regressionTestReasoning(triage, result));
        result.put("risk_probes", recallRiskProbes(triage, result));
        result.put("memory", call("memory_get_all", Map.of("repo", repo, "author", triage.get("author"))).result);
        result.put("context_scout", LlmAdvisoryNodes.contextScout(settings, llm, trace, "diff", triage, result));
        result.put("context_engine", ContextEngine.forDiff(settings, triage, result, trace));
        trace.record("phase_end", Map.of("phase", Phase.ANALYZE.name(), "result", result));
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> triageCommits(String repo, String base, String head) {
        trace.record("phase_start", Map.of("phase", Phase.TRIAGE.name()));
        Map<String, Object> repoArgs = repoArgs(repo);
        ToolResult compareResult = call("get_commit_compare", withExtra(repoArgs, Map.of("base", base, "head", head)));
        Map<String, Object> compare = compareResult.result instanceof Map<?, ?> map ? new LinkedHashMap<>((Map<String, Object>) map) : new LinkedHashMap<>();
        Object files = compare.getOrDefault("files", List.of());
        List<Map<String, Object>> fileMaps = listOfMaps(files);
        int changedLines = totalChangedLines(compare, fileMaps);
        boolean docsOnly = !fileMaps.isEmpty() && fileMaps.stream().allMatch(file -> docsOrConfigOnly(String.valueOf(file.get("filename"))));
        List<String> securityFiles = fileMaps.stream()
                .map(file -> String.valueOf(file.get("filename")))
                .filter(CodeReviewAgent::securityCoreFile)
                .toList();
        boolean humanRequired = securityFiles.size() >= 3;
        boolean highRisk = changedLines > 300 || !securityFiles.isEmpty();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("target", "commit_range");
        result.put("base", base);
        result.put("head", head);
        result.put("compare", compare);
        result.put("changed_files", files);
        result.put("docs_only", docsOnly);
        result.put("high_risk", highRisk);
        result.put("should_review", !docsOnly && !humanRequired);
        result.put("human_required", humanRequired);
        result.put("skip_reason", skipReason(docsOnly, false, changedLines, false, securityFiles.size()));
        result.put("focus_areas", focusAreas(fileMaps, securityFiles, highRisk));
        result.put("files_to_review", fileMaps.stream().map(file -> file.get("filename")).toList());
        result.put("estimated_complexity", complexity(changedLines, fileMaps.size(), highRisk));
        result.put("changed_lines", changedLines);
        result.put("hard_rule_triggered", humanRequired);
        result.put("confidence", compareResult.ok ? 0.9 : 0.2);
        result.put("author", commitAuthor(compare));
        applyTriageAdvice("commits:" + base + "..." + head, result);
        trace.record("phase_end", Map.of("phase", Phase.TRIAGE.name(), "result", result));
        return result;
    }

    private Map<String, Object> analyzeCommits(String repo, String base, String head, Map<String, Object> triage) {
        trace.record("phase_start", Map.of("phase", Phase.ANALYZE.name()));
        Map<String, Object> repoArgs = repoArgs(repo);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("diff", call("get_commit_compare_diff", withExtra(repoArgs, Map.of("base", base, "head", head))).result);
        result.put("comments", List.of());
        result.put("checks", Map.of("note", "No PR checks are available for direct commit range review."));
        result.put("commits", commitsFromCompare(triage.get("compare")));
        result.put("test_framework", call("detect_test_framework", withExtra(repoArgs, Map.of("branch", head))).result);
        Map<String, Object> contextExpansion = contextExpansion(repoArgs, head, triage);
        result.put("context_expansion", contextExpansion);
        result.put("dependency_manifests", contextExpansion.get("dependency_manifests"));
        result.put("sensitive_paths", contextExpansion.get("sensitive_paths"));
        result.put("related_tests", contextExpansion.get("related_tests"));
        result.put("security_file_contents", contextExpansion.get("security_file_contents"));
        Map<String, Object> repoContext = diffRepoContext(repo, triage, null);
        result.put("repo_context", repoContext);
        result.put("repo_manifest", repoContext.getOrDefault("changed_manifest", List.of()));
        result.put("lsp_context", repoContext.getOrDefault("lsp_context", Map.of()));
        result.put("static_checks", repoContext.getOrDefault("static_checks", List.of()));
        result.put("risk_model", riskModel(triage, result));
        result.put("regression_test_reasoning", regressionTestReasoning(triage, result));
        result.put("risk_probes", recallRiskProbes(triage, result));
        result.put("memory", call("memory_get_all", Map.of("repo", repo, "author", triage.get("author"))).result);
        result.put("context_scout", LlmAdvisoryNodes.contextScout(settings, llm, trace, "diff", triage, result));
        result.put("context_engine", ContextEngine.forDiff(settings, triage, result, trace));
        trace.record("phase_end", Map.of("phase", Phase.ANALYZE.name(), "result", result));
        return result;
    }

    private Map<String, Object> triageProvidedCommits(String repo, String base, String head, List<Map<String, Object>> fileMaps, String author) {
        trace.record("phase_start", Map.of("phase", Phase.TRIAGE.name()));
        int changedLines = totalChangedLines(Map.of(), fileMaps);
        boolean docsOnly = !fileMaps.isEmpty() && fileMaps.stream().allMatch(file -> docsOrConfigOnly(String.valueOf(file.get("filename"))));
        List<String> securityFiles = fileMaps.stream()
                .map(file -> String.valueOf(file.get("filename")))
                .filter(CodeReviewAgent::securityCoreFile)
                .toList();
        boolean humanRequired = securityFiles.size() >= 3;
        boolean highRisk = changedLines > 300 || !securityFiles.isEmpty();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("target", "local_git_commit_range");
        result.put("base", base);
        result.put("head", head);
        result.put("changed_files", fileMaps);
        result.put("docs_only", docsOnly);
        result.put("high_risk", highRisk);
        result.put("should_review", !docsOnly && !humanRequired);
        result.put("human_required", humanRequired);
        result.put("skip_reason", skipReason(docsOnly, false, changedLines, false, securityFiles.size()));
        result.put("focus_areas", focusAreas(fileMaps, securityFiles, highRisk));
        result.put("files_to_review", fileMaps.stream().map(file -> file.get("filename")).toList());
        result.put("estimated_complexity", complexity(changedLines, fileMaps.size(), highRisk));
        result.put("changed_lines", changedLines);
        result.put("hard_rule_triggered", humanRequired);
        result.put("confidence", 0.9);
        result.put("author", author == null || author.isBlank() ? "unknown" : author);
        applyTriageAdvice("local-git:" + base + "..." + head, result);
        trace.record("phase_end", Map.of("phase", Phase.TRIAGE.name(), "result", result));
        return result;
    }

    private Map<String, Object> analyzeProvidedCommits(String repo, Map<String, Object> triage, String diff, List<Map<String, Object>> commits) {
        return analyzeProvidedCommits(repo, triage, diff, commits, null);
    }

    private Map<String, Object> analyzeProvidedCommits(String repo, Map<String, Object> triage, String diff, List<Map<String, Object>> commits, Path repoPath) {
        trace.record("phase_start", Map.of("phase", Phase.ANALYZE.name()));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("diff", diff == null ? "" : diff);
        result.put("comments", List.of());
        result.put("checks", Map.of("note", "Local git review does not include GitHub PR checks."));
        result.put("commits", commits == null ? List.of() : commits);
        result.put("test_framework", Map.of("primary_framework", "unknown", "source", "local_git"));
        result.put("context_expansion", Map.of("source", "local_git", "note", "GitHub API context unavailable; review uses local git diff."));
        result.put("dependency_manifests", List.of());
        result.put("sensitive_paths", List.of());
        result.put("related_tests", List.of());
        result.put("security_file_contents", List.of());
        Map<String, Object> repoContext = diffRepoContext(repo, triage, repoPath);
        result.put("repo_context", repoContext);
        result.put("repo_manifest", repoContext.getOrDefault("changed_manifest", List.of()));
        result.put("lsp_context", repoContext.getOrDefault("lsp_context", Map.of()));
        result.put("static_checks", repoContext.getOrDefault("static_checks", List.of()));
        result.put("risk_model", riskModel(triage, result));
        result.put("regression_test_reasoning", regressionTestReasoning(triage, result));
        result.put("risk_probes", recallRiskProbes(triage, result));
        result.put("memory", call("memory_get_all", Map.of("repo", repo, "author", triage.get("author"))).result);
        result.put("context_scout", LlmAdvisoryNodes.contextScout(settings, llm, trace, "diff", triage, result));
        result.put("context_engine", ContextEngine.forDiff(settings, triage, result, trace));
        trace.record("phase_end", Map.of("phase", Phase.ANALYZE.name(), "result", result));
        return result;
    }

    private ReviewResult reviewPhase(String repo, int pr, Map<String, Object> triage, Map<String, Object> analysis) {
        return reviewPhase(repo, "PR #" + pr, triage, analysis);
    }

    private ReviewResult reviewPhase(String repo, String target, Map<String, Object> triage, Map<String, Object> analysis) {
        trace.record("phase_start", Map.of("phase", Phase.REVIEW.name()));
        Map<String, Object> reviewStrategy = sharedReviewStrategy(analysis);
        LanguageSkillRouter.Selection languageSkills = new LanguageSkillRouter(settings, llm, trace).selectForDiff(triage, analysis);
        analysis.put("language_skill_selection", languageSkills.selectedSkills());
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(new ChatMessage("system", SkillLoader.defaultPrompt()));
        if (languageSkills.hasPrompt()) {
            messages.add(new ChatMessage("system", "Selected language-specific code review skills for this target:\n\n" + languageSkills.prompt()));
        }
        messages.add(new ChatMessage("user", Jsons.stringify(Map.of(
                "repo", repo,
                "target", target,
                "triage", triage,
                "analysis", analysis,
                "review_strategy", reviewStrategy,
                "context_engine", analysis.getOrDefault("context_engine", Map.of()),
                "language_skill_selection", languageSkills.selectedSkills(),
                "instruction", """
                        Return exactly one valid JSON object. Do not return Markdown, code fences, tables, or explanation outside JSON.
                        Every string value must be valid JSON-escaped text. If a suggestion contains double quotes, write them as \\\".
                        Do not include raw template strings or raw code snippets that would break JSON string syntax.
                        Use context_engine.context_pack as the preferred concise context. Use risk_model to focus review, use lsp_context for symbol diagnostics/definitions/references when available, use regression_test_reasoning for test-gap findings, and include evidence/impact for every issue.
                        When a finding depends on non-diff context, cite the relevant context item id in the evidence text, for example "ctx-3 shows ...".
                        Schema: {"summary":"...","issues":[{"severity":"critical|high|medium|low|info","category":"security|bug|style|performance|maintainability|tests","file":"path","line":1,"body":"problem","evidence":"exact diff/config/check evidence","impact":"why this matters in production","suggestion":"fix","autoFixable":false,"fixCode":null,"confidence":0.9}],"shouldComment":true,"shouldCreateFixPr":false,"shouldUpdateMemory":true}
                        """
        )) + "\n\nSTRICT OUTPUT RULE: valid JSON object only. JSON strings must escape inner double quotes as \\\"."));
        ChatMessage finalMessage = null;
        List<Map<String, Object>> schemas = router.schemas();
        for (int iteration = 1; iteration <= settings.maxIterations(); iteration++) {
            trace.record("llm_request", Map.of(
                    "phase", Phase.REVIEW.name(),
                    "iteration", iteration,
                    "messages", messages,
                    "tools", schemas,
                    "temperature", 0.1
            ));
            Map<String, Object> response = llm.chatJson(messages, schemas, 0.1);
            trace.record("llm_response", Map.of("phase", Phase.REVIEW.name(), "iteration", iteration, "response", response));
            ChatMessage assistant = OpenAiCompatibleClient.assistantMessage(response);
            messages.add(assistant);
            List<ToolCall> calls = OpenAiCompatibleClient.extractToolCalls(assistant);
            if (calls.isEmpty()) {
                finalMessage = assistant;
                break;
            }
            for (ToolCall call : calls) {
                ToolResult result = router.call(call);
                messages.add(ChatMessage.tool(result.toolCallId, Jsons.stringify(result)));
            }
        }
        if (finalMessage == null) {
            trace.record("max_iterations", Map.of("phase", Phase.REVIEW.name(), "iterations", settings.maxIterations()));
            return ReviewResultParser.parse("{\"summary\":\"Review stopped after max iterations.\",\"issues\":[],\"shouldComment\":false}");
        }
        ReviewResult result = evidenceValidation(ReviewResultParser.parse(finalMessage.content), triage, analysis);
        if (settings.zeroIssueRecovery() && result.issues.isEmpty() && shouldRecoverZeroIssues(triage, analysis)) {
            ReviewResult recovery = evidenceValidation(recoveryReview(repo, target, triage, analysis), triage, analysis);
            result.issues.addAll(recovery.issues);
            if (!recovery.summary.isBlank()) {
                result.summary = result.summary + " Recovery pass: " + recovery.summary;
            }
            result.shouldComment = result.shouldComment || !result.issues.isEmpty();
            trace.record("zero_issue_recovery", Map.of("issues", recovery.issues.size(), "summary", recovery.summary));
        }
        result = verifyAndPublish(repo, target, triage, analysis, result);
        trace.record("phase_end", Map.of("phase", Phase.REVIEW.name(), "result", result));
        return result;
    }

    private static Map<String, Object> sharedReviewStrategy(Map<String, Object> analysis) {
        return ReviewAnalysisNodes.sharedReviewStrategy(analysis);
    }

    private ReviewResult recoveryReview(String repo, String target, Map<String, Object> triage, Map<String, Object> analysis) {
        trace.record("phase_start", Map.of("phase", "ZERO_ISSUE_RECOVERY"));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("repo", repo);
        payload.put("target", target);
        payload.put("triage", triage);
        payload.put("risk_model", analysis.getOrDefault("risk_model", Map.of()));
        payload.put("risk_probes", topRiskProbes(analysis, 8));
        payload.put("context_expansion", analysis.getOrDefault("context_expansion", Map.of()));
        payload.put("repo_context", analysis.getOrDefault("repo_context", Map.of()));
        payload.put("context_engine", analysis.getOrDefault("context_engine", Map.of()));
        payload.put("language_skill_selection", analysis.getOrDefault("language_skill_selection", List.of()));
        payload.put("diff", truncated(String.valueOf(analysis.getOrDefault("diff", "")), 12000));
        payload.put("instruction", """
                This is a targeted recovery pass after a first review found no issues.
                Use only concrete changed-code evidence and the supplied risk probes.
                You may call only the provided recovery tools. Tool round budget: %d.
                Use tools only to confirm a high-signal risk probe with surrounding code, callers, tests, or LSP evidence.
                Return at most 3 high-signal bug/security/performance/test-gap issues as valid JSON.
                Schema: {"summary":"...","issues":[{"severity":"critical|high|medium|low|info","category":"security|bug|performance|tests|maintainability","file":"path","line":1,"body":"problem","evidence":"specific changed hunk or context","impact":"why this matters","suggestion":"fix","confidence":0.0}],"shouldComment":true}
                """.formatted(settings.recoveryMaxToolRounds()));
        List<ChatMessage> messages = new ArrayList<>(List.of(
                new ChatMessage("system", "You are a conservative code review recovery node. You find missed high-signal defects, not style comments."),
                new ChatMessage("user", Jsons.stringify(payload))
        ));
        try {
            ChatMessage finalMessage = runBoundedToolLoop("ZERO_ISSUE_RECOVERY", messages, RECOVERY_TOOLS, settings.recoveryMaxToolRounds(), 0.1);
            ReviewResult result = finalMessage == null
                    ? ReviewResultParser.parse("{\"summary\":\"Recovery stopped after tool budget.\",\"issues\":[],\"shouldComment\":false}")
                    : ReviewResultParser.parse(finalMessage.content);
            if (result.issues.size() > 3) {
                result.issues = result.issues.stream().limit(3).collect(Collectors.toCollection(ArrayList::new));
            }
            trace.record("phase_end", Map.of("phase", "ZERO_ISSUE_RECOVERY", "issues", result.issues.size()));
            return result;
        } catch (Exception e) {
            trace.record("warning", Map.of("phase", "ZERO_ISSUE_RECOVERY", "error", safeMessage(e)));
            ReviewResult result = new ReviewResult();
            result.summary = "Recovery pass failed: " + safeMessage(e);
            result.shouldComment = false;
            trace.record("phase_end", Map.of("phase", "ZERO_ISSUE_RECOVERY", "error", safeMessage(e)));
            return result;
        }
    }

    private ReviewResult verifyAndPublish(String repo, String target, Map<String, Object> triage, Map<String, Object> analysis, ReviewResult result) {
        List<ReviewIssue> candidates = new ArrayList<>(result.issues);
        if (settings.verifierEnabled() && !candidates.isEmpty()) {
            trace.record("phase_start", Map.of("phase", "CANDIDATE_VERIFIER", "candidates", candidates.size()));
            candidates = verifyCandidates(repo, target, triage, analysis, candidates);
            trace.record("phase_end", Map.of("phase", "CANDIDATE_VERIFIER", "candidates", candidates.size()));
        }
        List<ReviewIssue> published = candidates.stream()
                .filter(issue -> !"DROP".equalsIgnoreCase(nullToEmpty(issue.validationVerdict)))
                .filter(issue -> issue.candidateScore >= publishThreshold(issue))
                .sorted(Comparator.comparingDouble((ReviewIssue issue) -> issue.candidateScore).reversed())
                .limit(Math.max(1, settings.reviewMaxComments()))
                .collect(Collectors.toCollection(ArrayList::new));
        trace.record("candidate_publish", Map.of(
                "input", result.issues.size(),
                "after_verification", candidates.size(),
                "published", published.size(),
                "threshold", settings.reviewPublishThreshold()
        ));
        result.issues = published;
        result.shouldComment = result.shouldComment && !published.isEmpty();
        return result;
    }

    private List<ReviewIssue> verifyCandidates(String repo, String target, Map<String, Object> triage,
                                               Map<String, Object> analysis, List<ReviewIssue> candidates) {
        List<ReviewIssue> ordered = candidates.stream()
                .sorted(Comparator.comparingDouble((ReviewIssue issue) -> issue.candidateScore).reversed())
                .collect(Collectors.toCollection(ArrayList::new));
        int verified = 0;
        for (ReviewIssue issue : ordered) {
            if (verified >= settings.verifierMaxCandidates()) {
                break;
            }
            if (issue.candidateScore < 0.30 || issue.candidateScore >= 0.75) {
                continue;
            }
            verified++;
            applyVerifierVerdict(repo, target, triage, analysis, issue);
        }
        return ordered;
    }

    private void applyVerifierVerdict(String repo, String target, Map<String, Object> triage,
                                      Map<String, Object> analysis, ReviewIssue issue) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("repo", repo);
        payload.put("target", target);
        payload.put("candidate", issueMap(issue));
        payload.put("changed_file", changedFileForIssue(triage, issue));
        payload.put("risk_probes", probesForIssue(analysis, issue));
        payload.put("context_expansion", analysis.getOrDefault("context_expansion", Map.of()));
        payload.put("context_engine", analysis.getOrDefault("context_engine", Map.of()));
        payload.put("lsp_context", analysis.getOrDefault("lsp_context", Map.of()));
        payload.put("static_checks", analysis.getOrDefault("static_checks", List.of()));
        payload.put("instruction", """
                Verify whether the candidate is a real actionable review finding.
                Tool round budget: %d. Prefer candidate_evidence_bundle or lsp_evidence_bundle when the supplied evidence is ambiguous.
                Return exactly JSON: {"verdict":"KEEP|DROP|DEMOTE","confidence":0.0,"corrected_line":null,"reason":"short reason"}.
                KEEP means the issue is likely real and actionable. DROP means unsupported or speculative. DEMOTE means plausible but low priority.
                """.formatted(settings.verifierMaxToolRounds()));
        List<ChatMessage> messages = new ArrayList<>(List.of(
                new ChatMessage("system", "You are a code review candidate verifier. Be evidence-driven and concise."),
                new ChatMessage("user", Jsons.stringify(payload))
        ));
        trace.record("candidate_verifier_start", Map.of("candidate", issueMap(issue), "allowed_tools", VERIFIER_TOOLS));
        try {
            ChatMessage finalMessage = runBoundedToolLoop("CANDIDATE_VERIFIER", messages, VERIFIER_TOOLS, settings.verifierMaxToolRounds(), 0.0);
            if (finalMessage == null) {
                throw new IllegalStateException("Verifier stopped after tool budget");
            }
            Map<String, Object> raw = Jsons.parseMap(finalMessage.content);
            String verdict = String.valueOf(raw.getOrDefault("verdict", "KEEP")).toUpperCase();
            double confidence = doubleValue(raw.get("confidence"), issue.confidence);
            issue.validationVerdict = switch (verdict) {
                case "DROP", "DEMOTE", "KEEP" -> verdict;
                default -> "KEEP";
            };
            issue.validationReason = String.valueOf(raw.getOrDefault("reason", ""));
            if (raw.get("corrected_line") instanceof Number n) {
                issue.correctedLine = n.intValue();
                issue.line = issue.correctedLine;
            }
            if ("DROP".equals(issue.validationVerdict)) {
                issue.candidateScore = Math.min(issue.candidateScore, 0.0);
            } else if ("DEMOTE".equals(issue.validationVerdict)) {
                issue.candidateScore = Math.min(issue.candidateScore, Math.max(0.0, confidence * 0.55));
                issue.confidence = Math.min(issue.confidence, confidence);
            } else {
                issue.candidateScore = Math.min(1.0, Math.max(issue.candidateScore, confidence * 0.7));
                issue.confidence = Math.max(issue.confidence, confidence);
            }
            trace.record("candidate_verifier_result", Map.of(
                    "candidate", issueMap(issue),
                    "verdict", issue.validationVerdict,
                    "confidence", confidence,
                    "reason", issue.validationReason
            ));
        } catch (Exception e) {
            issue.validationVerdict = "UNVERIFIED";
            issue.validationReason = "Verifier failed; keeping candidate score unchanged: " + safeMessage(e);
            trace.record("warning", Map.of("phase", "CANDIDATE_VERIFIER", "error", safeMessage(e), "candidate", issueMap(issue)));
            trace.record("candidate_verifier_result", Map.of(
                    "candidate", issueMap(issue),
                    "verdict", issue.validationVerdict,
                    "reason", issue.validationReason
            ));
        }
    }

    private double publishThreshold(ReviewIssue issue) {
        double base = settings.reviewPublishThreshold();
        if ("security".equals(issue.category) || "bug".equals(issue.category) || "performance".equals(issue.category)) {
            return Math.min(base, 0.38);
        }
        if ("style".equals(issue.category) || "maintainability".equals(issue.category)) {
            return Math.max(base, 0.62);
        }
        return base;
    }

    @SuppressWarnings("unchecked")
    private boolean shouldRecoverZeroIssues(Map<String, Object> triage, Map<String, Object> analysis) {
        Object risk = analysis.get("risk_model");
        String level = "low";
        if (risk instanceof Map<?, ?> map && map.get("risk_level") != null) {
            level = String.valueOf(map.get("risk_level"));
        }
        List<Map<String, Object>> probes = listOfMaps(analysis.get("risk_probes"));
        return ("high".equalsIgnoreCase(level) || "medium".equalsIgnoreCase(level) || Boolean.TRUE.equals(triage.get("high_risk")))
                && !probes.isEmpty();
    }

    private List<Map<String, Object>> act(String repo, int pr, Map<String, Object> triage, Map<String, Object> analysis, ReviewResult reviewResult) {
        trace.record("phase_start", Map.of("phase", Phase.ACT.name()));
        List<Map<String, Object>> actions = new ArrayList<>();
        Map<String, Object> actPlan = LlmAdvisoryNodes.actPlan(settings, llm, trace, repo, "PR #" + pr, triage, analysis, reviewResult);
        analysis.put("act_plan", actPlan);
        if (Boolean.TRUE.equals(actPlan.get("enabled"))) {
            actions.add(Map.of("name", "act_plan", "result", actPlan));
        }
        if (reviewResult.shouldComment && !reviewResult.issues.isEmpty()) {
            List<Map<String, Object>> comments = reviewResult.issues.stream()
                    .filter(i -> i.file != null && i.line != null)
                    .map(i -> Map.<String, Object>of("path", i.file, "line", i.line, "body", commentBody(i)))
                    .toList();
            ToolResult result = call("submit_review_comments", Map.of("repo", repo, "pr", pr, "body", reviewResult.summary, "comments", comments));
            actions.add(Map.of("name", "submit_review_comments", "result", result));
        }
        if (reviewResult.shouldUpdateMemory) {
            String author = String.valueOf(triage.getOrDefault("author", "unknown"));
            List<Map<String, Object>> issueMaps = issueMaps(reviewResult.issues);
            ToolResult profile = call("memory_update_developer_profile", Map.of(
                    "author", author,
                    "issues", issueMaps,
                    "growth_areas", growthAreas(reviewResult),
                    "strengths", strengths(analysis, reviewResult)
            ));
            ToolResult patterns = call("memory_aggregate_patterns", Map.of("repo", repo, "issues", issueMaps));
            actions.add(Map.of("name", "memory_update_developer_profile", "result", profile));
            actions.add(Map.of("name", "memory_aggregate_patterns", "result", patterns));
            trace.record("memory_update", Map.of("profile", profile, "patterns", patterns));
        }
        if (reviewResult.shouldCreateFixPr || reviewResult.issues.stream().anyMatch(i -> i.autoFixable && i.fixCode != null && !i.fixCode.isBlank())) {
            actions.addAll(createAutoFixAndTestPr(repo, pr, triage, analysis, reviewResult));
        }
        trace.record("phase_end", Map.of("phase", Phase.ACT.name(), "actions", actions));
        return actions;
    }

    private List<Map<String, Object>> actOnCommitRange(String repo, Map<String, Object> triage, Map<String, Object> analysis, ReviewResult reviewResult) {
        trace.record("phase_start", Map.of("phase", Phase.ACT.name()));
        List<Map<String, Object>> actions = new ArrayList<>();
        Map<String, Object> actPlan = LlmAdvisoryNodes.actPlan(settings, llm, trace, repo, "commit_range", triage, analysis, reviewResult);
        analysis.put("act_plan", actPlan);
        if (Boolean.TRUE.equals(actPlan.get("enabled"))) {
            actions.add(Map.of("name", "act_plan", "result", actPlan));
        }
        if (reviewResult.shouldUpdateMemory) {
            String author = String.valueOf(triage.getOrDefault("author", "unknown"));
            List<Map<String, Object>> issueMaps = issueMaps(reviewResult.issues);
            ToolResult profile = call("memory_update_developer_profile", Map.of(
                    "author", author,
                    "issues", issueMaps,
                    "growth_areas", growthAreas(reviewResult),
                    "strengths", strengths(analysis, reviewResult)
            ));
            ToolResult patterns = call("memory_aggregate_patterns", Map.of("repo", repo, "issues", issueMaps));
            actions.add(Map.of("name", "memory_update_developer_profile", "result", profile));
            actions.add(Map.of("name", "memory_aggregate_patterns", "result", patterns));
            trace.record("memory_update", Map.of("profile", profile, "patterns", patterns));
        }
        trace.record("phase_end", Map.of("phase", Phase.ACT.name(), "actions", actions));
        return actions;
    }

    private List<ReviewIssue> progressiveRepoReview(String repo, RepoAuditIndexer.AuditIndex index, List<Map<String, Object>> manifest,
                                                    List<Map<String, Object>> coverage, List<Map<String, Object>> checks,
                                                    Map<String, Object> risk, Map<String, Object> lspContext,
                                                    Map<String, Object> sharedAnalysis) {
        trace.record("phase_start", Map.of("phase", Phase.PROGRESSIVE_REVIEW.name()));
        List<ReviewIssue> issues = new ArrayList<>();
        List<List<RepoAuditIndexer.AuditSlice>> batches = new RepoAuditIndexer(settings).batches(index.slices());
        int batchNo = 0;
        for (List<RepoAuditIndexer.AuditSlice> batch : batches) {
            batchNo++;
            trace.record("review_batch_start", Map.of("batch", batchNo, "slices", batch.stream().map(RepoAuditIndexer.AuditSlice::payload).toList()));
            try {
                ReviewResult result = reviewRepoBatch(repo, batchNo, batches.size(), manifest, batch, checks, risk,
                        batchLspContext(lspContext, batch), coverageSummary(index, coverage), sharedAnalysis);
                issues.addAll(result.issues);
                markReviewed(coverage, batch);
                trace.record("review_batch_end", Map.of("batch", batchNo, "issues", result.issues.size(), "coverage", coverageSummary(index, coverage)));
            } catch (Exception e) {
                markFailed(coverage, batch, safeMessage(e));
                trace.record("review_batch_end", Map.of("batch", batchNo, "error", safeMessage(e), "coverage", coverageSummary(index, coverage)));
            }
        }
        trace.record("coverage_summary", coverageSummary(index, coverage));
        trace.record("phase_end", Map.of("phase", Phase.PROGRESSIVE_REVIEW.name(), "issues", issues.size(), "coverage", coverageSummary(index, coverage)));
        return issues;
    }

    private ReviewResult reviewRepoBatch(String repo, int batchNo, int totalBatches, List<Map<String, Object>> manifest,
                                         List<RepoAuditIndexer.AuditSlice> batch, List<Map<String, Object>> checks,
                                         Map<String, Object> risk, Map<String, Object> lspContext, Map<String, Object> coverageSummary,
                                         Map<String, Object> sharedAnalysis) {
        Map<String, Object> reviewStrategy = sharedReviewStrategy(sharedAnalysis);
        LanguageSkillRouter.Selection languageSkills = new LanguageSkillRouter(settings, llm, trace)
                .selectForRepoBatch(batch, risk, lspContext, sharedAnalysis);
        Map<String, Object> contextEngine = ContextEngine.forRepoBatch(settings, batch, manifest, checks, risk, lspContext, sharedAnalysis, trace);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("repo", repo);
        payload.put("batch", batchNo);
        payload.put("total_batches", totalBatches);
        payload.put("manifest_summary", manifest);
        payload.put("coverage_summary", coverageSummary);
        payload.put("risk_model", risk);
        payload.put("static_checks", checks);
        payload.put("lsp_context", lspContext);
        payload.put("shared_analysis", sharedAnalysis);
        payload.put("review_strategy", reviewStrategy);
        payload.put("context_engine", contextEngine);
        payload.put("language_skill_selection", languageSkills.selectedSkills());
        payload.put("slices", batch.stream().map(RepoAuditIndexer.AuditSlice::payload).toList());
        payload.put("instruction", "Review this batch as part of full repository audit. Use context_engine.context_pack as the preferred concise context, then the current slices. This node has bounded ReAct access: tool round budget is " + settings.repoBatchMaxToolRounds() + ", and only the provided batch tools are available. Use tools to confirm cross-file, contract, LSP, static, supply-chain, or test-impact evidence; do not explore unrelated areas. Use the shared review strategy nodes exactly like diff CR: context expansion, risk model, regression/test reasoning, and evidence validation expectations. If a finding depends on context outside the slice, cite the context item id or tool result. Return valid JSON only.");
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(new ChatMessage("system", new SkillLoader().loadSkill("code-review-repo-audit", false)));
        if (languageSkills.hasPrompt()) {
            messages.add(new ChatMessage("system", "Selected language-specific code review skills for this repository audit batch:\n\n" + languageSkills.prompt()));
        }
        messages.add(new ChatMessage("user", Jsons.stringify(payload)));
        ChatMessage finalMessage = runBoundedToolLoop(Phase.PROGRESSIVE_REVIEW.name() + "_BATCH_" + batchNo, messages, REPO_BATCH_TOOLS, settings.repoBatchMaxToolRounds(), 0.1);
        if (finalMessage == null) {
            return ReviewResultParser.parse("{\"summary\":\"Repository audit batch stopped after tool budget.\",\"issues\":[],\"shouldComment\":false}");
        }
        return ReviewResultParser.parse(finalMessage.content);
    }

    private static List<Map<String, Object>> initialCoverage(RepoAuditIndexer.AuditIndex index) {
        List<Map<String, Object>> coverage = new ArrayList<>();
        for (RepoAuditIndexer.AuditSlice slice : index.slices()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("path", slice.path());
            item.put("start_line", slice.startLine());
            item.put("end_line", slice.endLine());
            item.put("status", "pending");
            coverage.add(item);
        }
        for (Map<String, Object> skipped : index.skipped()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("path", skipped.get("path"));
            item.put("status", "skipped");
            item.put("reason", skipped.get("reason"));
            coverage.add(item);
        }
        return coverage;
    }

    private static void markReviewed(List<Map<String, Object>> coverage, List<RepoAuditIndexer.AuditSlice> batch) {
        mark(coverage, batch, "reviewed", null);
    }

    private static void markFailed(List<Map<String, Object>> coverage, List<RepoAuditIndexer.AuditSlice> batch, String reason) {
        mark(coverage, batch, "failed", reason);
    }

    private static void mark(List<Map<String, Object>> coverage, List<RepoAuditIndexer.AuditSlice> batch, String status, String reason) {
        for (RepoAuditIndexer.AuditSlice slice : batch) {
            for (Map<String, Object> item : coverage) {
                if (Objects.equals(item.get("path"), slice.path())
                        && Objects.equals(item.get("start_line"), slice.startLine())
                        && Objects.equals(item.get("end_line"), slice.endLine())) {
                    item.put("status", status);
                    if (reason != null) {
                        item.put("reason", reason);
                    }
                }
            }
        }
    }

    private static Map<String, Object> coverageSummary(RepoAuditIndexer.AuditIndex index, List<Map<String, Object>> coverage) {
        Map<String, Long> counts = coverage.stream()
                .collect(Collectors.groupingBy(item -> String.valueOf(item.get("status")), LinkedHashMap::new, Collectors.counting()));
        Map<String, Long> skipReasons = coverage.stream()
                .filter(item -> "skipped".equals(item.get("status")))
                .collect(Collectors.groupingBy(item -> String.valueOf(item.getOrDefault("reason", "unknown")), LinkedHashMap::new, Collectors.counting()));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("files_total", index.files().size() + index.skipped().size());
        out.put("reviewable_files", index.files().size());
        out.put("slices_total", index.slices().size());
        out.put("status_counts", counts);
        out.put("skip_reasons", skipReasons);
        return out;
    }

    private Map<String, Object> repoLspContext(Path repoPath, RepoAuditIndexer.AuditIndex index) {
        trace.record("phase_start", Map.of("phase", Phase.LSP_CONTEXT.name()));
        Map<String, Object> context;
        try {
            context = new LspAnalyzer(settings).workspaceContext(repoPath, index);
        } catch (Exception e) {
            context = new LinkedHashMap<>();
            context.put("enabled", settings.lspEnabled());
            context.put("status", "failed");
            context.put("error", safeMessage(e));
        }
        trace.record("phase_end", Map.of("phase", Phase.LSP_CONTEXT.name(), "result", context));
        return context;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> batchLspContext(Map<String, Object> lspContext, List<RepoAuditIndexer.AuditSlice> batch) {
        Set<String> paths = batch.stream().map(RepoAuditIndexer.AuditSlice::path).collect(Collectors.toCollection(HashSet::new));
        Object preview = lspContext.get("symbols_preview");
        List<Map<String, Object>> symbols = preview instanceof List<?> list
                ? list.stream()
                .filter(Map.class::isInstance)
                .map(item -> (Map<String, Object>) item)
                .filter(item -> paths.contains(String.valueOf(item.get("path"))))
                .toList()
                : List.of();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", lspContext.getOrDefault("status", "unknown"));
        out.put("servers", lspContext.getOrDefault("servers", List.of()));
        out.put("symbols", symbols);
        out.put("diagnostics", lspContext.getOrDefault("diagnostics", Map.of()));
        out.put("errors", lspContext.getOrDefault("errors", List.of()));
        return out;
    }

    private static Map<String, Object> repoAuditRiskModel(RepoAuditIndexer.AuditIndex index, Map<String, Object> lspContext) {
        List<String> sensitive = index.files().stream().filter(RepoAuditIndexer.AuditFile::sensitive).map(RepoAuditIndexer.AuditFile::path).toList();
        List<String> configs = index.files().stream().filter(RepoAuditIndexer.AuditFile::config).map(RepoAuditIndexer.AuditFile::path).toList();
        Map<String, Object> risk = new LinkedHashMap<>();
        risk.put("stack", index.stack());
        risk.put("directories", index.directories());
        risk.put("sensitive_files", sensitive);
        risk.put("config_files", configs);
        risk.put("lsp_status", lspContext.getOrDefault("status", "unknown"));
        risk.put("lsp_symbol_count", lspContext.getOrDefault("symbol_count", 0));
        risk.put("lsp_errors", lspContext.getOrDefault("errors", List.of()));
        risk.put("risk_level", sensitive.isEmpty() ? "medium" : "high");
        return risk;
    }

    @SuppressWarnings("unchecked")
    private List<ReviewIssue> repoEvidenceValidation(List<ReviewIssue> input, RepoAuditIndexer.AuditIndex index, List<Map<String, Object>> checks,
                                                     Map<String, Object> lspContext, Map<String, Object> analysis) {
        return EvidenceValidationNode.validateRepo(input, index, checks, lspContext, analysis, trace);
    }

    private Path reportPhase(AgentRunResult result, Map<String, Object> target) {
        trace.record("phase_start", Map.of("phase", Phase.REPORT.name()));
        Map<String, Object> draft = new LinkedHashMap<>();
        try {
            String reportSkill = new SkillLoader().loadSkill("code-review-report", false);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("session_id", result.sessionId);
            payload.put("repo", result.repo);
            payload.put("target", target);
            payload.put("status", result.status);
            payload.put("dry_run", result.dryRun);
            payload.put("summary", result.summary);
            payload.put("issues", result.issues);
            payload.put("actions", result.actions);
            payload.put("trace_path", result.tracePath == null ? "" : result.tracePath.toString());
            payload.put("instruction", "Generate the final code review report draft according to the report skill output contract.");
            List<ChatMessage> messages = List.of(
                    new ChatMessage("system", reportSkill),
                    new ChatMessage("user", Jsons.stringify(payload))
            );
            trace.record("llm_request", Map.of(
                    "phase", Phase.REPORT.name(),
                    "iteration", 1,
                    "messages", messages,
                    "tools", List.of(),
                    "temperature", 0.1
            ));
            Map<String, Object> response = llm.chatJson(messages, List.of(), 0.1);
            trace.record("llm_response", Map.of("phase", Phase.REPORT.name(), "iteration", 1, "response", response));
            ChatMessage assistant = OpenAiCompatibleClient.assistantMessage(response);
            if (assistant.content != null && !assistant.content.isBlank()) {
                draft = Jsons.parseMap(assistant.content);
            }
        } catch (Exception e) {
            trace.record("warning", Map.of(
                    "phase", Phase.REPORT.name(),
                    "warning", "Report LLM node failed; writing fallback report",
                    "error", safeMessage(e)
            ));
            draft = fallbackReportDraft(result);
        }

        try {
            Path path = new ReportWriter(settings.reportDir()).write(result, target, draft);
            trace.record("report_written", Map.of("phase", Phase.REPORT.name(), "path", path.toString()));
            trace.record("phase_end", Map.of("phase", Phase.REPORT.name(), "path", path.toString()));
            return path;
        } catch (Exception e) {
            trace.record("warning", Map.of(
                    "phase", Phase.REPORT.name(),
                    "warning", "Unable to write report",
                    "error", safeMessage(e)
            ));
            trace.record("phase_end", Map.of("phase", Phase.REPORT.name(), "error", safeMessage(e)));
            return null;
        }
    }

    private static Map<String, Object> fallbackReportDraft(AgentRunResult result) {
        Map<String, Object> draft = new LinkedHashMap<>();
        draft.put("title", "Code Review Report: " + result.repo);
        draft.put("executive_summary", result.summary == null || result.summary.isBlank() ? "No summary was generated." : result.summary);
        draft.put("risk_assessment", result.issues.isEmpty()
                ? "No actionable issues were found in the final validated issue list."
                : "The final validated issue list contains " + result.issues.size() + " issue(s). Review severity and evidence before merging.");
        draft.put("test_assessment", "See the Issues section for any test-specific findings generated by the review node.");
        draft.put("key_findings", result.issues.stream().map(issue -> issue.severity + " " + issue.category + " in " + issue.file).toList());
        draft.put("actions_taken", result.actions.stream().map(action -> String.valueOf(action.getOrDefault("name", "action"))).toList());
        draft.put("recommendation", result.issues.isEmpty() ? "No blocking issues found." : "Address the listed findings or explicitly accept the residual risk.");
        return draft;
    }

    private ToolResult call(String name, Map<String, Object> args) {
        return router.call(new ToolCall(UUID.randomUUID().toString(), name, args));
    }

    private ChatMessage runBoundedToolLoop(String phase, List<ChatMessage> messages, Set<String> allowedTools,
                                           int maxToolRounds, double temperature) {
        List<Map<String, Object>> schemas = toolSchemasFor(allowedTools);
        int maxIterations = Math.max(0, maxToolRounds) + 1;
        for (int iteration = 1; iteration <= maxIterations; iteration++) {
            trace.record("llm_request", Map.of(
                    "phase", phase,
                    "iteration", iteration,
                    "max_tool_rounds", maxToolRounds,
                    "allowed_tools", allowedTools,
                    "messages", messages,
                    "tools", schemas,
                    "temperature", temperature
            ));
            Map<String, Object> response = llm.chatJson(messages, schemas, temperature);
            trace.record("llm_response", Map.of("phase", phase, "iteration", iteration, "response", response));
            ChatMessage assistant = OpenAiCompatibleClient.assistantMessage(response);
            messages.add(assistant);
            List<ToolCall> calls = OpenAiCompatibleClient.extractToolCalls(assistant);
            if (calls.isEmpty()) {
                return assistant;
            }
            if (iteration > Math.max(0, maxToolRounds)) {
                trace.record("max_iterations", Map.of(
                        "phase", phase,
                        "iterations", iteration,
                        "max_tool_rounds", maxToolRounds,
                        "reason", "Model requested tools after bounded tool budget was exhausted"
                ));
                return null;
            }
            for (ToolCall call : calls) {
                ToolResult result;
                if (allowedTools.contains(call.name())) {
                    result = router.call(call);
                } else {
                    trace.record("tool_call", Map.of(
                            "tool_call", Map.of("id", call.id(), "name", call.name(), "arguments", call.arguments()),
                            "phase", phase,
                            "policy", "disallowed"
                    ));
                    result = ToolResult.error(call.id(), call.name(), "Tool is not allowed in phase " + phase + ": " + call.name());
                    trace.record("tool_result", Map.of("tool_result", result, "phase", phase, "policy", "disallowed"));
                }
                messages.add(ChatMessage.tool(result.toolCallId, Jsons.stringify(result)));
            }
        }
        trace.record("max_iterations", Map.of("phase", phase, "iterations", maxIterations, "max_tool_rounds", maxToolRounds, "allowed_tools", allowedTools));
        return null;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> toolSchemasFor(Set<String> allowedTools) {
        return router.schemas().stream()
                .filter(schema -> {
                    Object fn = schema.get("function");
                    if (!(fn instanceof Map<?, ?> map)) {
                        return false;
                    }
                    return allowedTools.contains(String.valueOf(map.get("name")));
                })
                .map(schema -> (Map<String, Object>) schema)
                .toList();
    }

    private static List<Map<String, Object>> issueMaps(List<ReviewIssue> issues) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ReviewIssue issue : issues) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("severity", issue.severity == null ? "medium" : issue.severity.name());
            item.put("category", issue.category);
            item.put("file", issue.file);
            item.put("line", issue.line);
            item.put("body", issue.body);
            item.put("evidence", issue.evidence);
            item.put("impact", issue.impact);
            item.put("suggestion", issue.suggestion);
            item.put("auto_fixable", issue.autoFixable);
            item.put("fix_code", issue.fixCode);
            item.put("confidence", issue.confidence);
            item.put("candidate_score", issue.candidateScore);
            item.put("validation_verdict", issue.validationVerdict);
            item.put("validation_reason", issue.validationReason);
            item.put("corrected_line", issue.correctedLine);
            item.put("risk_probe_ids", issue.riskProbeIds);
            out.add(item);
        }
        return out;
    }

    private List<Map<String, Object>> createAutoFixAndTestPr(String repo, int pr, Map<String, Object> triage, Map<String, Object> analysis, ReviewResult reviewResult) {
        List<ReviewIssue> fixable = reviewResult.issues.stream()
                .filter(i -> i.autoFixable && i.fixCode != null && !i.fixCode.isBlank() && i.file != null)
                .toList();
        if (fixable.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> actions = new ArrayList<>();
        Map<String, Object> repoArgs = repoArgs(repo);
        String branch = "auto-fix/pr-" + pr + "-" + System.currentTimeMillis();
        String headRef = headRef(triage.get("pull_request"));
        ToolResult branchResult = call("create_branch", withExtra(repoArgs, Map.of("branch", branch, "from_branch", headRef.isBlank() ? "main" : headRef)));
        actions.add(Map.of("name", "create_branch", "result", branchResult));
        for (ReviewIssue issue : fixable) {
            Object fileInfo = call("get_file_contents", withExtra(repoArgs, Map.of("path", issue.file, "branch", branch))).result;
            Object sha = fileInfo instanceof Map<?, ?> map ? map.get("sha") : null;
            Map<String, Object> args = withExtra(repoArgs, Map.of(
                    "path", issue.file,
                    "content", issue.fixCode,
                    "message", "fix(" + issue.category + "): auto-fix review issue in " + issue.file,
                    "branch", branch
            ));
            if (sha != null) {
                args.put("sha", sha);
            }
            actions.add(Map.of("name", "create_or_update_file", "result", call("create_or_update_file", args)));
        }
        actions.addAll(createGeneratedTests(repoArgs, branch, pr, triage, analysis));
        Object base = triage.get("pull_request") instanceof Map<?, ?> pull && pull.get("head") instanceof Map<?, ?> head && head.get("ref") != null
                ? head.get("ref")
                : "main";
        ToolResult prResult = call("create_pull_request", withExtra(repoArgs, Map.of(
                "title", "[Auto Fix] PR #" + pr + ": code review fixes",
                "head", branch,
                "base", base,
                "body", "Automated fix PR generated from code review findings for PR #" + pr + "."
        )));
        actions.add(Map.of("name", "create_pull_request", "result", prResult));
        return actions;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> createGeneratedTests(Map<String, Object> repoArgs, String branch, int pr, Map<String, Object> triage, Map<String, Object> analysis) {
        Map<String, Object> regression = (Map<String, Object>) analysis.getOrDefault("regression_test_reasoning", Map.of());
        if (!Boolean.TRUE.equals(regression.get("likely_test_gap"))) {
            return List.of();
        }
        Map<String, Object> framework = (Map<String, Object>) analysis.getOrDefault("test_framework", Map.of());
        String primary = String.valueOf(framework.getOrDefault("primary_framework", "pytest"));
        ToolResult generated = call("generate_tests_for_changes", Map.of("changed_files", triage.getOrDefault("changed_files", List.of()), "framework", primary));
        List<Map<String, Object>> actions = new ArrayList<>();
        actions.add(Map.of("name", "generate_tests_for_changes", "result", generated));
        if (generated.result instanceof Map<?, ?> map && map.get("generated_tests") instanceof List<?> tests) {
            for (Object item : tests) {
                if (!(item instanceof Map<?, ?> test)) {
                    continue;
                }
                String path = String.valueOf(test.get("path"));
                String content = String.valueOf(test.get("content"));
                actions.add(Map.of("name", "create_or_update_file", "result", call("create_or_update_file", withExtra(repoArgs, Map.of(
                        "path", path,
                        "content", content,
                        "message", "test: add generated coverage for PR #" + pr,
                        "branch", branch
                )))));
            }
        }
        return actions;
    }

    private ReviewResult evidenceValidation(ReviewResult input, Map<String, Object> triage, Map<String, Object> analysis) {
        return EvidenceValidationNode.validateDiff(input, triage, analysis, trace);
    }

    private static String commentBody(ReviewIssue issue) {
        StringBuilder body = new StringBuilder(issue.body);
        if (issue.evidence != null && !issue.evidence.isBlank()) {
            body.append("\n\nEvidence: ").append(issue.evidence);
        }
        if (issue.impact != null && !issue.impact.isBlank()) {
            body.append("\n\nImpact: ").append(issue.impact);
        }
        if (issue.suggestion != null && !issue.suggestion.isBlank()) {
            body.append("\n\nSuggestion: ").append(issue.suggestion);
        }
        return body.toString();
    }

    private static List<String> growthAreas(ReviewResult reviewResult) {
        return reviewResult.issues.stream()
                .map(i -> i.category)
                .filter(Objects::nonNull)
                .distinct()
                .limit(5)
                .toList();
    }

    @SuppressWarnings("unchecked")
    private static List<String> strengths(Map<String, Object> analysis, ReviewResult reviewResult) {
        List<String> out = new ArrayList<>();
        Map<String, Object> regression = (Map<String, Object>) analysis.getOrDefault("regression_test_reasoning", Map.of());
        if (intValue(regression.get("related_test_count")) > 0) {
            out.add("related tests exist near changed files");
        }
        if (reviewResult.issues.isEmpty()) {
            out.add("no actionable review issues found");
        }
        return out;
    }

    private static Map<String, Set<Integer>> changedLinesByFile(Object changedFiles) {
        Map<String, Set<Integer>> result = new HashMap<>();
        for (Map<String, Object> file : listOfMaps(changedFiles)) {
            String filename = String.valueOf(file.get("filename"));
            String patch = String.valueOf(file.getOrDefault("patch", ""));
            result.put(filename, changedLinesFromPatch(patch));
        }
        return result;
    }

    private static Set<Integer> changedLinesFromPatch(String patch) {
        Set<Integer> lines = new HashSet<>();
        if (patch == null || patch.isBlank()) {
            return lines;
        }
        int currentNewLine = 0;
        boolean sawHunk = false;
        for (String line : patch.split("\\R")) {
            java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("@@ -\\d+(?:,\\d+)? \\+(\\d+)(?:,\\d+)? @@.*").matcher(line);
            if (matcher.matches()) {
                sawHunk = true;
                currentNewLine = Integer.parseInt(matcher.group(1));
                continue;
            }
            if (!sawHunk) {
                if (line.startsWith("+") && !line.startsWith("+++")) {
                    lines.add(1);
                }
                continue;
            }
            if (line.startsWith("+") && !line.startsWith("+++")) {
                lines.add(currentNewLine++);
            } else if (!line.startsWith("-")) {
                currentNewLine++;
            }
        }
        return lines;
    }

    private ReviewResult skippedResult(Map<String, Object> triage) {
        ReviewResult result = new ReviewResult();
        result.summary = String.valueOf(triage.getOrDefault("skip_reason", "Review skipped by triage policy."));
        result.shouldComment = Boolean.TRUE.equals(triage.get("human_required"));
        result.shouldUpdateMemory = false;
        return result;
    }

    private List<Map<String, Object>> actOnTriageDecision(String repo, int pr, Map<String, Object> triage, ReviewResult reviewResult) {
        trace.record("phase_start", Map.of("phase", Phase.ACT.name()));
        List<Map<String, Object>> actions = new ArrayList<>();
        if (reviewResult.shouldComment) {
            Map<String, Object> args = withExtra(repoArgs(repo), Map.of(
                    "issue_number", pr,
                    "body", reviewResult.summary + "\n\nFocus areas: " + triage.getOrDefault("focus_areas", List.of())
            ));
            ToolResult result = call("add_issue_comment", args);
            actions.add(Map.of("name", "add_issue_comment", "result", result));
            trace.record("action_taken", Map.of("name", "add_issue_comment", "result", result));
        }
        trace.record("phase_end", Map.of("phase", Phase.ACT.name(), "actions", actions));
        return actions;
    }

    private Map<String, Object> contextExpansion(Map<String, Object> repoArgs, String headRef, Map<String, Object> triage) {
        trace.record("strategy_start", Map.of("strategy", "Context Expansion"));
        Object dependencyManifests = call("get_dependency_manifests", withExtra(repoArgs, Map.of("branch", headRef))).result;
        Object sensitivePaths = call("scan_sensitive_paths", withExtra(repoArgs, Map.of("branch", headRef, "limit", 80))).result;
        List<Map<String, Object>> relatedTests = relatedTests(repoArgs, headRef, triage.get("changed_files"));
        List<Map<String, Object>> securityFileContents = securityFileContents(repoArgs, headRef, triage.get("changed_files"));
        List<Map<String, Object>> surroundingContexts = surroundingContexts(repoArgs, headRef, triage.get("changed_files"));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("dependency_manifests", dependencyManifests);
        result.put("sensitive_paths", sensitivePaths);
        result.put("related_tests", relatedTests);
        result.put("security_file_contents", securityFileContents);
        result.put("surrounding_contexts", surroundingContexts);
        trace.record("strategy_end", Map.of(
                "strategy", "Context Expansion",
                "related_tests_files", relatedTests.size(),
                "security_files_loaded", securityFileContents.size(),
                "surrounding_contexts", surroundingContexts.size()
        ));
        return result;
    }

    private Map<String, Object> diffLspContext(String repo, Map<String, Object> triage, Path explicitRepoPath) {
        trace.record("strategy_start", Map.of("strategy", "Diff LSP Context"));
        Map<String, Object> result = new RepoContextNode(settings, trace).lspOnlyForDiff(repo, triage, explicitRepoPath);
        trace.record("strategy_end", Map.of("strategy", "Diff LSP Context", "result", result));
        return result;
    }

    private Map<String, Object> diffRepoContext(String repo, Map<String, Object> triage, Path explicitRepoPath) {
        return new RepoContextNode(settings, trace).forDiff(repo, triage, explicitRepoPath);
    }

    private Map<String, Object> repoAuditSharedAnalysis(String repo, RepoAuditIndexer.AuditIndex index, List<Map<String, Object>> manifest,
                                                       List<Map<String, Object>> checks, Map<String, Object> lspContext) {
        trace.record("strategy_start", Map.of("strategy", "Shared Analysis Nodes", "mode", "repo_audit"));
        Map<String, Object> triage = ReviewAnalysisNodes.repoAuditSyntheticTriage(index);
        Map<String, Object> analysis = new LinkedHashMap<>();
        analysis.put("context_expansion", ReviewAnalysisNodes.repoAuditContextExpansion(index, manifest, lspContext));
        analysis.put("repo_manifest", manifest);
        analysis.put("lsp_context", lspContext);
        analysis.put("static_checks", checks);
        analysis.put("risk_model", riskModel(triage, analysis));
        analysis.put("regression_test_reasoning", regressionTestReasoning(triage, analysis));
        analysis.put("memory", call("memory_get_all", Map.of("repo", repo, "author", "repo_audit")).result);
        analysis.put("context_scout", LlmAdvisoryNodes.contextScout(settings, llm, trace, "repo_audit", triage, analysis));
        analysis.put("context_engine", ContextEngine.forRepoAudit(settings, index, manifest, checks,
                mapOf(analysis.get("risk_model")), lspContext, analysis, trace));
        trace.record("strategy_end", Map.of("strategy", "Shared Analysis Nodes", "result", analysis));
        return analysis;
    }

    private Map<String, Object> riskModel(Map<String, Object> triage, Map<String, Object> analysis) {
        Map<String, Object> base = ReviewAnalysisNodes.riskModel(triage, analysis, trace);
        Map<String, Object> refined = LlmAdvisoryNodes.riskRefinement(settings, llm, trace, triage, analysis, base);
        trace.record("risk_refinement", Map.of("base", base, "result", refined));
        return refined;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> regressionTestReasoning(Map<String, Object> triage, Map<String, Object> analysis) {
        Map<String, Object> base = ReviewAnalysisNodes.regressionTestReasoning(triage, analysis, trace);
        Map<String, Object> refined = LlmAdvisoryNodes.testGapReasoning(settings, llm, trace, triage, analysis, base);
        trace.record("test_gap_reasoning", Map.of("base", base, "result", refined));
        return refined;
    }

    private List<Map<String, Object>> securityFileContents(Map<String, Object> repoArgs, String headRef, Object changedFiles) {
        List<Map<String, Object>> contents = new ArrayList<>();
        for (Map<String, Object> file : listOfMaps(changedFiles)) {
            String filename = String.valueOf(file.get("filename"));
            if (!securityCoreFile(filename)) {
                continue;
            }
            ToolResult content = call("get_file_contents", withExtra(repoArgs, Map.of("path", filename, "branch", headRef)));
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("filename", filename);
            item.put("content", content.ok ? content.result : Map.of("error", content.error));
            contents.add(item);
            if (contents.size() >= 5) {
                break;
            }
        }
        return contents;
    }

    private List<Map<String, Object>> surroundingContexts(Map<String, Object> repoArgs, String headRef, Object changedFiles) {
        List<Map<String, Object>> contexts = new ArrayList<>();
        Map<String, Set<Integer>> changedLines = changedLinesByFile(changedFiles);
        for (Map<String, Object> file : listOfMaps(changedFiles)) {
            String filename = String.valueOf(file.get("filename"));
            if (docsOrConfigOnly(filename) || filename.toLowerCase().contains("lock")) {
                continue;
            }
            Integer line = changedLines.getOrDefault(filename, Set.of()).stream().findFirst().orElse(1);
            ToolResult context = call("get_surrounding_lines", withExtra(repoArgs, Map.of("path", filename, "branch", headRef, "line", line, "context", 6)));
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("filename", filename);
            item.put("context", context.ok ? context.result : Map.of("error", context.error));
            contexts.add(item);
            if (contexts.size() >= 6) {
                break;
            }
        }
        return contexts;
    }

    private List<Map<String, Object>> relatedTests(Map<String, Object> repoArgs, String headRef, Object changedFiles) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> file : listOfMaps(changedFiles)) {
            String filename = String.valueOf(file.get("filename"));
            if (docsOrConfigOnly(filename) || filename.toLowerCase().contains("test")) {
                continue;
            }
            ToolResult tests = call("find_related_tests", withExtra(repoArgs, Map.of("source_path", filename, "branch", headRef)));
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("filename", filename);
            item.put("tests", tests.ok ? tests.result : Map.of("error", tests.error));
            out.add(item);
            if (out.size() >= 8) {
                break;
            }
        }
        return out;
    }

    private static List<Map<String, Object>> recallRiskProbes(Map<String, Object> triage, Map<String, Object> analysis) {
        List<Map<String, Object>> probes = new ArrayList<>();
        int next = 1;
        for (Map<String, Object> file : listOfMaps(triage.get("changed_files"))) {
            String path = String.valueOf(file.get("filename"));
            String patch = String.valueOf(file.getOrDefault("patch", ""));
            String lowerPath = path.toLowerCase();
            int line = firstChangedLine(patch);
            if (containsAny(patch, "&&", "||", "isAdmin", "isOwner", "permission", "authorize", "allowed", "denied")) {
                probes.add(riskProbe(next++, "boolean_permission", path, line, excerpt(patch, "&&", "||", "permission", "admin", "owner"),
                        "Changed boolean or permission logic can invert access checks."));
            }
            if (containsAny(patch, "password", "token", "secret", "credential", "auth", "session")) {
                probes.add(riskProbe(next++, "auth_credential", path, line, excerpt(patch, "password", "token", "secret", "auth", "session"),
                        "Credential or auth-adjacent changes need leakage, bypass, and lifecycle checks."));
            }
            if (containsAny(patch, "tolowercase", "touppercase", "equalsignorecase", "email", "locale")) {
                probes.add(riskProbe(next++, "case_sensitivity", path, line, excerpt(patch, "email", "lower", "upper", "locale"),
                        "Changed normalization can create case-sensitive bypasses or mismatches."));
            }
            if (containsAny(patch, "createmany", "bulk", "insert", "unique", "distinct", "dedup", "duplicate", "set<")) {
                probes.add(riskProbe(next++, "dedup_idempotency", path, line, excerpt(patch, "createMany", "unique", "duplicate", "dedup"),
                        "Bulk writes or uniqueness changes need duplicate and idempotency checks."));
            }
            if (containsAny(patch, "substring", "slice", "indexof", "lastindexof", "charat", "length")) {
                probes.add(riskProbe(next++, "substring_index", path, line, excerpt(patch, "substring", "slice", "index", "length"),
                        "Index and substring changes are prone to off-by-one and bounds errors."));
            }
            if (containsAny(patch, "null", "undefined", "optional", "nullable", "nonnull", "requirenonnull", "?.", "!!")) {
                probes.add(riskProbe(next++, "null_optional", path, line, excerpt(patch, "null", "undefined", "optional", "requireNonNull"),
                        "Nullability changes can allow invalid state or move failures later."));
            }
            if (containsAny(patch, "catch", "throw", "runtimeexception", "error", "exception", "return null", "promise.all")) {
                probes.add(riskProbe(next++, "error_handling", path, line, excerpt(patch, "catch", "throw", "error", "exception"),
                        "Changed error handling can hide failures or report misleading states."));
            }
            if (containsAny(patch, "cache", "memo", "ttl", "invalidate", "sync", "mutex", "lock", "goroutine", "thread", "race")) {
                probes.add(riskProbe(next++, "cache_concurrency", path, line, excerpt(patch, "cache", "lock", "thread", "race"),
                        "Cache or concurrency changes need stale data and race checks."));
            }
            if (containsAny(patch, "dispatchqueue", "mainactor", "task {", "async let", "await", "weak self", "unowned self", "closure", "delegate")) {
                probes.add(riskProbe(next++, "swift_lifecycle_concurrency", path, line, excerpt(patch, "MainActor", "DispatchQueue", "weak self", "await"),
                        "Swift async/UI lifecycle changes need main-thread, cancellation, and retain-cycle checks."));
            }
            if (containsAny(patch, "userdefaults", "keychain", "secitem", "biometric", "token", "password", "credential")) {
                probes.add(riskProbe(next++, "mobile_secret_storage", path, line, excerpt(patch, "UserDefaults", "Keychain", "SecItem", "token"),
                        "Mobile credential changes need secure storage, accessibility, and leakage checks."));
            }
            if (containsAny(patch, "malloc", "calloc", "realloc", "free(", "delete ", "new ", "memcpy", "memmove", "strcpy", "sprintf", "snprintf", "reinterpret_cast", "static_cast", "const_cast")) {
                probes.add(riskProbe(next++, "native_memory_bounds", path, line, excerpt(patch, "malloc", "free", "memcpy", "strcpy", "reinterpret_cast"),
                        "C/C++/Objective-C memory changes need bounds, ownership, lifetime, and nullability checks."));
            }
            if (containsAny(patch, "autoreleasepool", "retain", "release", "dealloc", "weak", "strong", "assign", "copy", "unsafe_unretained")) {
                probes.add(riskProbe(next++, "objc_ownership_lifecycle", path, line, excerpt(patch, "retain", "release", "dealloc", "weak", "strong"),
                        "Objective-C ownership changes need retain-cycle, dangling reference, and deallocation checks."));
            }
            if (containsAny(patch, "params.require", "permit(", "skip_before_action", "before_action", "where(", "find_by_sql", "joins(", "includes(", "pluck(")) {
                probes.add(riskProbe(next++, "rails_request_data_access", path, line, excerpt(patch, "permit", "before_action", "where", "find_by_sql"),
                        "Rails request/data changes need authorization, mass-assignment, SQL safety, and N+1 checks."));
            }
            if (containsAny(patch, "send", "email", "notify", "notification", "webhook", "event", "emit")) {
                probes.add(riskProbe(next++, "notification_side_effect", path, line, excerpt(patch, "send", "email", "notify", "event"),
                        "Changed side-effect recipients can notify the wrong users or duplicate notifications."));
            }
            if (lowerPath.contains("test") || lowerPath.contains("spec")) {
                if (containsAny(patch, "tobetrue", "toequal", "assert", "expect", "skip", "only", "todo")) {
                    probes.add(riskProbe(next++, "test_assertion_weakening", path, line, excerpt(patch, "expect", "assert", "skip", "todo"),
                            "Test assertion changes can weaken or skip meaningful coverage."));
                }
            }
        }
        Object regression = analysis.get("regression_test_reasoning");
        if (regression instanceof Map<?, ?> map && Boolean.TRUE.equals(map.get("likely_test_gap"))) {
            for (String file : stringList(map.get("files_needing_test_consideration"))) {
                probes.add(riskProbe(next++, "missing_regression_test", file, 1, "", "Executable behavior changed without related test coverage."));
                if (probes.size() >= 40) {
                    break;
                }
            }
        }
        return probes.stream().limit(40).toList();
    }

    private static Map<String, Object> riskProbe(int id, String type, String file, int line, String evidence, String reason) {
        Map<String, Object> probe = new LinkedHashMap<>();
        probe.put("id", "probe-" + id);
        probe.put("type", type);
        probe.put("file", file);
        probe.put("line", line);
        probe.put("evidence", evidence);
        probe.put("reason", reason);
        return probe;
    }

    private static List<Map<String, Object>> topRiskProbes(Map<String, Object> analysis, int limit) {
        return listOfMaps(analysis.get("risk_probes")).stream().limit(limit).toList();
    }

    private static List<Map<String, Object>> probesForIssue(Map<String, Object> analysis, ReviewIssue issue) {
        return listOfMaps(analysis.get("risk_probes")).stream()
                .filter(probe -> Objects.equals(String.valueOf(probe.get("file")), issue.file))
                .limit(6)
                .toList();
    }

    private static Map<String, Object> changedFileForIssue(Map<String, Object> triage, ReviewIssue issue) {
        for (Map<String, Object> file : listOfMaps(triage.get("changed_files"))) {
            if (Objects.equals(String.valueOf(file.get("filename")), issue.file)) {
                return file;
            }
        }
        return Map.of();
    }

    private static Map<String, Object> issueMap(ReviewIssue issue) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("severity", issue.severity == null ? "medium" : issue.severity.name());
        item.put("category", issue.category);
        item.put("file", issue.file);
        item.put("line", issue.line);
        item.put("body", issue.body);
        item.put("evidence", issue.evidence);
        item.put("impact", issue.impact);
        item.put("suggestion", issue.suggestion);
        item.put("confidence", issue.confidence);
        item.put("candidate_score", issue.candidateScore);
        return item;
    }

    private static int firstChangedLine(String patch) {
        if (patch == null || patch.isBlank()) {
            return 1;
        }
        int currentNewLine = 1;
        boolean sawHunk = false;
        for (String line : patch.split("\\R")) {
            java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("@@ -\\d+(?:,\\d+)? \\+(\\d+)(?:,\\d+)? @@.*").matcher(line);
            if (matcher.matches()) {
                sawHunk = true;
                currentNewLine = Integer.parseInt(matcher.group(1));
                continue;
            }
            if (line.startsWith("+") && !line.startsWith("+++")) {
                return currentNewLine;
            }
            if (sawHunk && !line.startsWith("-")) {
                currentNewLine++;
            }
        }
        return 1;
    }

    private static String excerpt(String text, String... needles) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String[] lines = text.split("\\R");
        for (String needle : needles) {
            for (String line : lines) {
                if (line.toLowerCase().contains(needle.toLowerCase())) {
                    return truncated(line.strip(), 240);
                }
            }
        }
        return truncated(text.replaceAll("\\s+", " ").strip(), 240);
    }

    private static String truncated(String value, int maxChars) {
        if (value == null) {
            return "";
        }
        return value.length() <= maxChars ? value : value.substring(0, maxChars);
    }

    private static double doubleValue(Object value, double fallback) {
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (Exception e) {
            return fallback;
        }
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static List<String> stringList(Object value) {
        if (value instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        return List.of();
    }

    private static Map<String, Object> repoArgs(String repo) {
        String[] parts = repo.split("/", 2);
        if (parts.length == 2) {
            return Map.of("owner", parts[0], "repo", parts[1]);
        }
        return Map.of("repo", repo);
    }

    private static Map<String, Object> withPr(Map<String, Object> args, int pr) {
        return withExtra(args, Map.of("pr", pr, "pull_number", pr));
    }

    private static Map<String, Object> withExtra(Map<String, Object> base, Map<String, Object> extra) {
        Map<String, Object> merged = new LinkedHashMap<>(base);
        merged.putAll(extra);
        return merged;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> listOfMaps(Object value) {
        if (value instanceof List<?> list) {
            return list.stream()
                    .filter(Map.class::isInstance)
                    .map(item -> (Map<String, Object>) item)
                    .collect(Collectors.toList());
        }
        return List.of();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapOf(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private static int totalChangedLines(Object pull, List<Map<String, Object>> files) {
        int total = files.stream()
                .mapToInt(file -> intValue(file.get("additions")) + intValue(file.get("deletions")))
                .sum();
        if (total > 0) {
            return total;
        }
        if (pull instanceof Map<?, ?> map) {
            return intValue(map.get("additions")) + intValue(map.get("deletions"));
        }
        return 0;
    }

    private static String textFromPull(Object pull, String key) {
        if (pull instanceof Map<?, ?> map) {
            Object value = map.get(key);
            return value == null ? "" : String.valueOf(value);
        }
        return "";
    }

    private static boolean booleanFromPull(Object pull, String key) {
        if (pull instanceof Map<?, ?> map) {
            Object value = map.get(key);
            return value instanceof Boolean b ? b : Boolean.parseBoolean(String.valueOf(value));
        }
        return false;
    }

    private static String headRef(Object pull) {
        if (pull instanceof Map<?, ?> map && map.get("head") instanceof Map<?, ?> head) {
            Object ref = head.get("ref");
            if (ref != null) {
                return String.valueOf(ref);
            }
            Object sha = head.get("sha");
            if (sha != null) {
                return String.valueOf(sha);
            }
        }
        return "";
    }

    private static String headRevision(Object pull) {
        if (pull instanceof Map<?, ?> map && map.get("head") instanceof Map<?, ?> head) {
            Object sha = head.get("sha");
            if (sha != null) {
                return String.valueOf(sha);
            }
            Object ref = head.get("ref");
            if (ref != null) {
                return String.valueOf(ref);
            }
        }
        return "";
    }

    private static boolean docsOrConfigOnly(String filename) {
        String lower = filename.toLowerCase();
        return lower.endsWith(".md") || lower.endsWith(".txt") || lower.endsWith(".json")
                || lower.endsWith(".yaml") || lower.endsWith(".yml") || lower.endsWith(".toml")
                || lower.endsWith(".ini") || lower.endsWith(".cfg");
    }

    private static boolean securityCoreFile(String filename) {
        String lower = filename.toLowerCase();
        if (lower.contains("/test") || lower.contains("sample")) {
            return false;
        }
        return containsAny(lower, "auth", "security", "permission", "access_control", "secret", "credential", "password", "token");
    }

    private static boolean containsAny(String text, String... needles) {
        String lower = text == null ? "" : text.toLowerCase();
        for (String needle : needles) {
            if (lower.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static void addUnique(List<String> values, String value) {
        if (!values.contains(value)) {
            values.add(value);
        }
    }

    private String skipReason(boolean docsOnly, boolean draft, int changedLines, boolean breakingOrDesign, int securityFileCount) {
        if (draft) {
            return "Draft PR requires human follow-up before automated review.";
        }
        if (breakingOrDesign) {
            return "PR mentions breaking changes, RFC, or design/architecture work and requires human review.";
        }
        if (securityFileCount >= 3) {
            return "PR touches multiple security-sensitive files and requires human review.";
        }
        if (docsOnly) {
            return "Docs/config-only PR skipped by triage.";
        }
        return "";
    }

    private static List<String> focusAreas(List<Map<String, Object>> files, List<String> securityFiles, boolean highRisk) {
        List<String> areas = new ArrayList<>();
        if (!securityFiles.isEmpty()) {
            areas.add("security-sensitive changes: " + securityFiles);
        }
        if (files.stream().anyMatch(file -> String.valueOf(file.get("filename")).toLowerCase().contains("test"))) {
            areas.add("test changes");
        }
        if (highRisk) {
            areas.add("high-risk behavioral change");
        }
        return areas.isEmpty() ? List.of("general correctness") : areas;
    }

    private static String complexity(int changedLines, int fileCount, boolean highRisk) {
        if (highRisk || changedLines > 300 || fileCount > 12) {
            return "high";
        }
        if (changedLines > 100 || fileCount > 5) {
            return "medium";
        }
        return "low";
    }

    private static int intValue(Object value) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        if (value == null) {
            return 0;
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    @SuppressWarnings("unchecked")
    private String authorFromPull(Object pull) {
        if (pull instanceof Map<?, ?> map) {
            Object user = map.get("user");
            if (user instanceof Map<?, ?> userMap) {
                Object login = userMap.get("login");
                return login == null ? "unknown" : String.valueOf(login);
            }
            Object author = map.get("author");
            return author == null ? "unknown" : String.valueOf(author);
        }
        return "unknown";
    }

    @SuppressWarnings("unchecked")
    private static String commitAuthor(Object compare) {
        List<Map<String, Object>> commits = commitsFromCompare(compare);
        if (commits.isEmpty()) {
            return "unknown";
        }
        Object commit = commits.get(commits.size() - 1).get("commit");
        if (commit instanceof Map<?, ?> commitMap) {
            Object author = commitMap.get("author");
            if (author instanceof Map<?, ?> authorMap && authorMap.get("name") != null) {
                return String.valueOf(authorMap.get("name"));
            }
        }
        Object author = commits.get(commits.size() - 1).get("author");
        if (author instanceof Map<?, ?> authorMap && authorMap.get("login") != null) {
            return String.valueOf(authorMap.get("login"));
        }
        return "unknown";
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> commitsFromCompare(Object compare) {
        if (compare instanceof Map<?, ?> map && map.get("commits") instanceof List<?> commits) {
            return commits.stream()
                    .filter(Map.class::isInstance)
                    .map(item -> (Map<String, Object>) item)
                    .collect(Collectors.toList());
        }
        return List.of();
    }

    private static String stackTrace(Exception e) {
        StringWriter out = new StringWriter();
        e.printStackTrace(new PrintWriter(out));
        return out.toString();
    }

    private static String safeMessage(Exception e) {
        return e.getMessage() == null || e.getMessage().isBlank() ? e.getClass().getSimpleName() : e.getMessage();
    }
}
