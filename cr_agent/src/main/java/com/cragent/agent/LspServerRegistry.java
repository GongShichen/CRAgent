package com.cragent.agent;

import java.util.concurrent.TimeUnit;

public final class LspServerRegistry {
    private LspServerRegistry() {
    }

    public static boolean commandExists(String executable) {
        try {
            Process process = new ProcessBuilder("/bin/sh", "-lc", "command -v " + executable)
                    .redirectErrorStream(true)
                    .start();
            return process.waitFor(2, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
