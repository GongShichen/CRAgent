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

    public static Path defaultSftPath() {
        return repoRoot().resolve("datasets/SFT/sft.jsonl");
    }

    public static Path defaultDpoPath() {
        return repoRoot().resolve("datasets/DPO/dpo.jsonl");
    }
}

