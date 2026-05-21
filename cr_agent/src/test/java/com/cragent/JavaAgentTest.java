package com.cragent;

import com.cragent.agent.CodeReviewAgent;
import com.cragent.agent.ContextEngine;
import com.cragent.agent.EvidenceValidationNode;
import com.cragent.agent.LlmAdvisoryNodes;
import com.cragent.agent.ReportWriter;
import com.cragent.agent.LanguageSkillRouter;
import com.cragent.agent.RepoAuditIndexer;
import com.cragent.agent.ReviewResultParser;
import com.cragent.config.Settings;
import com.cragent.datasets.TraceDatasetExporter;
import com.cragent.llm.FakeLlmClient;
import com.cragent.llm.LlmClient;
import com.cragent.model.AgentRunResult;
import com.cragent.cli.LlmIntentRouter;
import com.cragent.model.ReviewIssue;
import com.cragent.model.ReviewResult;
import com.cragent.model.Severity;
import com.cragent.model.ToolCall;
import com.cragent.model.ToolResult;
import com.cragent.cli.ChatCommandParser;
import com.cragent.cli.PrIdentifier;
import com.cragent.skills.SkillLoader;
import com.cragent.skills.SkillDescriptor;
import com.cragent.tools.ToolRouter;
import com.cragent.tools.ToolSchemas;
import com.cragent.tools.ToolSpec;
import com.cragent.tools.AdvancedReviewTools;
import com.cragent.tools.TestGenerationTools;
import com.cragent.tools.MemoryTools;
import com.cragent.memory.MemoryStore;
import com.cragent.trace.TraceRecorder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class JavaAgentTest {
    @TempDir
    Path tmp;

    @Test
    void skillLoaderLoadsCombinedSkills() {
        String prompt = SkillLoader.defaultPrompt();
        assertTrue(prompt.length() > 5000);
        assertTrue(prompt.contains("code-review-test-gen"));
        assertTrue(prompt.contains("Java Code Review Runtime Contract"));
        assertTrue(prompt.contains("Do not prefix names with `mcp__github__`"));
        assertTrue(prompt.contains("Code Review Checklist"));
        assertTrue(prompt.length() < 40000);
    }

    @Test
    void parserReadsJson() {
        ReviewResult result = ReviewResultParser.parse("""
                {"summary":"ok","issues":[{"severity":"high","category":"security","file":"a.py","line":3,"body":"bad","evidence":"+bad()","impact":"breaks auth","confidence":0.9}],"shouldComment":true}
                """);
        assertEquals("ok", result.summary);
        assertEquals(1, result.issues.size());
        assertEquals("a.py", result.issues.getFirst().file);
        assertEquals("+bad()", result.issues.getFirst().evidence);
        assertEquals("breaks auth", result.issues.getFirst().impact);
    }

    @Test
    void parserAcceptsUppercaseSeverityFromLegacySkill() {
        ReviewResult result = ReviewResultParser.parse("""
                {"summary":"ok","issues":[{"severity":"HIGH","category":"security","file":"a.py","line":3,"body":"bad","confidence":0.9}]}
                """);
        assertEquals("high", result.issues.getFirst().severity.name());
    }

    @Test
    void parserAcceptsFencedJsonWithNestedCodeFenceInString() {
        ReviewResult result = ReviewResultParser.parse("""
                ```json
                {
                  "summary": "ok",
                  "issues": [
                    {
                      "severity": "medium",
                      "category": "maintainability",
                      "file": "a.py",
                      "line": 3,
                      "body": "bad",
                      "suggestion": "try this:\\n```python\\nprint(1)\\n```",
                      "confidence": 0.7
                    }
                  ],
                  "shouldComment": true
                }
                ```
                """);
        assertEquals("ok", result.summary);
        assertEquals(1, result.issues.size());
        assertTrue(result.issues.getFirst().suggestion.contains("print(1)"));
    }

    @Test
    void parserRecoversMalformedJsonWithUnescapedQuotes() {
        ReviewResult result = ReviewResultParser.parse("""
                ```json
                {
                  "summary": "front-end review",
                  "issues": [
                    {
                      "severity": "low",
                      "category": "maintainability",
                      "file": "packages/next/src/build/webpack/loaders/next-instrumentation-client-loader.ts",
                      "line": 41,
                      "body": "Resolution failures need a clearer error.",
                      "evidence": "Promise.all has no catch.",
                      "impact": "Users may see unclear build errors.",
                      "suggestion": "Wrap the error with `Failed to resolve instrumentationClientInject specifier "${spec}": ${err.message}`.",
                      "autoFixable": false,
                      "fixCode": null,
                      "confidence": 0.65
                    }
                  ],
                  "shouldComment": true,
                  "shouldCreateFixPr": false,
                  "shouldUpdateMemory": true
                }
                ```
                """);
        assertEquals("front-end review", result.summary);
        assertEquals(1, result.issues.size());
        assertEquals("maintainability", result.issues.getFirst().category);
        assertTrue(result.issues.getFirst().suggestion.contains("${spec}"));
        assertTrue(result.shouldComment);
    }

    @Test
    void parserFallsBackForMarkdown() {
        ReviewResult result = ReviewResultParser.parse("""
                **File:** `src/example.py`
                ### HIGH — Security
                **Line:** 1
                Password is read from request args.
                **Confidence** 0.92
                """);
        assertEquals(1, result.issues.size());
        assertEquals("src/example.py", result.issues.getFirst().file);
        assertEquals(1, result.issues.getFirst().line);
    }

    @Test
    void routerInterceptsDryRunWrites() {
        TraceRecorder trace = new TraceRecorder(tmp.resolve("traces"));
        ToolRouter router = new ToolRouter(true, trace, 1000);
        router.register(new ToolSpec("write_tool", "write", ToolSchemas.object(Map.of(), List.of()), args -> Map.of("ok", true), true));
        ToolResult result = router.call(new ToolCall("1", "write_tool", Map.of()));
        assertTrue(result.ok);
        assertTrue((Boolean) ((Map<?, ?>) result.result).get("dry_run"));
    }

    @Test
    void advancedReviewToolsRegisterSecurityHistoryContractAndTestHelpers() {
        TraceRecorder trace = new TraceRecorder(tmp.resolve("advanced-traces"));
        ToolRouter router = new ToolRouter(true, trace, 12000);
        Settings settings = new Settings(
                "https://token-plan-cn.xiaomimimo.com/v1",
                "",
                "mimo-v2.5-pro",
                "",
                true,
                tmp.resolve("traces"),
                tmp.resolve("memory"),
                tmp.resolve("report"),
                30,
                12000, true, true, 30);
        new AdvancedReviewTools(settings).register(router);

        assertTrue(router.hasTool("run_semgrep_scan"));
        assertTrue(router.hasTool("run_dependency_vulnerability_scan"));
        assertTrue(router.hasTool("candidate_evidence_bundle"));
        assertTrue(router.hasTool("git_churn_hotspots"));
        assertTrue(router.hasTool("detect_public_api_changes"));
        assertTrue(router.hasTool("select_impacted_tests"));
        assertTrue(router.hasTool("github_actions_permission_audit"));
        assertTrue(router.hasTool("sbom_generate"));
    }

    @Test
    void advancedReviewToolsAuditWorkflowAndApiDiffs() throws Exception {
        Path repo = tmp.resolve("advanced-repo");
        Files.createDirectories(repo.resolve(".github/workflows"));
        Files.writeString(repo.resolve(".github/workflows/ci.yml"), """
                name: ci
                on: pull_request_target
                permissions: write-all
                jobs:
                  test:
                    runs-on: ubuntu-latest
                    steps:
                      - uses: actions/checkout@v4
                      - run: echo "$SECRET_TOKEN"
                """, StandardCharsets.UTF_8);

        ToolRouter router = new ToolRouter(true, new TraceRecorder(tmp.resolve("advanced-run-traces")), 12000);
        Settings settings = new Settings(
                "https://token-plan-cn.xiaomimimo.com/v1",
                "",
                "mimo-v2.5-pro",
                "",
                true,
                tmp.resolve("traces"),
                tmp.resolve("memory"),
                tmp.resolve("report"),
                30,
                12000, true, true, 30);
        new AdvancedReviewTools(settings).register(router);

        ToolResult workflow = router.call(new ToolCall("1", "github_actions_permission_audit", Map.of("repo_path", repo.toString())));
        assertTrue(workflow.ok);
        assertTrue(workflow.result.toString().contains("pull_request_target"));
        assertTrue(workflow.result.toString().contains("broad_permissions"));

        ToolResult api = router.call(new ToolCall("2", "detect_public_api_changes", Map.of(
                "changed_files", List.of(Map.of(
                        "filename", "src/main/java/com/acme/UserApi.java",
                        "patch", """
                                @@
                                +public String displayName() {
                                +  return name;
                                +}
                                """
                ))
        )));
        assertTrue(api.ok);
        assertTrue(api.result.toString().contains("public_api_surface"));
    }

    @Test
    void contextEngineBuildsDiffPackIndexAndLedger() {
        Settings settings = new Settings(
                "https://token-plan-cn.xiaomimimo.com/v1",
                "",
                "mimo-v2.5-pro",
                "",
                true,
                tmp.resolve("traces"),
                tmp.resolve("memory"),
                tmp.resolve("report"),
                30,
                12000, true, true, 30);
        Map<String, Object> triage = Map.of(
                "changed_files", List.of(Map.of(
                        "filename", "src/AuthService.java",
                        "additions", 2,
                        "deletions", 0,
                        "patch", """
                                @@ -10,0 +11,2 @@
                                +public boolean canDelete(User user) {
                                +  return user.isAdmin() || user.isOwner();
                                """
                ))
        );
        Map<String, Object> analysis = new java.util.LinkedHashMap<>();
        analysis.put("risk_model", Map.of("risk_types", List.of("security/auth"), "risk_level", "high"));
        analysis.put("risk_probes", List.of(Map.of(
                "id", 1,
                "type", "boolean_permission",
                "file", "src/AuthService.java",
                "line", 11,
                "evidence", "+  return user.isAdmin() || user.isOwner();",
                "rationale", "Changed permission logic."
        )));
        analysis.put("context_expansion", Map.of(
                "surrounding_contexts", List.of(Map.of("filename", "src/AuthService.java", "context", "9: class AuthService")),
                "related_tests", List.of(Map.of("filename", "src/AuthService.java", "tests", Map.of("count", 1, "items", List.of("AuthServiceTest.java"))))
        ));
        analysis.put("lsp_context", Map.of("symbols_preview", List.of(Map.of("path", "src/AuthService.java", "name", "canDelete", "line", 11))));

        Map<String, Object> context = ContextEngine.forDiff(settings, triage, analysis, new TraceRecorder(tmp.resolve("ctx-traces")));
        assertEquals("diff", context.get("mode"));
        assertTrue(context.toString().contains("changed_hunk"));
        assertTrue(context.toString().contains("ctx-"));
        Map<?, ?> ledger = (Map<?, ?>) context.get("context_ledger");
        assertTrue(((Number) ledger.get("selected_items")).intValue() > 0);
        assertEquals(60, ((Number) ledger.get("rrf_k")).intValue());
        assertTrue(ledger.toString().contains("lexical_diff_terms"));
        assertTrue(ledger.toString().contains("risk_probe"));
        List<?> items = (List<?>) ((Map<?, ?>) context.get("context_pack")).get("items");
        assertTrue(items.stream().anyMatch(item -> item instanceof Map<?, ?> map
                && map.containsKey("rrf_score")
                && map.containsKey("retrieval_channels")
                && map.containsKey("channel_ranks")
                && map.containsKey("why_selected")));
        assertTrue(context.toString().contains("AuthService.java"));
    }

    @Test
    void contextEngineBuildsRepoAuditAndBatchContext() throws Exception {
        Path repo = tmp.resolve("ctx-repo");
        Files.createDirectories(repo.resolve("src/main/java/com/acme"));
        Files.createDirectories(repo.resolve("src/test/java/com/acme"));
        Files.writeString(repo.resolve("src/main/java/com/acme/AuthService.java"), """
                package com.acme;
                public class AuthService {
                  public boolean canDelete(User user) { return user.isAdmin(); }
                }
                """, StandardCharsets.UTF_8);
        Files.writeString(repo.resolve("src/test/java/com/acme/AuthServiceTest.java"), """
                package com.acme;
                class AuthServiceTest {}
                """, StandardCharsets.UTF_8);
        Files.writeString(repo.resolve("pom.xml"), "<project></project>", StandardCharsets.UTF_8);

        Settings settings = new Settings(
                "https://token-plan-cn.xiaomimimo.com/v1",
                "",
                "mimo-v2.5-pro",
                "",
                true,
                tmp.resolve("traces"),
                tmp.resolve("memory"),
                tmp.resolve("report"),
                30,
                12000, true, true, 30);
        RepoAuditIndexer.AuditIndex index = new RepoAuditIndexer(settings).index(repo);
        List<Map<String, Object>> manifest = index.files().stream().map(RepoAuditIndexer.AuditFile::manifest).toList();
        Map<String, Object> lsp = Map.of("status", "available", "symbols_preview", List.of(Map.of("path", "src/main/java/com/acme/AuthService.java", "name", "AuthService", "line", 2)));
        Map<String, Object> risk = Map.of("risk_level", "high", "stack", index.stack());
        TraceRecorder trace = new TraceRecorder(tmp.resolve("ctx-repo-traces"));

        Map<String, Object> repoContext = ContextEngine.forRepoAudit(settings, index, manifest, List.of(), risk, lsp, Map.of(), trace);
        assertEquals("repo_audit", repoContext.get("mode"));
        assertTrue(repoContext.toString().contains("repo_overview"));
        assertTrue(repoContext.toString().contains("repo_rules_and_configs"));

        Map<String, Object> batchContext = ContextEngine.forRepoBatch(settings, index.slices().stream().limit(1).toList(),
                manifest, List.of(), risk, lsp, Map.of(), trace);
        assertEquals("repo_batch", batchContext.get("mode"));
        assertTrue(batchContext.toString().contains("batch_slice"));
        assertTrue(batchContext.toString().contains("context_ledger"));
        List<?> batchItems = (List<?>) ((Map<?, ?>) batchContext.get("context_pack")).get("items");
        assertTrue(batchItems.stream().anyMatch(item -> item instanceof Map<?, ?> map
                && "batch_slice".equals(map.get("type"))
                && "selected_mandatory".equals(map.get("pack_decision"))
                && String.valueOf(map.get("retrieval_channels")).contains("mandatory_batch_slice")));
    }

    @Test
    void llmContextScoutFallsBackForMalformedOutput() {
        Settings settings = new Settings(
                "https://token-plan-cn.xiaomimimo.com/v1",
                "",
                "mimo-v2.5-pro",
                "",
                true,
                tmp.resolve("traces"),
                tmp.resolve("memory"),
                tmp.resolve("report"),
                30,
                12000, true, true, 30);
        LlmClient malformed = (messages, tools, temperature) -> Map.of("choices", List.of(Map.of("message", Map.of(
                "role", "assistant",
                "content", "not-json"
        ))));
        Map<String, Object> triage = Map.of("changed_files", List.of(Map.of(
                "filename", "src/AuthService.java",
                "patch", "+ return user.isAdmin() || user.isOwner();"
        )));
        Map<String, Object> analysis = Map.of("risk_model", Map.of("review_focus", List.of("permission logic")));
        Map<String, Object> scout = LlmAdvisoryNodes.contextScout(settings, malformed, new TraceRecorder(tmp.resolve("scout-traces")), "diff", triage, analysis);
        assertEquals("diff", scout.get("mode"));
        assertTrue(scout.toString().contains("authservice"));
        assertTrue(scout.toString().contains("permission"));
    }

    @Test
    void githubContextToolsRequireLiveCredentials() {
        assertFalse(new com.cragent.tools.GitHubTools("").available());
    }

    @Test
    void rawSourceQueriesStayWithinSupportedNonBenchmarkLanguages() throws Exception {
        Path queries = Path.of("..", "datasets", "raw", "source_queries.jsonl");
        Path denylist = Path.of("..", "datasets", "raw", "denylist.json");
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.exists(queries) && Files.exists(denylist),
                "raw dataset manifests are generated runtime inputs and may be absent in a clean checkout");

        Set<String> supported = Set.of("typescript", "javascript", "python", "java", "kotlin", "go", "rust", "ruby",
                "c", "cpp", "csharp", "php", "swift", "objective-c");
        Set<String> benchmarkRepos = Set.of("grafana/grafana", "getsentry/sentry", "calcom/cal.com",
                "keycloak/keycloak", "discourse/discourse");
        int languages = 0;
        int repoAuditEligible = 0;
        for (String line : Files.readAllLines(queries, StandardCharsets.UTF_8)) {
            if (line.isBlank()) {
                continue;
            }
            Map<String, Object> row = com.cragent.util.Jsons.parseMap(line);
            String language = String.valueOf(row.get("language"));
            String repo = String.valueOf(row.get("repo"));
            List<?> modes = (List<?>) row.get("allowed_modes");
            assertTrue(supported.contains(language), "unsupported language in raw source query: " + language);
            assertFalse(benchmarkRepos.contains(repo.toLowerCase()), "benchmark repo leaked into raw sources: " + repo);
            assertFalse(repo.toLowerCase().startsWith("ai-code-review-evaluation/"));
            assertNotNull(modes);
            assertFalse(modes.isEmpty());
            if (!"diff_only".equals(row.get("audit_tier"))) {
                assertTrue(modes.contains("both") || modes.contains("repo_audit"));
                repoAuditEligible++;
            }
            languages++;
        }
        assertTrue(languages >= 60);
        assertTrue(repoAuditEligible >= 20);
    }

    @Test
    void memorySchemaMatchesPythonStyleRecords() {
        MemoryTools memory = new MemoryTools(new MemoryStore(tmp.resolve("memory-schema")));
        Map<?, ?> all = (Map<?, ?>) memory.memoryGetAll(Map.of("repo", "owner/repo", "author", "alice"));
        List<?> rules = (List<?>) all.get("rules");
        assertFalse(rules.isEmpty());
        assertTrue(((Map<?, ?>) rules.getFirst()).containsKey("content"));

        memory.memoryUpdateDeveloperProfile(Map.of(
                "author", "alice",
                "new_issues", List.of(Map.of("category", "security", "severity", "high")),
                "strengths", List.of("clear tests"),
                "growth_areas", List.of("credential handling")
        ));
        Map<?, ?> profileResult = (Map<?, ?>) memory.memoryGetDeveloperProfile(Map.of("author", "alice"));
        Map<?, ?> profile = (Map<?, ?>) profileResult.get("profile");
        Map<?, ?> content = (Map<?, ?>) profile.get("content");
        assertEquals(1, content.get("pr_count"));
        assertTrue(content.toString().contains("issue_history"));
        assertTrue(content.toString().contains("credential handling"));

        memory.memoryAggregatePatterns(Map.of(
                "repo", "owner/repo",
                "issues", List.of(Map.of("category", "security", "severity", "high", "file", "src/Auth.java", "body", "bad"))
        ));
        Map<?, ?> report = (Map<?, ?>) memory.memoryHealthReport(Map.of("repo", "owner/repo"));
        assertEquals(1, report.get("total_occurrences"));
        assertTrue(report.toString().contains("trend"));
    }

    @Test
    void memoryReadCanBeDisabledForEvaluation() {
        MemoryStore store = new MemoryStore(tmp.resolve("memory-disabled"));
        MemoryTools writer = new MemoryTools(store);
        writer.memoryAddFalsePositive(Map.of("pattern", "known fp", "reason", "test"));

        MemoryTools disabled = new MemoryTools(store, false);
        Map<?, ?> all = (Map<?, ?>) disabled.memoryGetAll(Map.of("repo", "owner/repo"));
        assertEquals(true, all.get("disabled"));
        assertTrue(((List<?>) all.get("rules")).isEmpty());

        Map<?, ?> profile = (Map<?, ?>) disabled.memoryGetDeveloperProfile(Map.of("author", "alice"));
        assertEquals(true, profile.get("disabled"));
    }

    @Test
    void fullAgentDryRunWithFakeLlm() throws Exception {
        Settings settings = new Settings(
                "https://token-plan-cn.xiaomimimo.com/v1",
                "",
                "mimo-v2.5-pro",
                "",
                true,
                tmp.resolve("traces"),
                tmp.resolve("memory"),
                tmp.resolve("report"),
                30,
                12000, true, true, 30);
        AgentRunResult result = testAgent(settings, new FakeLlmClient()).review("owner/repo", 1);
        assertEquals("completed", result.status);
        assertFalse(result.issues.isEmpty());
        assertTrue(result.tracePath.toFile().exists());
        assertNotNull(result.reportPath);
        assertTrue(result.reportPath.toFile().exists());
        assertTrue(Files.readString(result.reportPath).contains("Code Review Report"));
    }

    @Test
    void reportWriterFormatsContextAndEvidenceAsMarkdownBlocks() throws Exception {
        AgentRunResult result = new AgentRunResult();
        result.sessionId = "session-1";
        result.repo = "owner/repo";
        result.status = "completed";
        result.summary = "done";
        result.tracePath = tmp.resolve("trace.jsonl");

        ReviewIssue issue = new ReviewIssue();
        issue.severity = Severity.high;
        issue.category = "security";
        issue.file = "src/Auth.go";
        issue.line = 42;
        issue.body = "Token is logged.";
        issue.evidence = "log.Printf(\"token=%s\", token)";
        issue.impact = "Secrets can leak into logs.";
        issue.suggestion = "Remove token logging.";
        issue.confidence = 0.9;
        result.issues = List.of(issue);

        Path report = new ReportWriter(tmp.resolve("report")).write(result, Map.of(
                "target", "repo_audit",
                "coverage_summary", Map.of(
                        "files_total", 2,
                        "reviewable_files", 1,
                        "slices_total", 1,
                        "status_counts", Map.of("reviewed", 1),
                        "skip_reasons", Map.of("binary", 1)
                ),
                "static_checks", List.of(Map.of("command", "go test ./...", "status", "passed", "exit_code", 0, "output", "ok ./...")),
                "lsp_context", Map.of("enabled", true, "status", "partial", "errors", List.of(Map.of("language", "go", "status", "missing")))
        ), Map.of("title", "Code Review Report: owner/repo", "executive_summary", "done"));

        String markdown = Files.readString(report, StandardCharsets.UTF_8);
        assertTrue(markdown.contains("## Context Summary"));
        assertTrue(markdown.contains("| Files total | `2` |"));
        assertTrue(markdown.contains("```go"));
        assertTrue(markdown.contains("log.Printf"));
        assertTrue(markdown.contains("<details>"));
        assertTrue(markdown.contains("```json"));
        assertFalse(markdown.contains("coverage_summary: `{"));
    }

    @Test
    void reviewQualityGateFiltersWeakFindings() {
        ReviewResult input = ReviewResultParser.parse("""
                {
                  "summary": "quality gate test",
                  "issues": [
                    {"severity":"low","category":"style","file":"src/example.py","line":99,"body":"weak uncertain style note","confidence":0.3},
                    {"severity":"high","category":"logic","file":"src/example.py","line":99,"body":"real issue but wrong inline line","evidence":"+password = request.args.get('password')","impact":"credential leakage","confidence":0.9},
                    {"severity":"high","category":"security","file":"src/not_changed.py","line":1,"body":"not in diff","confidence":0.9}
                  ],
                  "shouldComment": true
                }
                """);
        ReviewResult result = EvidenceValidationNode.validateDiff(input, fixtureTriage(), Map.of(), new TraceRecorder(tmp.resolve("quality-traces")));
        assertEquals(1, result.issues.size());
        assertEquals("bug", result.issues.getFirst().category);
        assertEquals(1, result.issues.getFirst().line);
        assertTrue(result.issues.getFirst().candidateScore >= 0.38);
    }

    @Test
    void evidenceValidationScoresButDoesNotDropHunkOnlyFindings() {
        TraceRecorder trace = new TraceRecorder(tmp.resolve("evidence-traces"));
        ReviewResult input = new ReviewResult();
        ReviewIssue hallucinated = new ReviewIssue();
        hallucinated.severity = Severity.high;
        hallucinated.category = "bug";
        hallucinated.file = "src/example.py";
        hallucinated.line = 3;
        hallucinated.body = "This changed code may break authentication.";
        hallucinated.impact = "Users may be unable to sign in.";
        hallucinated.confidence = 0.95;
        input.issues = List.of(hallucinated);
        input.shouldComment = true;

        ReviewResult result = EvidenceValidationNode.validateDiff(input, fixtureTriage(), Map.of(), trace);
        assertEquals(1, result.issues.size());
        assertNull(result.issues.getFirst().line);
        assertTrue(result.issues.getFirst().candidateScore > 0.0);
    }

    @Test
    void evidenceValidationRepairsLineFromExactEvidence() {
        TraceRecorder trace = new TraceRecorder(tmp.resolve("line-repair-traces"));
        ReviewResult input = new ReviewResult();
        ReviewIssue issue = new ReviewIssue();
        issue.severity = Severity.high;
        issue.category = "logic";
        issue.file = "src/example.py";
        issue.line = 99;
        issue.body = "Password is read from the URL query string.";
        issue.evidence = "+password = request.args.get('password')";
        issue.impact = "Credentials can leak through logs and browser history.";
        issue.confidence = 0.9;
        input.issues = List.of(issue);
        input.shouldComment = true;

        ReviewResult result = EvidenceValidationNode.validateDiff(input, fixtureTriage(), Map.of(), trace);
        assertEquals(1, result.issues.size());
        assertEquals(1, result.issues.getFirst().line);
        assertEquals("bug", result.issues.getFirst().category);
    }

    @Test
    void evidenceValidationDeduplicatesSemanticDuplicates() {
        TraceRecorder trace = new TraceRecorder(tmp.resolve("dedupe-traces"));
        ReviewIssue first = new ReviewIssue();
        first.severity = Severity.high;
        first.category = "security";
        first.file = "src/example.py";
        first.line = 1;
        first.body = "Password is read from query parameters.";
        first.evidence = "+password = request.args.get('password')";
        first.impact = "Credentials can leak through logs and browser history.";
        first.confidence = 0.9;

        ReviewIssue second = new ReviewIssue();
        second.severity = Severity.medium;
        second.category = "security";
        second.file = "src/example.py";
        second.line = 1;
        second.body = "Password is read from query params and may be logged.";
        second.evidence = "+password = request.args.get('password')";
        second.impact = "Credentials can leak through request logs.";
        second.confidence = 0.85;

        ReviewResult input = new ReviewResult();
        input.issues = List.of(first, second);
        input.shouldComment = true;

        ReviewResult result = EvidenceValidationNode.validateDiff(input, fixtureTriage(), Map.of(), trace);
        assertEquals(1, result.issues.size());
    }

    @Test
    void falsePositiveMemoryFiltersFindings() {
        Settings settings = new Settings(
                "https://token-plan-cn.xiaomimimo.com/v1",
                "",
                "mimo-v2.5-pro",
                "",
                true,
                tmp.resolve("fp-traces"),
                tmp.resolve("fp-memory"),
                tmp.resolve("fp-report"),
                30,
                12000, true, true, 30);
        settings = settings.withVerifierEnabled(false);
        MemoryTools memory = new MemoryTools(new MemoryStore(settings.memoryDir()));
        memory.memoryGetAll(Map.of());
        memory.memoryAddFalsePositive(Map.of(
                "pattern", "fixture-only",
                "reason", "fixture-like test value",
                "file_patterns", List.of("src/example.py")
        ));
        AgentRunResult result = testAgent(settings, (messages, tools, temperature) -> Map.of("choices", List.of(Map.of("message", Map.of(
                "role", "assistant",
                "content", """
                        {
                          "summary": "fp",
                          "issues": [
                            {"severity":"high","category":"security","file":"src/example.py","line":1,"body":"test 文件中的硬编码值 fixture-only","evidence":"+value = 'fixture-only'","impact":"none","confidence":0.9},
                            {"severity":"high","category":"security","file":"src/example.py","line":1,"body":"unvalidated password is passed into the login flow","evidence":"+password = request.args.get('password')","impact":"request-controlled credentials can bypass validation","suggestion":"validate the credential source before use","confidence":0.9}
                          ],
                          "shouldComment": true
                        }
                        """
        ))))).review("owner/repo", 1);
        assertEquals(1, result.issues.size());
        assertFalse(result.issues.getFirst().body.contains("fixture-only"));
    }

    @Test
    void falsePositiveMemoryDoesNotClearEveryHighSignalFinding() {
        Settings settings = new Settings(
                "https://token-plan-cn.xiaomimimo.com/v1",
                "",
                "mimo-v2.5-pro",
                "",
                true,
                tmp.resolve("fp-restore-traces"),
                tmp.resolve("fp-restore-memory"),
                tmp.resolve("fp-restore-report"),
                30,
                12000, true, true, 30);
        settings = settings.withVerifierEnabled(false);
        MemoryTools memory = new MemoryTools(new MemoryStore(settings.memoryDir()));
        memory.memoryGetAll(Map.of());
        memory.memoryAddFalsePositive(Map.of(
                "pattern", "fixture-only",
                "reason", "fixture-like test value",
                "file_patterns", List.of("src/example.py")
        ));
        AgentRunResult result = testAgent(settings, (messages, tools, temperature) -> Map.of("choices", List.of(Map.of("message", Map.of(
                "role", "assistant",
                "content", """
                        {
                          "summary": "fp",
                          "issues": [
                            {"severity":"high","category":"security","file":"src/example.py","line":1,"body":"test 文件中的硬编码值 fixture-only","evidence":"+value = 'fixture-only'","impact":"none","confidence":0.9}
                          ],
                          "shouldComment": true
                        }
                        """
        ))))).review("owner/repo", 1);
        assertEquals(1, result.issues.size());
        assertEquals("DEMOTE", result.issues.getFirst().validationVerdict);
    }

    @Test
    void autoFixCreatesDryRunActions() {
        Settings settings = new Settings(
                "https://token-plan-cn.xiaomimimo.com/v1",
                "",
                "mimo-v2.5-pro",
                "",
                true,
                tmp.resolve("fix-traces"),
                tmp.resolve("fix-memory"),
                tmp.resolve("fix-report"),
                30,
                12000, true, true, 30);
        AgentRunResult result = testAgent(settings, (messages, tools, temperature) -> Map.of("choices", List.of(Map.of("message", Map.of(
                "role", "assistant",
                "content", """
                        {
                          "summary": "fix",
                          "issues": [
                            {"severity":"high","category":"security","file":"src/example.py","line":1,"body":"fixable","evidence":"+password = request.args.get('password')","impact":"credential leak","suggestion":"use form","autoFixable":true,"fixCode":"password = request.form.get('password')\\n","confidence":0.9}
                          ],
                          "shouldComment": true,
                          "shouldCreateFixPr": true
                        }
                        """
        ))))).review("owner/repo", 1);
        assertFalse(result.actions.isEmpty());
        assertTrue(result.actions.toString().contains("create_branch"));
        assertTrue(result.actions.toString().contains("create_pull_request"));
    }

    @Test
    void verifierDropsUnsupportedCandidateAndKeepsStrongFinding() {
        Settings settings = new Settings(
                "https://token-plan-cn.xiaomimimo.com/v1",
                "",
                "mimo-v2.5-pro",
                "",
                true,
                tmp.resolve("verifier-traces"),
                tmp.resolve("verifier-memory"),
                tmp.resolve("verifier-report"),
                30,
                12000, true, true, 30);
        AgentRunResult result = testAgent(settings, (messages, tools, temperature) -> {
            String last = messages.get(messages.size() - 1).content == null ? "" : messages.get(messages.size() - 1).content;
            if (last.contains("Verify whether the candidate")) {
                String verdict = last.contains("unsupported issue") ? "DROP" : "KEEP";
                return assistantJson("{\"verdict\":\"" + verdict + "\",\"confidence\":0.95,\"corrected_line\":null,\"reason\":\"fixture verifier\"}");
            }
            if (last.contains("Generate the final code review report")) {
                return assistantJson("{\"title\":\"Code Review Report: owner/repo\",\"executive_summary\":\"done\"}");
            }
            return assistantJson("""
                    {
                      "summary": "verifier test",
                      "issues": [
                        {"severity":"high","category":"bug","file":"src/example.py","line":1,"body":"Password is read from query parameters.","evidence":"+password = request.args.get('password')","impact":"Credentials can leak through URLs and logs.","confidence":0.9},
                        {"severity":"high","category":"bug","file":"src/example.py","line":1,"body":"unsupported issue","impact":"This is speculative.","confidence":0.9}
                      ],
                      "shouldComment": true
                    }
                    """);
        }).review("owner/repo", 1);
        assertEquals(1, result.issues.size());
        assertTrue(result.issues.getFirst().body.contains("Password"));
        assertNotEquals("DROP", result.issues.getFirst().validationVerdict);
    }

    @Test
    void zeroIssueRecoveryRunsWhenRiskProbesExist() {
        Settings settings = new Settings(
                "https://token-plan-cn.xiaomimimo.com/v1",
                "",
                "mimo-v2.5-pro",
                "",
                true,
                tmp.resolve("recovery-traces"),
                tmp.resolve("recovery-memory"),
                tmp.resolve("recovery-report"),
                30,
                12000, true, true, 30).withVerifierEnabled(false);
        AgentRunResult result = testAgent(settings, (messages, tools, temperature) -> {
            String last = messages.get(messages.size() - 1).content == null ? "" : messages.get(messages.size() - 1).content;
            if (last.contains("targeted recovery pass")) {
                return assistantJson("""
                        {
                          "summary": "recovered",
                          "issues": [
                            {"severity":"high","category":"bug","file":"src/example.py","line":1,"body":"Password is read from query parameters.","evidence":"+password = request.args.get('password')","impact":"Credentials can leak through URLs and logs.","confidence":0.9}
                          ],
                          "shouldComment": true
                        }
                        """);
            }
            if (last.contains("Generate the final code review report")) {
                return assistantJson("{\"title\":\"Code Review Report: owner/repo\",\"executive_summary\":\"done\"}");
            }
            return assistantJson("{\"summary\":\"none\",\"issues\":[],\"shouldComment\":false}");
        }).review("owner/repo", 1);
        assertEquals(1, result.issues.size());
        assertTrue(result.summary.contains("Recovery pass"));
    }

    @Test
    void liveMemoryActionsAcceptReviewIssues() {
        Settings settings = new Settings(
                "https://token-plan-cn.xiaomimimo.com/v1",
                "",
                "mimo-v2.5-pro",
                "",
                false,
                tmp.resolve("live-memory-traces"),
                tmp.resolve("live-memory"),
                tmp.resolve("live-report"),
                30,
                12000, true, true, 30);
        AgentRunResult result = testAgent(settings, (messages, tools, temperature) -> Map.of("choices", List.of(Map.of("message", Map.of(
                "role", "assistant",
                "content", """
                        {
                          "summary": "memory",
                          "issues": [
                            {"severity":"medium","category":"bug","file":"src/example.py","line":1,"body":"real issue","evidence":"+password = request.args.get('password')","impact":"bad","confidence":0.9}
                          ],
                          "shouldComment": false,
                          "shouldUpdateMemory": true
                        }
                        """
        ))))).review("owner/repo", 1);
        assertEquals("completed", result.status);
        assertTrue(result.actions.stream().anyMatch(action -> "memory_update_developer_profile".equals(action.get("name"))));
        assertFalse(result.actions.toString().contains("cannot be cast"));
    }


    @Test
    void fullCommitRangeDryRunWithFakeLlm() {
        Settings settings = new Settings(
                "https://token-plan-cn.xiaomimimo.com/v1",
                "",
                "mimo-v2.5-pro",
                "",
                true,
                tmp.resolve("commit-traces"),
                tmp.resolve("commit-memory"),
                tmp.resolve("commit-report"),
                30,
                12000, true, true, 30);
        AgentRunResult result = testAgent(settings, new FakeLlmClient()).reviewCommits("owner/repo", "base-sha", "head-sha");
        assertEquals("completed", result.status);
        assertTrue(result.tracePath.toFile().exists());
        assertNotNull(result.reportPath);
        assertTrue(result.reportPath.toFile().exists());
    }


    @Test
    void prIdentifierParsesUrlAndShortForm() {
        PrIdentifier fromUrl = PrIdentifier.parse("https://github.com/openai/example/pull/42");
        assertEquals("openai/example", fromUrl.fullRepo());
        assertEquals(42, fromUrl.pr());

        PrIdentifier shortForm = PrIdentifier.parse("owner/repo #7");
        assertEquals("owner/repo", shortForm.fullRepo());
        assertEquals(7, shortForm.pr());
    }

    @Test
    void chatParserUnderstandsNaturalPrAndCommitRequests() {
        ChatCommandParser.ChatIntent prUrl = ChatCommandParser.parse("帮我 review https://github.com/vercel/next.js/pull/93785");
        assertEquals(ChatCommandParser.Type.PR, prUrl.type());
        assertEquals("vercel/next.js", prUrl.repo());
        assertEquals(93785, prUrl.pr());

        ChatCommandParser.ChatIntent prText = ChatCommandParser.parse("用 dry-run 看一下 spring-projects/spring-boot PR 50454");
        assertEquals(ChatCommandParser.Type.PR, prText.type());
        assertEquals("spring-projects/spring-boot", prText.repo());
        assertEquals(50454, prText.pr());
        assertTrue(prText.dryRunOverride());

        ChatCommandParser.ChatIntent commits = ChatCommandParser.parse("live review psf/requests 从 6e83187b 到 0224b0ec");
        assertEquals(ChatCommandParser.Type.COMMITS, commits.type());
        assertEquals("psf/requests", commits.repo());
        assertEquals("6e83187b", commits.base());
        assertEquals("0224b0ec", commits.head());
        assertFalse(commits.dryRunOverride());

        ChatCommandParser.ChatIntent compare = ChatCommandParser.parse("review https://github.com/owner/repo/compare/main...feature-branch");
        assertEquals(ChatCommandParser.Type.COMMITS, compare.type());
        assertEquals("owner/repo", compare.repo());
        assertEquals("main", compare.base());
        assertEquals("feature-branch", compare.head());

        ChatCommandParser.ChatIntent repoOnly = ChatCommandParser.parse("帮我 review https://github.com/GongShichen/JTravelAgent.git");
        assertEquals(ChatCommandParser.Type.REPO, repoOnly.type());
        assertEquals("GongShichen/JTravelAgent", repoOnly.repo());
    }

    @Test
    void llmIntentRouterClassifiesRepoAuditAndCommitDiff() {
        LlmIntentRouter auditRouter = new LlmIntentRouter((messages, tools, temperature) -> Map.of("choices", List.of(Map.of("message", Map.of(
                "role", "assistant",
                "content", "{\"type\":\"REPO_AUDIT\",\"repo\":\"owner/repo\",\"pr\":null,\"base\":null,\"head\":null,\"dry_run\":null,\"confidence\":0.96,\"reason\":\"whole repo\"}"
        )))));
        ChatCommandParser.ChatIntent audit = auditRouter.route("对整个 owner/repo 做 CR");
        assertEquals(ChatCommandParser.Type.REPO_AUDIT, audit.type());
        assertEquals("owner/repo", audit.repo());

        LlmIntentRouter commitRouter = new LlmIntentRouter((messages, tools, temperature) -> Map.of("choices", List.of(Map.of("message", Map.of(
                "role", "assistant",
                "content", "{\"type\":\"COMMITS\",\"repo\":\"owner/repo\",\"pr\":null,\"base\":\"abc1234\",\"head\":\"def5678\",\"dry_run\":false,\"confidence\":0.95,\"reason\":\"two refs\"}"
        )))));
        ChatCommandParser.ChatIntent commits = commitRouter.route("review owner/repo 从 abc1234 到 def5678");
        assertEquals(ChatCommandParser.Type.COMMITS, commits.type());
        assertEquals("abc1234", commits.base());
        assertEquals("def5678", commits.head());
        assertFalse(commits.dryRunOverride());
    }

    @Test
    void llmIntentRouterFallsBackToRules() {
        LlmIntentRouter router = new LlmIntentRouter((messages, tools, temperature) -> Map.of("choices", List.of(Map.of("message", Map.of(
                "role", "assistant",
                "content", "not json"
        )))));
        ChatCommandParser.ChatIntent intent = router.route("review https://github.com/owner/repo/compare/main...feature");
        assertEquals(ChatCommandParser.Type.COMMITS, intent.type());
        assertEquals("main", intent.base());
        assertEquals("feature", intent.head());
    }

    @Test
    void repoAuditIndexerBuildsFullCoverageWithoutSampling() throws Exception {
        Path repo = tmp.resolve("fixture-repo");
        Files.createDirectories(repo.resolve("src"));
        Files.createDirectories(repo.resolve("app/models"));
        Files.createDirectories(repo.resolve("node_modules/pkg"));
        Files.writeString(repo.resolve("src/Auth.java"), "class Auth { String token; }\n", StandardCharsets.UTF_8);
        Files.writeString(repo.resolve("src/Session.swift"), "final class Session { let token: String }\n", StandardCharsets.UTF_8);
        Files.writeString(repo.resolve("src/native.cpp"), "int add(int a, int b) { return a + b; }\n", StandardCharsets.UTF_8);
        Files.writeString(repo.resolve("src/AuthManager.m"), "@implementation AuthManager\n@end\n", StandardCharsets.UTF_8);
        Files.writeString(repo.resolve("app/models/user.rb"), "class User; end\n", StandardCharsets.UTF_8);
        Files.writeString(repo.resolve("README.md"), "# docs\n", StandardCharsets.UTF_8);
        Files.writeString(repo.resolve("node_modules/pkg/index.js"), "ignored\n", StandardCharsets.UTF_8);
        Settings settings = new Settings("url", "", "model", "", true, tmp.resolve("traces"), tmp.resolve("memory"), tmp.resolve("report"),
                30, 12000, false, true, 30);

        RepoAuditIndexer.AuditIndex index = new RepoAuditIndexer(settings).index(repo);
        assertEquals(6, index.files().size());
        assertTrue(index.files().stream().anyMatch(file -> file.path().equals("src/Auth.java")));
        assertTrue(index.files().stream().anyMatch(file -> file.path().equals("src/Session.swift") && file.language().equals("swift")));
        assertTrue(index.files().stream().anyMatch(file -> file.path().equals("src/native.cpp") && file.language().equals("cpp")));
        assertTrue(index.files().stream().anyMatch(file -> file.path().equals("src/AuthManager.m") && file.language().equals("objective-c")));
        assertTrue(index.files().stream().anyMatch(file -> file.path().equals("app/models/user.rb") && file.language().equals("ruby")));
        assertTrue(index.skipped().stream().anyMatch(item -> "vendor_cache".equals(item.get("reason"))));
        assertFalse(index.slices().isEmpty());
        List<List<RepoAuditIndexer.AuditSlice>> batches = new RepoAuditIndexer(settings).batches(index.slices());
        long covered = batches.stream().flatMap(List::stream).map(RepoAuditIndexer.AuditSlice::path).distinct().count();
        assertEquals(6, covered);
    }

    @Test
    void repoAuditIndexerDoesNotApplyFileSizeOrSliceLimits() throws Exception {
        Path repo = tmp.resolve("large-repo");
        Files.createDirectories(repo.resolve("src"));
        String largeBody = "class Large {\n" + "  String value = \"x\";\n".repeat(60_000) + "}\n";
        Files.writeString(repo.resolve("src/Large.java"), largeBody, StandardCharsets.UTF_8);
        Settings settings = new Settings("url", "", "model", "", true, tmp.resolve("traces"), tmp.resolve("memory"), tmp.resolve("report"),
                30, 12000, false, true, 30);

        RepoAuditIndexer.AuditIndex index = new RepoAuditIndexer(settings).index(repo);
        assertTrue(index.files().stream().anyMatch(file -> file.path().equals("src/Large.java")));
        assertFalse(index.skipped().stream().anyMatch(item -> "too_large".equals(item.get("reason"))));
        assertEquals(1, index.slices().stream().filter(slice -> slice.path().equals("src/Large.java")).count());
        assertTrue(index.slices().stream().anyMatch(slice -> slice.path().equals("src/Large.java")
                && slice.content().length() == largeBody.length()));
    }

    @Test
    void settingsLoadExplicitEnvFile() throws Exception {
        Path env = tmp.resolve(".env");
        Files.writeString(env, "OPENAI_BASE_URL=https://example.test/v1\nOPENAI_API_KEY=secret\nOPENAI_MODEL=model-x\nCR_AGENT_LLM_THINKING_MODE=disabled\nCR_AGENT_LLM_TIMEOUT_SECONDS=123\nCR_AGENT_DRY_RUN=false\nCR_AGENT_REPORT_DIR=custom-report\nCR_AGENT_MEMORY_READ_ENABLED=false\nCR_AGENT_REPO_AUDIT_RUN_CHECKS=false\nCR_AGENT_LSP_ENABLED=false\nCR_AGENT_LSP_TIMEOUT_SECONDS=7\nCR_AGENT_VERIFIER_ENABLED=false\nCR_AGENT_VERIFIER_MAX_CANDIDATES=3\nCR_AGENT_REVIEW_MAX_COMMENTS=4\nCR_AGENT_REVIEW_PUBLISH_THRESHOLD=0.51\nCR_AGENT_ZERO_ISSUE_RECOVERY=false\nCR_AGENT_LANGUAGE_SKILLS_ENABLED=false\nCR_AGENT_LANGUAGE_SKILL_MAX_SELECTED=2\nCR_AGENT_RECOVERY_MAX_TOOL_ROUNDS=9\nCR_AGENT_VERIFIER_MAX_TOOL_ROUNDS=5\nCR_AGENT_REPO_BATCH_MAX_TOOL_ROUNDS=11\nCR_AGENT_LLM_TRIAGE_ADVICE=false\nCR_AGENT_LLM_CONTEXT_SCOUT=false\nCR_AGENT_LLM_RISK_REFINEMENT=false\nCR_AGENT_LLM_TEST_REASONING=false\nCR_AGENT_LLM_ACT_PLANNING=true\nCR_AGENT_CONTEXT_RRF_K=77\nCR_AGENT_CONTEXT_MAX_ITEMS=12\n", StandardCharsets.UTF_8);
        Settings settings = Settings.load(env);
        assertEquals("https://example.test/v1", settings.openaiBaseUrl());
        assertEquals("secret", settings.openaiApiKey());
        assertEquals("model-x", settings.openaiModel());
        assertEquals("disabled", settings.llmThinkingMode());
        assertEquals(123, settings.llmTimeoutSeconds());
        assertTrue(settings.reportDir().endsWith("custom-report"));
        assertFalse(settings.memoryReadEnabled());
        assertFalse(settings.repoAuditRunChecks());
        assertFalse(settings.lspEnabled());
        assertEquals(7, settings.lspTimeoutSeconds());
        assertFalse(settings.verifierEnabled());
        assertEquals(3, settings.verifierMaxCandidates());
        assertEquals(4, settings.reviewMaxComments());
        assertEquals(0.51, settings.reviewPublishThreshold(), 0.0001);
        assertFalse(settings.zeroIssueRecovery());
        assertFalse(settings.languageSkillsEnabled());
        assertEquals(2, settings.languageSkillMaxSelected());
        assertEquals(9, settings.recoveryMaxToolRounds());
        assertEquals(5, settings.verifierMaxToolRounds());
        assertEquals(11, settings.repoBatchMaxToolRounds());
        assertFalse(settings.llmTriageAdvice());
        assertFalse(settings.llmContextScout());
        assertFalse(settings.llmRiskRefinement());
        assertFalse(settings.llmTestReasoning());
        assertTrue(settings.llmActPlanning());
        assertEquals(77, settings.contextRrfK());
        assertEquals(12, settings.contextMaxItems());
        assertTrue(settings.hasLlmCredentials());
        assertFalse(settings.dryRun());
    }

    @Test
    void infersCommonBackendAndFrontendTestPaths() {
        TestGenerationTools tools = new TestGenerationTools();
        assertEquals("src/test/java/com/acme/UserServiceTest.java",
                ((Map<?, ?>) tools.inferTestPath(Map.of("source_path", "src/main/java/com/acme/UserService.java", "framework", "junit5"))).get("test_path"));
        assertEquals("src/lib/auth_test.rs",
                ((Map<?, ?>) tools.inferTestPath(Map.of("source_path", "src/lib/auth.rs", "framework", "cargo-test"))).get("test_path"));
        assertEquals("src/components/Button.test.tsx",
                ((Map<?, ?>) tools.inferTestPath(Map.of("source_path", "src/components/Button.tsx", "framework", "react-testing-library"))).get("test_path"));
        assertEquals("e2e/login.spec.ts",
                ((Map<?, ?>) tools.inferTestPath(Map.of("source_path", "src/pages/login.tsx", "framework", "playwright"))).get("test_path"));
        assertEquals("src/test/kotlin/com/acme/UserServiceTest.kt",
                ((Map<?, ?>) tools.inferTestPath(Map.of("source_path", "src/main/kotlin/com/acme/UserService.kt", "framework", "kotest"))).get("test_path"));
        assertEquals("tests/Controller/UserControllerTest.php",
                ((Map<?, ?>) tools.inferTestPath(Map.of("source_path", "app/Controller/UserController.php", "framework", "phpunit"))).get("test_path"));
        assertEquals("spec/models/user_spec.rb",
                ((Map<?, ?>) tools.inferTestPath(Map.of("source_path", "app/models/user.rb", "framework", "rspec"))).get("test_path"));
        assertEquals("tests/UserServiceTests.cs",
                ((Map<?, ?>) tools.inferTestPath(Map.of("source_path", "src/UserService.cs", "framework", "xunit"))).get("test_path"));
        assertEquals("Tests/UserServiceTests.swift",
                ((Map<?, ?>) tools.inferTestPath(Map.of("source_path", "Sources/App/UserService.swift", "framework", "xctest"))).get("test_path"));
        assertEquals("tests/parser_test.cpp",
                ((Map<?, ?>) tools.inferTestPath(Map.of("source_path", "src/parser.cpp", "framework", "googletest"))).get("test_path"));
        assertEquals("Tests/AuthManagerTests.m",
                ((Map<?, ?>) tools.inferTestPath(Map.of("source_path", "Sources/AuthManager.m", "framework", "ocmock"))).get("test_path"));
        assertEquals("Tests/AuthManagerTests.m",
                ((Map<?, ?>) tools.inferTestPath(Map.of("source_path", "Sources/AuthManager.m", "framework", "xctest"))).get("test_path"));
    }

    @Test
    void lspPreflightSupportsMobileNativeAndRubyServers() {
        List<String> languages = com.cragent.agent.LspAnalyzer.supportedServers().stream()
                .map(com.cragent.agent.LspAnalyzer.ServerSpec::language)
                .toList();
        assertTrue(languages.contains("kotlin"));
        assertTrue(languages.contains("swift"));
        assertTrue(languages.contains("cpp"));
        assertTrue(languages.contains("ruby"));
        assertTrue(languages.contains("csharp"));
        assertTrue(languages.contains("php"));
        assertTrue(languages.contains("yaml"));
        assertTrue(languages.contains("json"));
        assertTrue(languages.contains("dockerfile"));
    }

    @Test
    void lspToolsExposeCodeReviewEvidenceTools() {
        TraceRecorder trace = new TraceRecorder(tmp.resolve("traces"));
        ToolRouter router = new ToolRouter(true, trace, 12000);
        Settings settings = new Settings("url", "", "model", "", true, tmp.resolve("traces"), tmp.resolve("memory"), tmp.resolve("report"),
                30, 12000, true, true, 30);
        new com.cragent.tools.LspTools(settings).register(router);
        List<String> names = router.schemas().stream().map(schema -> String.valueOf(((Map<?, ?>) schema.get("function")).get("name"))).toList();
        assertTrue(names.contains("lsp_capabilities"));
        assertTrue(names.contains("lsp_symbol_at_position"));
        assertTrue(names.contains("lsp_changed_symbols"));
        assertTrue(names.contains("lsp_call_graph"));
        assertTrue(names.contains("lsp_related_tests_by_symbol"));
        assertTrue(names.contains("lsp_evidence_bundle"));
    }

    @Test
    void languageSkillCatalogParsesDescriptors() {
        SkillLoader loader = new SkillLoader();
        List<SkillDescriptor> catalog = loader.languageSkillCatalog();
        assertEquals(13, catalog.size());
        assertTrue(catalog.stream().anyMatch(skill -> skill.name().equals("code-review-lang-java-jvm")
                && skill.languages().contains("java")
                && skill.filePatterns().contains("*.java")
                && skill.modes().contains("diff")));
        assertTrue(catalog.stream().anyMatch(skill -> skill.name().equals("code-review-lang-c-cpp")
                && skill.languages().contains("cpp")));
        String selected = loader.loadSelectedSkills(List.of("code-review-lang-ruby-rails", "not-a-language-skill"));
        assertTrue(selected.contains("Ruby / Rails CR Skill"));
        assertFalse(selected.contains("Java / Spring / JVM CR Skill"));
    }

    @Test
    void languageSkillRouterFallsBackByLanguageAndPath() {
        Settings settings = new Settings("url", "", "model", "", true, tmp.resolve("traces"), tmp.resolve("memory"), tmp.resolve("report"),
                30, 12000, true, true, 30);
        LlmClient malformed = (messages, tools, temperature) -> Map.of("choices", List.of(Map.of("message", Map.of(
                "role", "assistant",
                "content", "not-json"
        ))));
        LanguageSkillRouter router = new LanguageSkillRouter(settings, malformed, new TraceRecorder(tmp.resolve("traces")));
        Map<String, Object> triage = Map.of("changed_files", List.of(
                Map.of("filename", "src/main/java/com/acme/AuthService.java", "patch", "+ synchronized void login() {}"),
                Map.of("filename", "src/components/Login.tsx", "patch", "+ localStorage.setItem('token', token)"),
                Map.of("filename", ".github/workflows/ci.yml", "patch", "+ permissions: write-all")
        ));
        Map<String, Object> analysis = new java.util.LinkedHashMap<>();
        analysis.put("risk_model", Map.of("risk_types", List.of("security/auth", "dependency/build")));
        LanguageSkillRouter.Selection selection = router.selectForDiff(triage, analysis);
        List<String> names = selection.selectedSkills().stream().map(item -> String.valueOf(item.get("name"))).toList();
        assertTrue(names.contains("code-review-lang-java-jvm"));
        assertTrue(names.contains("code-review-lang-js-ts-frontend"));
        assertTrue(names.contains("code-review-lang-config-build"));
        assertTrue(selection.prompt().contains("Java / Spring / JVM CR Skill"));
        assertTrue(analysis.containsKey("language_skill_selection"));
    }

    @Test
    void exportsAgenticRlEpisodesAndRewardLabels() throws Exception {
        Path traces = tmp.resolve("rl-trace");
        Files.createDirectories(traces);
        Path trace = traces.resolve("rl.jsonl");
        Files.writeString(trace, String.join("\n",
                "{\"event_type\":\"session_start\",\"session_id\":\"rl1\",\"repo\":\"owner/repo\",\"target\":\"commit_range\",\"base\":\"a\",\"head\":\"b\",\"dry_run\":true}",
                "{\"event_type\":\"llm_request\",\"session_id\":\"rl1\",\"phase\":\"REVIEW\",\"iteration\":1,\"messages\":[{\"role\":\"system\",\"content\":\"review\"},{\"role\":\"user\",\"content\":\"check\"}],\"tools\":[{\"type\":\"function\",\"function\":{\"name\":\"search_code\",\"description\":\"Search code\",\"parameters\":{\"type\":\"object\"}}}]}",
                "{\"event_type\":\"llm_response\",\"session_id\":\"rl1\",\"phase\":\"REVIEW\",\"iteration\":1,\"response\":{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":null,\"tool_calls\":[{\"id\":\"call-1\",\"type\":\"function\",\"function\":{\"name\":\"search_code\",\"arguments\":\"{\\\"query\\\":\\\"auth\\\"}\"}}]}}]}}",
                "{\"event_type\":\"tool_call\",\"session_id\":\"rl1\",\"tool_call\":{\"id\":\"call-1\",\"name\":\"search_code\",\"arguments\":{\"query\":\"auth\"}}}",
                "{\"event_type\":\"tool_result\",\"session_id\":\"rl1\",\"tool_result\":{\"toolCallId\":\"call-1\",\"name\":\"search_code\",\"ok\":true,\"result\":{\"items\":[\"AuthService\"]},\"truncated\":false}}",
                "{\"event_type\":\"llm_request\",\"session_id\":\"rl1\",\"phase\":\"REVIEW\",\"iteration\":2,\"messages\":[{\"role\":\"system\",\"content\":\"review\"},{\"role\":\"user\",\"content\":\"check\"},{\"role\":\"assistant\",\"content\":null,\"toolCalls\":[{\"id\":\"call-1\",\"type\":\"function\",\"function\":{\"name\":\"search_code\",\"arguments\":\"{\\\"query\\\":\\\"auth\\\"}\"}}]},{\"role\":\"tool\",\"content\":\"{}\",\"toolCallId\":\"call-1\"}],\"tools\":[{\"type\":\"function\",\"function\":{\"name\":\"search_code\",\"description\":\"Search code\",\"parameters\":{\"type\":\"object\"}}}]}",
                "{\"event_type\":\"llm_response\",\"session_id\":\"rl1\",\"phase\":\"REVIEW\",\"iteration\":2,\"response\":{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"{\\\"summary\\\":\\\"ok\\\",\\\"issues\\\":[{\\\"body\\\":\\\"real issue\\\"}]}\"}}]}}",
                "{\"event_type\":\"candidate_publish\",\"session_id\":\"rl1\",\"input\":1,\"after_verification\":1,\"published\":1}",
                "{\"event_type\":\"session_end\",\"session_id\":\"rl1\",\"status\":\"completed\",\"summary\":\"ok\",\"issues_found\":1}",
                ""
        ), StandardCharsets.UTF_8);
        TraceDatasetExporter exporter = new TraceDatasetExporter();
        Path episodes = tmp.resolve("datasets/RL/episodes.jsonl");
        Path rewards = tmp.resolve("datasets/RL/rewards.jsonl");
        assertEquals(1, exporter.exportRlEpisodes(traces, episodes));
        assertEquals(1, exporter.exportRewardLabels(traces, rewards));
        String episodeText = Files.readString(episodes);
        assertTrue(episodeText.contains("\"schema_version\":\"agentic_rl_episode_v1\""));
        assertTrue(episodeText.contains("\"task_id\":\"repo=owner/repo|target=commit_range|base=a|head=b\""));
        assertTrue(episodeText.contains("\"type\":\"tool_call\""));
        assertTrue(episodeText.contains("\"tool_results\""));
        assertTrue(episodeText.contains("\"done\":true"));
        String rewardText = Files.readString(rewards);
        assertTrue(rewardText.contains("\"schema_version\":\"agentic_rl_reward_v1\""));
        assertTrue(rewardText.contains("\"terminal_reward\""));
        assertTrue(rewardText.contains("\"heuristic_trace_v1\""));
    }

    private CodeReviewAgent testAgent(Settings settings, LlmClient llm) {
        TraceRecorder trace = new TraceRecorder(settings.traceDir());
        ToolRouter router = new ToolRouter(settings.dryRun(), trace, settings.maxToolResultChars());
        registerGitHubFixtures(router);
        new MemoryTools(new MemoryStore(settings.memoryDir())).register(router);
        new TestGenerationTools().register(router);
        return new CodeReviewAgent(settings, llm, trace, router);
    }

    private void registerGitHubFixtures(ToolRouter router) {
        router.register(new ToolSpec("get_pull_request", "fixture", ToolSchemas.object(Map.of(), List.of()), args -> Map.of(
                "number", args.getOrDefault("pr", args.getOrDefault("pull_number", 1)),
                "title", "Fixture PR",
                "body", "Change auth behavior",
                "user", Map.of("login", "alice"),
                "head", Map.of("sha", "fixture-sha", "ref", "feature/auth"),
                "base", Map.of("ref", "main"),
                "additions", 1,
                "deletions", 0,
                "changed_files", 1
        ), false));
        router.register(new ToolSpec("list_changed_files", "fixture", ToolSchemas.object(Map.of(), List.of()), args -> fixtureFiles(), false));
        router.register(new ToolSpec("get_pull_request_files", "fixture", ToolSchemas.object(Map.of(), List.of()), args -> fixtureFiles(), false));
        router.register(new ToolSpec("get_pr_diff", "fixture", ToolSchemas.object(Map.of(), List.of()), args -> "diff --git a/src/example.py b/src/example.py\n+password = request.args.get('password')\n", false));
        router.register(new ToolSpec("get_commit_compare", "fixture", ToolSchemas.object(Map.of(), List.of()), args -> Map.of(
                "base_commit", Map.of("sha", args.get("base")),
                "merge_base_commit", Map.of("sha", args.get("base")),
                "status", "ahead",
                "ahead_by", 1,
                "behind_by", 0,
                "total_commits", 1,
                "commits", List.of(Map.of("sha", "head-sha", "commit", Map.of("author", Map.of("name", "alice")))),
                "files", fixtureFiles()
        ), false));
        router.register(new ToolSpec("get_commit_compare_diff", "fixture", ToolSchemas.object(Map.of(), List.of()), args -> "diff --git a/src/example.py b/src/example.py\n+password = request.args.get('password')\n", false));
        router.register(new ToolSpec("list_commits", "fixture", ToolSchemas.object(Map.of(), List.of()), args -> List.of(Map.of("sha", "fixture-sha", "message", "change auth")), false));
        router.register(new ToolSpec("get_file_contents", "fixture", ToolSchemas.object(Map.of(), List.of()), args -> Map.of("path", args.get("path"), "content", "password = request.args.get('password')\n", "sha", "fixture-sha"), false));
        router.register(new ToolSpec("list_review_comments", "fixture", ToolSchemas.object(Map.of(), List.of()), args -> List.of(), false));
        router.register(new ToolSpec("list_checks", "fixture", ToolSchemas.object(Map.of(), List.of()), args -> Map.of("check_runs", List.of(Map.of("name", "ci", "conclusion", "success"))), false));
        router.register(new ToolSpec("get_pull_request_status", "fixture", ToolSchemas.object(Map.of(), List.of()), args -> Map.of("check_runs", List.of()), false));
        router.register(new ToolSpec("search_code", "fixture", ToolSchemas.object(Map.of(), List.of()), args -> Map.of("items", List.of()), false));
        router.register(new ToolSpec("list_repository_tree", "fixture", ToolSchemas.object(Map.of(), List.of()), args -> Map.of("tree", List.of(
                Map.of("path", "src/example.py", "type", "blob"),
                Map.of("path", "tests/test_example.py", "type", "blob"),
                Map.of("path", "requirements.txt", "type", "blob")
        ), "truncated", false), false));
        router.register(new ToolSpec("get_surrounding_lines", "fixture", ToolSchemas.object(Map.of(), List.of()), args -> Map.of("path", args.get("path"), "target_line", args.getOrDefault("line", 1), "lines", List.of(Map.of("line", 1, "text", "password = request.args.get('password')"))), false));
        router.register(new ToolSpec("find_related_tests", "fixture", ToolSchemas.object(Map.of(), List.of()), args -> Map.of("source_path", args.get("source_path"), "related_tests", List.of(Map.of("path", "tests/test_example.py")), "count", 1), false));
        router.register(new ToolSpec("get_dependency_manifests", "fixture", ToolSchemas.object(Map.of(), List.of()), args -> Map.of("manifests", List.of(Map.of("path", "requirements.txt", "content", "pytest\n")), "count", 1), false));
        router.register(new ToolSpec("scan_sensitive_paths", "fixture", ToolSchemas.object(Map.of(), List.of()), args -> Map.of("paths", List.of(Map.of("path", "src/example.py", "reason", "auth-adjacent")), "count", 1), false));
        router.register(new ToolSpec("detect_test_framework", "fixture", ToolSchemas.object(Map.of(), List.of()), args -> Map.of("language", "python", "frameworks", List.of("pytest"), "primary_framework", "pytest"), false));
        for (String name : List.of("create_pull_request_review", "submit_review_comments", "create_branch", "create_or_update_file", "create_pull_request", "add_issue_comment")) {
            router.register(new ToolSpec(name, "fixture write", ToolSchemas.object(Map.of(), List.of()), args -> Map.of("ok", true), true));
        }
    }

    private List<Map<String, Object>> fixtureFiles() {
        return List.of(Map.of("filename", "src/example.py", "status", "modified", "additions", 1, "deletions", 0, "patch", "+password = request.args.get('password')"));
    }

    private Map<String, Object> fixtureTriage() {
        return Map.of(
                "changed_files", fixtureFiles(),
                "author", "alice",
                "should_review", true
        );
    }

    private Map<String, Object> assistantJson(String content) {
        return Map.of("choices", List.of(Map.of("message", Map.of("role", "assistant", "content", content))));
    }
}
