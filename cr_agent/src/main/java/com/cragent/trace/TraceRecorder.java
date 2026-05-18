package com.cragent.trace;

import com.cragent.util.Jsons;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public class TraceRecorder {
    private final String sessionId;
    private final Path path;

    public TraceRecorder(Path traceDir) {
        this.sessionId = UUID.randomUUID().toString().replace("-", "");
        this.path = traceDir.resolve(sessionId + ".jsonl");
    }

    public String sessionId() {
        return sessionId;
    }

    public Path path() {
        return path;
    }

    public synchronized void record(String eventType, Map<String, Object> payload) {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("event_type", eventType);
        record.put("session_id", sessionId);
        record.put("ts", Instant.now().toString());
        if (payload != null) {
            record.putAll(payload);
        }
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, Jsons.stringify(record) + System.lineSeparator(), StandardCharsets.UTF_8,
                    Files.exists(path)
                            ? java.nio.file.StandardOpenOption.APPEND
                            : java.nio.file.StandardOpenOption.CREATE);
        } catch (IOException e) {
            System.err.println("Warning: unable to write trace " + path + ": " + e.getMessage());
        }
    }
}
