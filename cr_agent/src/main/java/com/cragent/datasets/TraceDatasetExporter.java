package com.cragent.datasets;

import com.fasterxml.jackson.core.type.TypeReference;
import com.cragent.util.Jsons;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

public class TraceDatasetExporter {
    public int exportSft(Path inputPath, Path outputPath) {
        List<List<Map<String, Object>>> sessions = sessions(inputPath);
        ensureParent(outputPath);
        int count = 0;
        StringBuilder out = new StringBuilder();
        for (List<Map<String, Object>> events : sessions) {
            if (!eligibleForSft(events)) {
                continue;
            }
            List<Map<String, Object>> messages = messagesFromEvents(events);
            if (!messages.isEmpty()) {
                out.append(Jsons.stringify(Map.of("messages", messages))).append(System.lineSeparator());
                count++;
            }
        }
        write(outputPath, out.toString());
        return count;
    }

    public int exportDpo(Path inputPath, Path outputPath) {
        List<List<Map<String, Object>>> sessions = sessions(inputPath);
        List<ScoredSession> scored = sessions.stream()
                .map(s -> new ScoredSession(qualityScore(s), s))
                .sorted(Comparator.comparingDouble(ScoredSession::score).reversed())
                .toList();
        ensureParent(outputPath);
        int count = 0;
        StringBuilder out = new StringBuilder();
        int half = scored.size() / 2;
        for (int i = 0; i < half; i++) {
            ScoredSession high = scored.get(i);
            ScoredSession low = scored.get(scored.size() - 1 - i);
            if (high.score <= low.score) {
                continue;
            }
            String prompt = firstUserPrompt(high.events);
            if (prompt == null) {
                prompt = firstUserPrompt(low.events);
            }
            String chosen = finalSummary(high.events);
            String rejected = finalSummary(low.events);
            if (prompt != null && chosen != null && rejected != null) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("prompt", prompt);
                row.put("chosen", chosen);
                row.put("rejected", rejected);
                row.put("chosen_score", high.score);
                row.put("rejected_score", low.score);
                out.append(Jsons.stringify(row)).append(System.lineSeparator());
                count++;
            }
        }
        write(outputPath, out.toString());
        return count;
    }

    public double qualityScore(List<Map<String, Object>> events) {
        Map<String, Object> end = lastEvent(events, "session_end");
        double score = 0.0;
        if ("completed".equals(end.get("status")) || end.containsKey("stats")) {
            score += 10.0;
        }
        if (events.stream().anyMatch(e -> "max_iterations".equals(e.get("event_type")))) {
            score -= 10.0;
        }
        if (events.stream().anyMatch(e -> "error".equals(e.get("event_type")))) {
            score -= 5.0;
        }
        Object issues = end.get("issues_found");
        if (issues == null && end.get("stats") instanceof Map<?, ?> stats) {
            issues = stats.get("issues_found");
        }
        score += Math.min(asInt(issues), 5);
        long failedTools = events.stream()
                .filter(e -> "tool_result".equals(e.get("event_type")))
                .filter(e -> {
                    Object raw = e.get("tool_result");
                    return raw instanceof Map<?, ?> map && Boolean.FALSE.equals(map.get("ok"));
                })
                .count();
        score -= failedTools * 0.5;
        return score;
    }

    private List<List<Map<String, Object>>> sessions(Path inputPath) {
        return jsonlFiles(inputPath).stream().map(this::readJsonl).toList();
    }

    private List<Path> jsonlFiles(Path inputPath) {
        if (!Files.exists(inputPath)) {
            return List.of();
        }
        if (Files.isRegularFile(inputPath)) {
            return List.of(inputPath);
        }
        try (Stream<Path> stream = Files.list(inputPath)) {
            return stream.filter(p -> p.getFileName().toString().endsWith(".jsonl"))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new IllegalStateException("Unable to list trace directory: " + inputPath, e);
        }
    }

    private List<Map<String, Object>> readJsonl(Path path) {
        try {
            List<Map<String, Object>> records = new ArrayList<>();
            for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                if (!line.isBlank()) {
                    records.add(Jsons.MAPPER.readValue(line, new TypeReference<>() {
                    }));
                }
            }
            return records;
        } catch (IOException e) {
            throw new IllegalStateException("Unable to read trace: " + path, e);
        }
    }

    private boolean eligibleForSft(List<Map<String, Object>> events) {
        Map<String, Object> end = lastEvent(events, "session_end");
        return ("completed".equals(end.get("status")) || end.containsKey("stats"))
                && events.stream().noneMatch(e -> "max_iterations".equals(e.get("event_type")));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> messagesFromEvents(List<Map<String, Object>> events) {
        List<Map<String, Object>> messages = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Map<String, Object> event : events) {
            if ("llm_request".equals(event.get("event_type"))) {
                Object raw = event.get("messages");
                if (raw instanceof List<?> list) {
                    for (Object item : list) {
                        if (item instanceof Map<?, ?> message) {
                            addMessage(messages, seen, (Map<String, Object>) message);
                        }
                    }
                }
            } else if ("llm_response".equals(event.get("event_type"))) {
                Object rawResponse = event.get("response");
                if (rawResponse instanceof Map<?, ?> response) {
                    Object rawChoices = response.get("choices");
                    if (rawChoices instanceof List<?> choices && !choices.isEmpty() && choices.getFirst() instanceof Map<?, ?> choice) {
                        Object rawMessage = choice.get("message");
                        if (rawMessage instanceof Map<?, ?> message) {
                            addMessage(messages, seen, (Map<String, Object>) message);
                        }
                    }
                }
            }
            if ("user".equals(event.get("type")) || "assistant".equals(event.get("type"))) {
                Object rawMessage = event.get("message");
                if (rawMessage instanceof Map<?, ?> message) {
                    addMessage(messages, seen, normalizeClaudeMessage((Map<String, Object>) message));
                }
            }
        }
        return messages;
    }

    private void addMessage(List<Map<String, Object>> messages, Set<String> seen, Map<String, Object> message) {
        String key = Jsons.stringify(message);
        if (seen.add(key)) {
            messages.add(message);
        }
    }

    private String firstUserPrompt(List<Map<String, Object>> events) {
        for (Map<String, Object> event : events) {
            if (!"llm_request".equals(event.get("event_type"))) {
                if ("user".equals(event.get("type")) && event.get("message") instanceof Map<?, ?> message) {
                    Object content = message.get("content");
                    return content == null ? null : String.valueOf(content);
                }
                continue;
            }
            Object raw = event.get("messages");
            if (raw instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof Map<?, ?> message && "user".equals(message.get("role"))) {
                        Object content = message.get("content");
                        return content == null ? null : String.valueOf(content);
                    }
                }
            }
        }
        return null;
    }

    private String finalSummary(List<Map<String, Object>> events) {
        Object summary = lastEvent(events, "session_end").get("summary");
        if (summary != null) {
            return String.valueOf(summary);
        }
        Map<String, Object> end = lastEvent(events, "session_end");
        if (end.get("stats") instanceof Map<?, ?> stats) {
            Object decision = stats.get("decision");
            Object issues = stats.get("issues_found");
            return "decision=" + (decision == null ? "" : decision) + ", issues_found=" + (issues == null ? 0 : issues);
        }
        return null;
    }

    private Map<String, Object> lastEvent(List<Map<String, Object>> events, String eventType) {
        for (int i = events.size() - 1; i >= 0; i--) {
            Map<String, Object> event = events.get(i);
            if (eventType.equals(event.get("event_type")) || eventType.equals(event.get("type"))) {
                return event;
            }
        }
        return Map.of();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> normalizeClaudeMessage(Map<String, Object> message) {
        Object content = message.get("content");
        if (!"assistant".equals(message.get("role")) || !(content instanceof List<?> blocks)) {
            return message;
        }
        List<Map<String, Object>> toolCalls = new ArrayList<>();
        List<String> textParts = new ArrayList<>();
        for (Object blockObj : blocks) {
            if (!(blockObj instanceof Map<?, ?> block)) {
                continue;
            }
            Object type = block.get("type");
            if ("text".equals(type)) {
                Object text = block.get("text");
                textParts.add(String.valueOf(text == null ? "" : text));
            } else if ("thinking".equals(type)) {
                Object thinking = block.get("thinking");
                textParts.add("<thinking>\n" + (thinking == null ? "" : thinking) + "\n</thinking>");
            } else if ("tool_use".equals(type)) {
                Map<String, Object> function = new LinkedHashMap<>();
                function.put("name", block.get("name"));
                Object input = block.get("input");
                function.put("arguments", Jsons.stringify(input == null ? Map.of() : input));
                Object id = block.get("id");
                toolCalls.add(Map.of(
                        "id", id == null ? "" : id,
                        "type", "function",
                        "function", function
                ));
            }
        }
        Map<String, Object> normalized = new LinkedHashMap<>();
        normalized.put("role", "assistant");
        if (!textParts.isEmpty()) {
            normalized.put("content", String.join("\n\n", textParts));
        }
        if (!toolCalls.isEmpty()) {
            normalized.put("tool_calls", toolCalls);
        }
        return normalized;
    }

    private static int asInt(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null) {
            return 0;
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static void ensureParent(Path outputPath) {
        try {
            Files.createDirectories(outputPath.getParent());
        } catch (IOException e) {
            throw new IllegalStateException("Unable to create output directory: " + outputPath.getParent(), e);
        }
    }

    private static void write(Path outputPath, String content) {
        try {
            Files.writeString(outputPath, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to write dataset: " + outputPath, e);
        }
    }

    private record ScoredSession(double score, List<Map<String, Object>> events) {
    }
}
