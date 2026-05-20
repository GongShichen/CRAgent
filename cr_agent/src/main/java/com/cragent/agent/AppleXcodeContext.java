package com.cragent.agent;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

public final class AppleXcodeContext {
    private AppleXcodeContext() {
    }

    public static Map<String, Object> probe(Path repoPath) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<String> markers = appleMarkers(repoPath);
        boolean hasAppleProject = !markers.isEmpty();
        boolean hasXcodebuild = LspServerRegistry.commandExists("xcodebuild");
        boolean hasSwift = LspServerRegistry.commandExists("swift");
        boolean hasXcrun = LspServerRegistry.commandExists("xcrun");
        CommandResult mcpBridge = hasXcrun ? run(repoPath, Duration.ofSeconds(5), "xcrun", "-f", "mcpbridge") : CommandResult.unavailable("xcrun -f mcpbridge", "xcrun is not on PATH.");

        out.put("platform", "apple");
        out.put("has_apple_markers", hasAppleProject);
        out.put("markers", markers);
        out.put("xcodebuild_available", hasXcodebuild);
        out.put("swift_available", hasSwift);
        out.put("xcrun_available", hasXcrun);
        out.put("mcpbridge_available", mcpBridge.exitCode == 0);
        out.put("mcpbridge_path", mcpBridge.exitCode == 0 ? mcpBridge.output.strip() : "");
        out.put("mcpbridge_status", mcpBridge.status());
        out.put("repo_audit_eligible", hasAppleProject && (hasXcodebuild || hasSwift));
        out.put("external_agent_setup", "Enable Xcode Intelligence MCP access, open the project in Xcode, then configure `codex mcp add xcode -- xcrun mcpbridge`.");
        return out;
    }

    public static List<String> appleMarkers(Path repoPath) {
        List<String> markers = new ArrayList<>();
        addIfExists(markers, repoPath, "Package.swift");
        addIfExists(markers, repoPath, "Podfile");
        try (Stream<Path> paths = Files.list(repoPath)) {
            paths.map(path -> path.getFileName().toString())
                    .filter(AppleXcodeContext::isAppleProjectMarker)
                    .sorted()
                    .forEach(markers::add);
        } catch (Exception ignored) {
        }
        return markers.stream().distinct().toList();
    }

    public static String[] xcodeListCommand(Path repoPath) {
        List<String> markers = appleMarkers(repoPath);
        for (String marker : markers) {
            String lower = marker.toLowerCase(Locale.ROOT);
            if (lower.endsWith(".xcworkspace")) {
                return new String[]{"xcodebuild", "-list", "-workspace", marker};
            }
        }
        for (String marker : markers) {
            String lower = marker.toLowerCase(Locale.ROOT);
            if (lower.endsWith(".xcodeproj")) {
                return new String[]{"xcodebuild", "-list", "-project", marker};
            }
        }
        return new String[]{"xcodebuild", "-list"};
    }

    private static boolean isAppleProjectMarker(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".xcodeproj") || lower.endsWith(".xcworkspace");
    }

    private static void addIfExists(List<String> markers, Path repoPath, String name) {
        if (Files.exists(repoPath.resolve(name))) {
            markers.add(name);
        }
    }

    private static CommandResult run(Path dir, Duration timeout, String... command) {
        try {
            Process process = new ProcessBuilder(command)
                    .directory(dir.toFile())
                    .redirectErrorStream(true)
                    .start();
            boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                return new CommandResult(String.join(" ", command), 124, "timeout");
            }
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            return new CommandResult(String.join(" ", command), process.exitValue(), output);
        } catch (Exception e) {
            return CommandResult.unavailable(String.join(" ", command), e.getMessage());
        }
    }

    private record CommandResult(String command, int exitCode, String output) {
        static CommandResult unavailable(String command, String output) {
            return new CommandResult(command, 127, output == null ? "" : output);
        }

        String status() {
            if (exitCode == 0) return "available";
            if (exitCode == 124) return "timeout";
            if (exitCode == 127) return "unavailable";
            return "failed";
        }
    }
}
