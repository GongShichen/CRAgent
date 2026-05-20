package com.cragent.agent;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

public class RepoStaticChecks {
    public List<Map<String, Object>> run(Path repoPath, Map<String, Object> stack) {
        List<Map<String, Object>> results = new ArrayList<>();
        Map<String, Object> appleContext = AppleXcodeContext.probe(repoPath);
        if (Boolean.TRUE.equals(appleContext.get("has_apple_markers"))) {
            results.add(Map.of("command", "xcode_context_probe", "status", "completed", "output", appleContext));
        }
        maybe(results, repoPath, Files.exists(repoPath.resolve("pom.xml")), "mvn", "test", "-q");
        maybe(results, repoPath, Files.exists(repoPath.resolve("build.gradle")) || Files.exists(repoPath.resolve("build.gradle.kts")), "./gradlew", "test");
        maybe(results, repoPath, Files.exists(repoPath.resolve("package.json")), "npm", "test", "--", "--runInBand");
        maybe(results, repoPath, Files.exists(repoPath.resolve("go.mod")), "go", "test", "./...");
        maybe(results, repoPath, Files.exists(repoPath.resolve("pyproject.toml")) || Files.exists(repoPath.resolve("pytest.ini")), "pytest", "-q");
        maybe(results, repoPath, Files.exists(repoPath.resolve("Cargo.toml")), "cargo", "test");
        maybe(results, repoPath, Files.exists(repoPath.resolve("Package.swift")), "swift", "test");
        maybe(results, repoPath, hasXcodeProject(repoPath), AppleXcodeContext.xcodeListCommand(repoPath));
        maybe(results, repoPath, Files.exists(repoPath.resolve("Gemfile")), "bundle", "exec", "rspec", "--dry-run", "--format", "progress");
        maybe(results, repoPath, Files.exists(repoPath.resolve("Gemfile")), "bundle", "exec", "rubocop", "--format", "simple");
        maybe(results, repoPath, Files.exists(repoPath.resolve("Gemfile")) && hasAny(repoPath, "Rakefile", "rakefile"), "bundle", "exec", "rake", "test");
        maybe(results, repoPath, !Files.exists(repoPath.resolve("Gemfile")) && hasAny(repoPath, "Rakefile", "rakefile"), "rake", "test");
        maybe(results, repoPath, Files.exists(repoPath.resolve("compile_commands.json")), "clang-tidy", firstNativeSource(repoPath), "-p", ".");
        maybe(results, repoPath, hasNativeSources(repoPath), "cppcheck", "--enable=warning,performance,portability", "--inline-suppr", "--quiet", ".");
        return results;
    }

    private static void maybe(List<Map<String, Object>> results, Path dir, boolean enabled, String... command) {
        if (!enabled) {
            return;
        }
        results.add(run(dir, command));
    }

    private static Map<String, Object> run(Path dir, String... command) {
        for (String part : command) {
            if (part == null || part.isBlank()) {
                return Map.of("command", safeCommand(command), "status", "skipped", "output", "No suitable source file found for this static check.");
            }
        }
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

    private static String safeCommand(String... command) {
        List<String> parts = new ArrayList<>();
        for (String part : command) {
            parts.add(part == null ? "" : part);
        }
        return String.join(" ", parts).trim();
    }

    private static boolean hasAny(Path repoPath, String... names) {
        for (String name : names) {
            if (Files.exists(repoPath.resolve(name))) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasXcodeProject(Path repoPath) {
        try (Stream<Path> paths = Files.list(repoPath)) {
            return paths.anyMatch(path -> {
                String name = path.getFileName().toString().toLowerCase();
                return name.endsWith(".xcodeproj") || name.endsWith(".xcworkspace");
            });
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean hasNativeSources(Path repoPath) {
        return firstNativeSource(repoPath) != null;
    }

    private static String firstNativeSource(Path repoPath) {
        try (Stream<Path> paths = Files.walk(repoPath, 8)) {
            return paths
                    .filter(Files::isRegularFile)
                    .map(repoPath::relativize)
                    .map(Path::toString)
                    .filter(RepoStaticChecks::isNativeSource)
                    .filter(path -> !path.startsWith("build/") && !path.startsWith("Pods/") && !path.startsWith("vendor/"))
                    .findFirst()
                    .orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean isNativeSource(String path) {
        String lower = path.toLowerCase();
        return lower.endsWith(".c") || lower.endsWith(".cc") || lower.endsWith(".cpp") || lower.endsWith(".cxx")
                || lower.endsWith(".m") || lower.endsWith(".mm");
    }
}
