package com.cragent;

import com.cragent.agent.CodeReviewAgent;
import com.cragent.agent.ReviewResultParser;
import com.cragent.config.Settings;
import com.cragent.datasets.TraceDatasetExporter;
import com.cragent.llm.FakeLlmClient;
import com.cragent.llm.LlmClient;
import com.cragent.model.AgentRunResult;
import com.cragent.model.ReviewResult;
import com.cragent.model.ToolCall;
import com.cragent.model.ToolResult;
import com.cragent.cli.ChatCommandParser;
import com.cragent.cli.PrIdentifier;
import com.cragent.skills.SkillLoader;
import com.cragent.tools.ToolRouter;
import com.cragent.tools.ToolSchemas;
import com.cragent.tools.ToolSpec;
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
    void githubContextToolsRequireLiveCredentials() {
        assertFalse(new com.cragent.tools.GitHubTools("").available());
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
    void fullAgentDryRunWithFakeLlm() {
        Settings settings = new Settings(
                "https://token-plan-cn.xiaomimimo.com/v1",
                "",
                "mimo-v2.5-pro",
                "",
                true,
                tmp.resolve("traces"),
                tmp.resolve("memory"),
                30,
                12000,
                2000
        );
        AgentRunResult result = testAgent(settings, new FakeLlmClient()).review("owner/repo", 1);
        assertEquals("completed", result.status);
        assertFalse(result.issues.isEmpty());
        assertTrue(result.tracePath.toFile().exists());
    }

    @Test
    void reviewQualityGateFiltersWeakFindings() {
        Settings settings = new Settings(
                "https://token-plan-cn.xiaomimimo.com/v1",
                "",
                "mimo-v2.5-pro",
                "",
                true,
                tmp.resolve("quality-traces"),
                tmp.resolve("quality-memory"),
                30,
                12000,
                2000
        );
        AgentRunResult result = testAgent(settings, (messages, tools, temperature) -> Map.of("choices", List.of(Map.of("message", Map.of(
                "role", "assistant",
                "content", """
                        {
                          "summary": "quality gate test",
                          "issues": [
                            {"severity":"low","category":"style","file":"src/example.py","line":99,"body":"weak uncertain style note","confidence":0.3},
                            {"severity":"high","category":"logic","file":"src/example.py","line":99,"body":"real issue but wrong inline line","evidence":"+password = request.args.get('password')","impact":"credential leakage","confidence":0.9},
                            {"severity":"high","category":"security","file":"src/not_changed.py","line":1,"body":"not in diff","confidence":0.9}
                          ],
                          "shouldComment": true
                        }
                        """
        ))))).review("owner/repo", 1);
        assertEquals(1, result.issues.size());
        assertEquals("bug", result.issues.getFirst().category);
        assertNull(result.issues.getFirst().line);
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
                30,
                12000,
                2000
        );
        MemoryTools memory = new MemoryTools(new MemoryStore(settings.memoryDir()));
        memory.memoryGetAll(Map.of());
        memory.memoryAddFalsePositive(Map.of(
                "pattern", "password problem",
                "reason", "fixture-like test value",
                "file_patterns", List.of("src/example.py")
        ));
        AgentRunResult result = testAgent(settings, (messages, tools, temperature) -> Map.of("choices", List.of(Map.of("message", Map.of(
                "role", "assistant",
                "content", """
                        {
                          "summary": "fp",
                          "issues": [
                            {"severity":"high","category":"security","file":"src/example.py","line":1,"body":"test 文件中的硬编码值 password problem","evidence":"+password = request.args.get('password')","impact":"none","confidence":0.9}
                          ],
                          "shouldComment": true
                        }
                        """
        ))))).review("owner/repo", 1);
        assertTrue(result.issues.isEmpty());
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
                30,
                12000,
                2000
        );
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
    void liveMemoryActionsAcceptReviewIssues() {
        Settings settings = new Settings(
                "https://token-plan-cn.xiaomimimo.com/v1",
                "",
                "mimo-v2.5-pro",
                "",
                false,
                tmp.resolve("live-memory-traces"),
                tmp.resolve("live-memory"),
                30,
                12000,
                2000
        );
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
                30,
                12000,
                2000
        );
        AgentRunResult result = testAgent(settings, new FakeLlmClient()).reviewCommits("owner/repo", "base-sha", "head-sha");
        assertEquals("completed", result.status);
        assertTrue(result.tracePath.toFile().exists());
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
    void settingsLoadExplicitEnvFile() throws Exception {
        Path env = tmp.resolve(".env");
        Files.writeString(env, "OPENAI_BASE_URL=https://example.test/v1\nOPENAI_API_KEY=secret\nOPENAI_MODEL=model-x\nCR_AGENT_DRY_RUN=false\nCR_AGENT_HUMAN_REVIEW_CHANGED_LINES_THRESHOLD=1234\n", StandardCharsets.UTF_8);
        Settings settings = Settings.load(env);
        assertEquals("https://example.test/v1", settings.openaiBaseUrl());
        assertEquals("secret", settings.openaiApiKey());
        assertEquals("model-x", settings.openaiModel());
        assertEquals(1234, settings.humanReviewChangedLinesThreshold());
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
    }

    @Test
    void exportsSftAndDpoDatasets() throws Exception {
        Path traces = tmp.resolve("traces");
        Settings good = new Settings("url", "", "model", "", true, traces, tmp.resolve("memory"), 30, 12000, 2000);
        testAgent(good, new FakeLlmClient()).review("owner/repo", 1);

        Settings bad = new Settings("url", "", "model", "", true, traces, tmp.resolve("memory2"), 0, 12000, 2000);
        testAgent(bad, new FakeLlmClient()).review("owner/repo", 2);

        TraceDatasetExporter exporter = new TraceDatasetExporter();
        Path sft = tmp.resolve("datasets/SFT/sft.jsonl");
        Path dpo = tmp.resolve("datasets/DPO/dpo.jsonl");
        assertEquals(1, exporter.exportSft(traces, sft));
        assertEquals(1, exporter.exportDpo(traces, dpo));
        assertTrue(Files.readString(sft).contains("\"messages\""));
        assertTrue(Files.readString(dpo).contains("\"chosen\""));
    }

    @Test
    void exporterAcceptsClaudeStyleTrace() throws Exception {
        Path traces = tmp.resolve("claude");
        Files.createDirectories(traces);
        Path trace = traces.resolve("trace_abc.jsonl");
        Files.writeString(trace, String.join("\n",
                "{\"type\":\"session_start\",\"session_id\":\"abc\",\"pr\":\"owner/repo#1\"}",
                "{\"type\":\"user\",\"message\":{\"role\":\"user\",\"content\":\"Review PR #1\"}}",
                "{\"type\":\"assistant\",\"message\":{\"role\":\"assistant\",\"content\":[{\"type\":\"text\",\"text\":\"Checking.\"},{\"type\":\"tool_use\",\"id\":\"tool-1\",\"name\":\"get_pull_request\",\"input\":{\"owner\":\"o\",\"repo\":\"r\",\"pull_number\":1}}],\"stop_reason\":\"tool_use\",\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}}",
                "{\"type\":\"user\",\"message\":{\"role\":\"user\",\"content\":[{\"type\":\"tool_result\",\"tool_use_id\":\"tool-1\",\"content\":[{\"type\":\"text\",\"text\":\"{}\"}]}]}}",
                "{\"type\":\"session_end\",\"session_id\":\"abc\",\"stats\":{\"decision\":\"COMMENT\",\"issues_found\":1}}",
                ""
        ), StandardCharsets.UTF_8);
        Path out = tmp.resolve("sft.jsonl");
        assertEquals(1, new TraceDatasetExporter().exportSft(traces, out));
        String text = Files.readString(out);
        assertTrue(text.contains("\"tool_calls\""));
        assertFalse(text.contains("[Tool:"));
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
}
