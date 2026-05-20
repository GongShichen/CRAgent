package com.cragent.datasets;

import com.fasterxml.jackson.core.type.TypeReference;
import com.cragent.util.Jsons;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

public class TraceDatasetExporter {
    public int exportRlEpisodes(Path inputPath, Path outputPath) {
        List<List<Map<String, Object>>> sessions = sessions(inputPath);
        ensureParent(outputPath);
        int count = 0;
        StringBuilder out = new StringBuilder();
        for (List<Map<String, Object>> events : sessions) {
            Map<String, Object> episode = rlEpisode(events);
            if (!episode.isEmpty()) {
                out.append(Jsons.stringify(episode)).append(System.lineSeparator());
                count++;
            }
        }
        write(outputPath, out.toString());
        return count;
    }

    public int exportRewardLabels(Path inputPath, Path outputPath) {
        List<List<Map<String, Object>>> sessions = sessions(inputPath);
        ensureParent(outputPath);
        int count = 0;
        StringBuilder out = new StringBuilder();
        for (List<Map<String, Object>> events : sessions) {
            Map<String, Object> reward = rewardLabel(events);
            if (!reward.isEmpty()) {
                out.append(Jsons.stringify(reward)).append(System.lineSeparator());
                count++;
            }
        }
        write(outputPath, out.toString());
        return count;
    }

    private Map<String, Object> rlEpisode(List<Map<String, Object>> events) {
        Map<String, Object> start = firstEvent(events, "session_start");
        Map<String, Object> end = lastEvent(events, "session_end");
        if (start.isEmpty() && end.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> reward = rewardLabel(events);
        List<Map<String, Object>> steps = rlSteps(events, doubleValue(reward.get("terminal_reward"), 0.0));
        Map<String, Object> episode = new LinkedHashMap<>();
        episode.put("schema_version", "agentic_rl_episode_v1");
        episode.put("session_id", sessionId(events));
        episode.put("task_id", taskKey(events));
        episode.put("task", taskMetadata(start));
        episode.put("status", end.getOrDefault("status", statusFromEvents(events)));
        episode.put("done", !end.isEmpty());
        episode.put("terminal_reward", reward.getOrDefault("terminal_reward", 0.0));
        episode.put("reward_components", reward.getOrDefault("components", Map.of()));
        episode.put("steps", steps);
        episode.put("trace_events", events.size());
        return episode;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> rlSteps(List<Map<String, Object>> events, double terminalReward) {
        List<Map<String, Object>> steps = new ArrayList<>();
        Map<String, Object> pendingRequest = null;
        int llmStepByEventIndex = -1;
        Map<String, Integer> toolCallToStep = new LinkedHashMap<>();
        for (Map<String, Object> event : events) {
            String type = String.valueOf(event.get("event_type"));
            if ("llm_request".equals(type)) {
                pendingRequest = event;
                llmStepByEventIndex = -1;
            } else if ("llm_response".equals(type) && pendingRequest != null) {
                Map<String, Object> action = assistantAction(event);
                Map<String, Object> step = new LinkedHashMap<>();
                step.put("step", steps.size() + 1);
                step.put("phase", event.getOrDefault("phase", pendingRequest.getOrDefault("phase", "")));
                step.put("iteration", event.getOrDefault("iteration", pendingRequest.getOrDefault("iteration", 0)));
                step.put("state", Map.of(
                        "messages", normalizeMessages(pendingRequest.get("messages")),
                        "available_tools", normalizeTools(pendingRequest.get("tools"))
                ));
                step.put("action", action);
                step.put("observation", Map.of("tool_results", new ArrayList<>()));
                step.put("reward", 0.0);
                step.put("done", false);
                step.put("ts", event.getOrDefault("ts", ""));
                steps.add(step);
                llmStepByEventIndex = steps.size() - 1;
                for (Map<String, Object> call : listOfMaps(action.get("tool_calls"))) {
                    Object id = call.get("id");
                    if (id != null) {
                        toolCallToStep.put(String.valueOf(id), llmStepByEventIndex);
                    }
                }
                pendingRequest = null;
            } else if ("tool_result".equals(type)) {
                Object raw = event.get("tool_result");
                if (!(raw instanceof Map<?, ?> rawMap)) {
                    continue;
                }
                Map<String, Object> result = normalizeToolResult((Map<String, Object>) rawMap);
                String id = String.valueOf(result.getOrDefault("tool_call_id", ""));
                Integer targetStep = toolCallToStep.get(id);
                if (targetStep != null && targetStep >= 0 && targetStep < steps.size()) {
                    appendToolObservation(steps.get(targetStep), result);
                    steps.get(targetStep).put("reward", stepRewardForToolResult(result));
                } else {
                    Map<String, Object> step = new LinkedHashMap<>();
                    step.put("step", steps.size() + 1);
                    step.put("phase", event.getOrDefault("phase", ""));
                    step.put("iteration", event.getOrDefault("iteration", 0));
                    step.put("state", Map.of("messages", List.of(), "available_tools", List.of()));
                    step.put("action", Map.of("type", "external_tool_observation", "tool_call_id", id, "tool_name", result.getOrDefault("name", "")));
                    step.put("observation", Map.of("tool_results", List.of(result)));
                    step.put("reward", stepRewardForToolResult(result));
                    step.put("done", false);
                    step.put("ts", event.getOrDefault("ts", ""));
                    steps.add(step);
                }
            } else if ("max_iterations".equals(type)) {
                Map<String, Object> step = new LinkedHashMap<>();
                step.put("step", steps.size() + 1);
                step.put("phase", event.getOrDefault("phase", ""));
                step.put("iteration", event.getOrDefault("iterations", 0));
                step.put("state", Map.of("messages", List.of(), "available_tools", List.of()));
                step.put("action", Map.of("type", "guardrail_stop"));
                step.put("observation", Map.of("event", event));
                step.put("reward", -0.5);
                step.put("done", false);
                step.put("ts", event.getOrDefault("ts", ""));
                steps.add(step);
            }
        }
        if (!steps.isEmpty()) {
            Map<String, Object> last = steps.getLast();
            last.put("done", true);
            last.put("reward", terminalReward);
        }
        return steps;
    }

    @SuppressWarnings("unchecked")
    private void appendToolObservation(Map<String, Object> step, Map<String, Object> result) {
        Object rawObservation = step.get("observation");
        List<Map<String, Object>> results = new ArrayList<>();
        if (rawObservation instanceof Map<?, ?> observation && observation.get("tool_results") instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    results.add(new LinkedHashMap<>((Map<String, Object>) map));
                }
            }
        }
        results.add(result);
        step.put("observation", Map.of("tool_results", results));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> assistantAction(Map<String, Object> llmResponseEvent) {
        Object rawResponse = llmResponseEvent.get("response");
        if (!(rawResponse instanceof Map<?, ?> response)) {
            return Map.of("type", "assistant_message", "message", Map.of());
        }
        Object rawChoices = response.get("choices");
        if (!(rawChoices instanceof List<?> choices) || choices.isEmpty() || !(choices.getFirst() instanceof Map<?, ?> choice)) {
            return Map.of("type", "assistant_message", "message", Map.of());
        }
        Object rawMessage = choice.get("message");
        if (!(rawMessage instanceof Map<?, ?> message)) {
            return Map.of("type", "assistant_message", "message", Map.of());
        }
        Map<String, Object> normalized = normalizeOpenAiMessage((Map<String, Object>) message);
        List<Map<String, Object>> toolCalls = listOfMaps(normalized.get("tool_calls"));
        Map<String, Object> action = new LinkedHashMap<>();
        action.put("type", toolCalls.isEmpty() ? "final_answer" : "tool_call");
        action.put("message", normalized);
        action.put("tool_calls", toolCalls);
        return action;
    }

    private Map<String, Object> rewardLabel(List<Map<String, Object>> events) {
        Map<String, Object> start = firstEvent(events, "session_start");
        Map<String, Object> end = lastEvent(events, "session_end");
        if (start.isEmpty() && end.isEmpty()) {
            return Map.of();
        }
        int issues = asInt(end.get("issues_found"));
        long errorEvents = events.stream().filter(e -> "error".equals(e.get("event_type"))).count();
        long maxIterationEvents = events.stream().filter(e -> "max_iterations".equals(e.get("event_type"))).count();
        long failedTools = events.stream()
                .filter(e -> "tool_result".equals(e.get("event_type")))
                .filter(e -> {
                    Object raw = e.get("tool_result");
                    return raw instanceof Map<?, ?> map && Boolean.FALSE.equals(map.get("ok"));
                })
                .count();
        long disallowedTools = events.stream()
                .filter(e -> "tool_result".equals(e.get("event_type")))
                .filter(e -> "disallowed".equals(String.valueOf(e.get("policy"))))
                .count();
        long truncatedTools = events.stream()
                .filter(e -> "tool_result".equals(e.get("event_type")))
                .filter(e -> {
                    Object raw = e.get("tool_result");
                    return raw instanceof Map<?, ?> map && Boolean.TRUE.equals(map.get("truncated"));
                })
                .count();
        long droppedCandidates = events.stream()
                .filter(e -> "candidate_verifier_result".equals(e.get("event_type")))
                .filter(e -> "DROP".equals(String.valueOf(e.get("verdict"))))
                .count();
        long publishedCandidates = events.stream()
                .filter(e -> "candidate_publish".equals(e.get("event_type")))
                .mapToLong(e -> asInt(e.get("published")))
                .sum();
        String status = String.valueOf(end.getOrDefault("status", statusFromEvents(events)));
        double completed = "completed".equals(status) || end.containsKey("stats") ? 0.6 : -0.6;
        double issueReward = Math.min(issues, 5) * 0.08;
        double publishReward = Math.min(publishedCandidates, 5) * 0.05;
        double errorPenalty = errorEvents * -0.25;
        double maxIterationPenalty = maxIterationEvents * -0.7;
        double failedToolPenalty = failedTools * -0.08;
        double disallowedPenalty = disallowedTools * -0.2;
        double truncationPenalty = truncatedTools * -0.02;
        double verifierPenalty = droppedCandidates * -0.04;
        Map<String, Object> components = new LinkedHashMap<>();
        components.put("completed", completed);
        components.put("issues_found", issueReward);
        components.put("published_candidates", publishReward);
        components.put("error_events", errorPenalty);
        components.put("max_iterations", maxIterationPenalty);
        components.put("failed_tools", failedToolPenalty);
        components.put("disallowed_tools", disallowedPenalty);
        components.put("truncated_tools", truncationPenalty);
        components.put("verifier_dropped_candidates", verifierPenalty);
        double terminal = completed + issueReward + publishReward + errorPenalty + maxIterationPenalty
                + failedToolPenalty + disallowedPenalty + truncationPenalty + verifierPenalty;
        terminal = Math.max(-1.0, Math.min(1.0, terminal));
        Map<String, Object> label = new LinkedHashMap<>();
        label.put("schema_version", "agentic_rl_reward_v1");
        label.put("session_id", sessionId(events));
        label.put("task_id", taskKey(events));
        label.put("task", taskMetadata(start));
        label.put("status", status);
        label.put("terminal_reward", terminal);
        label.put("components", components);
        label.put("counts", Map.of(
                "issues_found", issues,
                "published_candidates", publishedCandidates,
                "error_events", errorEvents,
                "max_iterations", maxIterationEvents,
                "failed_tools", failedTools,
                "disallowed_tools", disallowedTools,
                "truncated_tools", truncatedTools,
                "verifier_dropped_candidates", droppedCandidates
        ));
        label.put("source", "heuristic_trace_v1");
        return label;
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

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> normalizeMessages(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> messages = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> message) {
                messages.add(normalizeOpenAiMessage((Map<String, Object>) message));
            }
        }
        return messages;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> normalizeTools(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> tools = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> tool) {
                tools.add(new LinkedHashMap<>((Map<String, Object>) tool));
            }
        }
        return tools;
    }

    private Map<String, Object> normalizeOpenAiMessage(Map<String, Object> message) {
        Map<String, Object> normalized = new LinkedHashMap<>();
        Object role = message.get("role");
        if (role != null) {
            normalized.put("role", role);
        }
        if (message.containsKey("content")) {
            normalized.put("content", message.get("content"));
        }
        Object toolCalls = message.containsKey("tool_calls") ? message.get("tool_calls") : message.get("toolCalls");
        if (toolCalls instanceof List<?> list && !list.isEmpty()) {
            normalized.put("tool_calls", list);
        }
        Object toolCallId = message.containsKey("tool_call_id") ? message.get("tool_call_id") : message.get("toolCallId");
        if (toolCallId != null) {
            normalized.put("tool_call_id", toolCallId);
        }
        return normalized.isEmpty() ? message : normalized;
    }

    private Map<String, Object> normalizeToolResult(Map<String, Object> result) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tool_call_id", result.getOrDefault("toolCallId", result.getOrDefault("tool_call_id", "")));
        out.put("name", result.getOrDefault("name", ""));
        out.put("ok", result.getOrDefault("ok", false));
        out.put("result", result.get("result"));
        out.put("error", result.get("error"));
        out.put("truncated", result.getOrDefault("truncated", false));
        return out;
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

    private Map<String, Object> firstEvent(List<Map<String, Object>> events, String eventType) {
        for (Map<String, Object> event : events) {
            if (eventType.equals(event.get("event_type")) || eventType.equals(event.get("type"))) {
                return event;
            }
        }
        return Map.of();
    }

    private String taskKey(List<Map<String, Object>> events) {
        Map<String, Object> start = lastEvent(events, "session_start");
        if (start.isEmpty()) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        for (String key : List.of("repo", "target", "pr", "base", "head")) {
            Object value = start.get(key);
            if (value != null && !String.valueOf(value).isBlank()) {
                parts.add(key + "=" + value);
            }
        }
        return String.join("|", parts);
    }

    private String sessionId(List<Map<String, Object>> events) {
        for (Map<String, Object> event : events) {
            Object id = event.get("session_id");
            if (id != null && !String.valueOf(id).isBlank()) {
                return String.valueOf(id);
            }
        }
        return "";
    }

    private Map<String, Object> taskMetadata(Map<String, Object> start) {
        Map<String, Object> task = new LinkedHashMap<>();
        for (String key : List.of("repo", "target", "pr", "base", "head", "dry_run")) {
            if (start.containsKey(key)) {
                task.put(key, start.get(key));
            }
        }
        return task;
    }

    private String statusFromEvents(List<Map<String, Object>> events) {
        if (events.stream().anyMatch(e -> "error".equals(e.get("event_type")))) {
            return "failed";
        }
        if (events.stream().anyMatch(e -> "max_iterations".equals(e.get("event_type")))) {
            return "max_iterations";
        }
        return "unknown";
    }

    private double stepRewardForToolResult(Map<String, Object> result) {
        double reward = Boolean.TRUE.equals(result.get("ok")) ? 0.04 : -0.08;
        if (Boolean.TRUE.equals(result.get("truncated"))) {
            reward -= 0.02;
        }
        return reward;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> listOfMaps(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                out.add(new LinkedHashMap<>((Map<String, Object>) map));
            }
        }
        return out;
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

    private static double doubleValue(Object value, double fallback) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value == null) {
            return fallback;
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException e) {
            return fallback;
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

}
