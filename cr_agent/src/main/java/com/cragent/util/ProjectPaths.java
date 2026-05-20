package com.cragent.util;

import java.nio.file.Path;

public final class ProjectPaths {
    private ProjectPaths() {
    }

    public static Path repoRoot() {
        Path cwd = Path.of("").toAbsolutePath().normalize();
        if (cwd.getFileName() != null && cwd.getFileName().toString().equals("cr_agent")) {
            return cwd.getParent();
        }
        return cwd;
    }

    public static Path defaultTraceDir() {
        Path cwd = Path.of("").toAbsolutePath().normalize();
        Path local = cwd.resolve("data/traces");
        return local;
    }

    public static Path defaultRlEpisodesPath() {
        return repoRoot().resolve("datasets/RL/episodes.jsonl");
    }

    public static Path defaultRlRewardsPath() {
        return repoRoot().resolve("datasets/RL/rewards.jsonl");
    }

    public static Path defaultReportDir() {
        return repoRoot().resolve("report");
    }
}
