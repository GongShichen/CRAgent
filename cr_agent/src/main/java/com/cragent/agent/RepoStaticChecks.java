package com.cragent.agent;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class RepoStaticChecks {
    public List<Map<String, Object>> run(Path repoPath, Map<String, Object> stack) {
        List<Map<String, Object>> results = new ArrayList<>();
        maybe(results, repoPath, Files.exists(repoPath.resolve("pom.xml")), "mvn", "test", "-q");
        maybe(results, repoPath, Files.exists(repoPath.resolve("build.gradle")) || Files.exists(repoPath.resolve("build.gradle.kts")), "./gradlew", "test");
        maybe(results, repoPath, Files.exists(repoPath.resolve("package.json")), "npm", "test", "--", "--runInBand");
        maybe(results, repoPath, Files.exists(repoPath.resolve("go.mod")), "go", "test", "./...");
        maybe(results, repoPath, Files.exists(repoPath.resolve("pyproject.toml")) || Files.exists(repoPath.resolve("pytest.ini")), "pytest", "-q");
        maybe(results, repoPath, Files.exists(repoPath.resolve("Cargo.toml")), "cargo", "test");
        return results;
    }

    private static void maybe(List<Map<String, Object>> results, Path dir, boolean enabled, String... command) {
        if (!enabled) {
            return;
        }
        results.add(run(dir, command));
    }

    private static Map<String, Object> run(Path dir, String... command) {
        try {
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.directory(dir.toFile());
            Process process = builder.start();
            boolean finished = process.waitFor(Duration.ofSeconds(45).toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                return Map.of("command", String.join(" ", command), "status", "timeout", "output", "");
            }
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8)
                    + "\n" + new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            return Map.of("command", String.join(" ", command), "status", process.exitValue() == 0 ? "passed" : "failed",
                    "exit_code", process.exitValue(), "output", truncate(output, 8000));
        } catch (Exception e) {
            return Map.of("command", String.join(" ", command), "status", "unavailable", "output", e.getMessage());
        }
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max) + "\n...[truncated]";
    }
}
