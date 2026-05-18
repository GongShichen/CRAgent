package com.cragent.config;

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
        int maxIterations,
        int maxToolResultChars,
        int humanReviewChangedLinesThreshold
) {
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
        return new Settings(openaiBaseUrl, openaiApiKey, openaiModel, githubToken, value, traceDir, memoryDir,
                maxIterations, maxToolResultChars, humanReviewChangedLinesThreshold);
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
                Integer.parseInt(env.getOrDefault("CR_AGENT_MAX_ITERATIONS", "30")),
                Integer.parseInt(env.getOrDefault("CR_AGENT_MAX_TOOL_RESULT_CHARS", "12000")),
                Integer.parseInt(env.getOrDefault("CR_AGENT_HUMAN_REVIEW_CHANGED_LINES_THRESHOLD", "2000"))
        );
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
