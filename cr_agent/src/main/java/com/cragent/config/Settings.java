package com.cragent.config;

import com.cragent.util.ProjectPaths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

public record Settings(
        String openaiBaseUrl,
        String openaiApiKey,
        String openaiModel,
        String githubToken,
        boolean dryRun,
        Path traceDir,
        Path memoryDir,
        Path reportDir,
        int maxIterations,
        int maxToolResultChars,
        boolean repoAuditRunChecks,
        boolean lspEnabled,
        int lspTimeoutSeconds,
        boolean verifierEnabled,
        int verifierMaxCandidates,
        int reviewMaxComments,
        double reviewPublishThreshold,
        boolean zeroIssueRecovery,
        boolean languageSkillsEnabled,
        int languageSkillMaxSelected,
        int recoveryMaxToolRounds,
        int verifierMaxToolRounds,
        int repoBatchMaxToolRounds,
        boolean llmTriageAdvice,
        boolean llmContextScout,
        boolean llmRiskRefinement,
        boolean llmTestReasoning,
        boolean llmActPlanning,
        int contextRrfK,
        int contextMaxItems
) {
    public Settings(String openaiBaseUrl, String openaiApiKey, String openaiModel, String githubToken,
                    boolean dryRun, Path traceDir, Path memoryDir, Path reportDir, int maxIterations,
                    int maxToolResultChars, boolean repoAuditRunChecks, boolean lspEnabled, int lspTimeoutSeconds) {
        this(openaiBaseUrl, openaiApiKey, openaiModel, githubToken, dryRun, traceDir, memoryDir, reportDir,
                maxIterations, maxToolResultChars, repoAuditRunChecks, lspEnabled, lspTimeoutSeconds,
                true, 8, 6, 0.45, true, true, 6, 6, 4, 6,
                true, true, true, true, false, 60, 40);
    }

    public static Settings load() {
        Map<String, String> env = new HashMap<>();
        env.putAll(System.getenv());
        Path cwd = Path.of("").toAbsolutePath().normalize();
        if (cwd.getFileName() != null && cwd.getFileName().toString().equals("cr_agent")) {
            env.putAll(readDotEnv(cwd.getParent().resolve(".env")));
        }
        env.putAll(readDotEnv(Path.of(".env")));
        return from(env);
    }

    public static Settings load(Path envFile) {
        Map<String, String> env = new HashMap<>();
        env.putAll(System.getenv());
        env.putAll(readDotEnv(envFile));
        return from(env);
    }

    public Settings withDryRun(boolean value) {
        return new Settings(openaiBaseUrl, openaiApiKey, openaiModel, githubToken, value, traceDir, memoryDir, reportDir,
                maxIterations, maxToolResultChars, repoAuditRunChecks, lspEnabled, lspTimeoutSeconds,
                verifierEnabled, verifierMaxCandidates, reviewMaxComments, reviewPublishThreshold, zeroIssueRecovery,
                languageSkillsEnabled, languageSkillMaxSelected, recoveryMaxToolRounds, verifierMaxToolRounds, repoBatchMaxToolRounds,
                llmTriageAdvice, llmContextScout, llmRiskRefinement, llmTestReasoning, llmActPlanning, contextRrfK, contextMaxItems);
    }

    public Settings withTraceDir(Path value) {
        return new Settings(openaiBaseUrl, openaiApiKey, openaiModel, githubToken, dryRun, value, memoryDir, reportDir,
                maxIterations, maxToolResultChars, repoAuditRunChecks, lspEnabled, lspTimeoutSeconds,
                verifierEnabled, verifierMaxCandidates, reviewMaxComments, reviewPublishThreshold, zeroIssueRecovery,
                languageSkillsEnabled, languageSkillMaxSelected, recoveryMaxToolRounds, verifierMaxToolRounds, repoBatchMaxToolRounds,
                llmTriageAdvice, llmContextScout, llmRiskRefinement, llmTestReasoning, llmActPlanning, contextRrfK, contextMaxItems);
    }

    public Settings withVerifierEnabled(boolean value) {
        return new Settings(openaiBaseUrl, openaiApiKey, openaiModel, githubToken, dryRun, traceDir, memoryDir, reportDir,
                maxIterations, maxToolResultChars, repoAuditRunChecks, lspEnabled, lspTimeoutSeconds,
                value, verifierMaxCandidates, reviewMaxComments, reviewPublishThreshold, zeroIssueRecovery,
                languageSkillsEnabled, languageSkillMaxSelected, recoveryMaxToolRounds, verifierMaxToolRounds, repoBatchMaxToolRounds,
                llmTriageAdvice, llmContextScout, llmRiskRefinement, llmTestReasoning, llmActPlanning, contextRrfK, contextMaxItems);
    }

    private static Settings from(Map<String, String> env) {
        return new Settings(
                env.getOrDefault("OPENAI_BASE_URL", "https://token-plan-cn.xiaomimimo.com/v1"),
                env.getOrDefault("OPENAI_API_KEY", ""),
                env.getOrDefault("OPENAI_MODEL", "mimo-v2.5-pro"),
                env.getOrDefault("GITHUB_TOKEN", ""),
                Boolean.parseBoolean(env.getOrDefault("CR_AGENT_DRY_RUN", "true")),
                Path.of(env.getOrDefault("CR_AGENT_TRACE_DIR", "data/traces")),
                Path.of(env.getOrDefault("CR_AGENT_MEMORY_DIR", "data/memory")),
                resolveRepoPath(env.getOrDefault("CR_AGENT_REPORT_DIR", "report")),
                Integer.parseInt(env.getOrDefault("CR_AGENT_MAX_ITERATIONS", "30")),
                Integer.parseInt(env.getOrDefault("CR_AGENT_MAX_TOOL_RESULT_CHARS", "12000")),
                Boolean.parseBoolean(env.getOrDefault("CR_AGENT_REPO_AUDIT_RUN_CHECKS", "true")),
                Boolean.parseBoolean(env.getOrDefault("CR_AGENT_LSP_ENABLED", "true")),
                Integer.parseInt(env.getOrDefault("CR_AGENT_LSP_TIMEOUT_SECONDS", "30")),
                Boolean.parseBoolean(env.getOrDefault("CR_AGENT_VERIFIER_ENABLED", "true")),
                Integer.parseInt(env.getOrDefault("CR_AGENT_VERIFIER_MAX_CANDIDATES", "8")),
                Integer.parseInt(env.getOrDefault("CR_AGENT_REVIEW_MAX_COMMENTS", "6")),
                Double.parseDouble(env.getOrDefault("CR_AGENT_REVIEW_PUBLISH_THRESHOLD", "0.45")),
                Boolean.parseBoolean(env.getOrDefault("CR_AGENT_ZERO_ISSUE_RECOVERY", "true")),
                Boolean.parseBoolean(env.getOrDefault("CR_AGENT_LANGUAGE_SKILLS_ENABLED", "true")),
                Integer.parseInt(env.getOrDefault("CR_AGENT_LANGUAGE_SKILL_MAX_SELECTED", "6")),
                Integer.parseInt(env.getOrDefault("CR_AGENT_RECOVERY_MAX_TOOL_ROUNDS", "6")),
                Integer.parseInt(env.getOrDefault("CR_AGENT_VERIFIER_MAX_TOOL_ROUNDS", "4")),
                Integer.parseInt(env.getOrDefault("CR_AGENT_REPO_BATCH_MAX_TOOL_ROUNDS", "6")),
                Boolean.parseBoolean(env.getOrDefault("CR_AGENT_LLM_TRIAGE_ADVICE", "true")),
                Boolean.parseBoolean(env.getOrDefault("CR_AGENT_LLM_CONTEXT_SCOUT", "true")),
                Boolean.parseBoolean(env.getOrDefault("CR_AGENT_LLM_RISK_REFINEMENT", "true")),
                Boolean.parseBoolean(env.getOrDefault("CR_AGENT_LLM_TEST_REASONING", "true")),
                Boolean.parseBoolean(env.getOrDefault("CR_AGENT_LLM_ACT_PLANNING", "false")),
                Integer.parseInt(env.getOrDefault("CR_AGENT_CONTEXT_RRF_K", "60")),
                Integer.parseInt(env.getOrDefault("CR_AGENT_CONTEXT_MAX_ITEMS", "40"))
        );
    }

    private static Path resolveRepoPath(String value) {
        Path path = Path.of(value);
        return path.isAbsolute() ? path : ProjectPaths.repoRoot().resolve(path).normalize();
    }

    public boolean hasLlmCredentials() {
        return openaiBaseUrl != null && !openaiBaseUrl.isBlank()
                && openaiApiKey != null && !openaiApiKey.isBlank()
                && openaiModel != null && !openaiModel.isBlank();
    }

    public boolean hasGithubCredentials() {
        return githubToken != null && !githubToken.isBlank();
    }

    private static Map<String, String> readDotEnv(Path path) {
        Map<String, String> values = new HashMap<>();
        if (!Files.exists(path)) {
            return values;
        }
        try {
            for (String line : Files.readAllLines(path)) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#") || !trimmed.contains("=")) {
                    continue;
                }
                int idx = trimmed.indexOf('=');
                String key = trimmed.substring(0, idx).trim();
                String value = trimmed.substring(idx + 1).trim();
                if ((value.startsWith("\"") && value.endsWith("\"")) || (value.startsWith("'") && value.endsWith("'"))) {
                    value = value.substring(1, value.length() - 1);
                }
                values.put(key, value);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Unable to read .env file: " + path, e);
        }
        return values;
    }
}
