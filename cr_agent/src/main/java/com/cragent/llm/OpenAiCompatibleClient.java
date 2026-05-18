package com.cragent.llm;

import com.fasterxml.jackson.core.type.TypeReference;
import com.cragent.config.Settings;
import com.cragent.model.ChatMessage;
import com.cragent.util.Jsons;
import com.cragent.util.Retry;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class OpenAiCompatibleClient implements LlmClient {
    private final Settings settings;
    private final HttpClient client = HttpClient.newHttpClient();

    public OpenAiCompatibleClient(Settings settings) {
        this.settings = settings;
    }

    @Override
    public Map<String, Object> chat(List<ChatMessage> messages, List<Map<String, Object>> tools, double temperature) {
        return chat(messages, tools, temperature, false);
    }

    @Override
    public Map<String, Object> chatJson(List<ChatMessage> messages, List<Map<String, Object>> tools, double temperature) {
        return chat(messages, tools, temperature, true);
    }

    private Map<String, Object> chat(List<ChatMessage> messages, List<Map<String, Object>> tools, double temperature, boolean jsonMode) {
        if (!settings.hasLlmCredentials()) {
            throw new IllegalStateException("OPENAI_API_KEY is required for real LLM calls");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", settings.openaiModel());
        payload.put("messages", messages.stream().map(this::messageToMap).toList());
        payload.put("temperature", temperature);
        if (tools != null && !tools.isEmpty()) {
            payload.put("tools", tools);
            payload.put("tool_choice", "auto");
        }
        if (jsonMode) {
            payload.put("response_format", Map.of("type", "json_object"));
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(settings.openaiBaseUrl().replaceAll("/$", "") + "/chat/completions"))
                .header("Authorization", "Bearer " + settings.openaiApiKey())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Jsons.stringify(payload)))
                .build();
        return Retry.run("LLM request", () -> {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                if (Retry.retryableStatus(response.statusCode())) {
                    throw new IOException("LLM retryable HTTP " + response.statusCode() + " " + response.body());
                }
                throw new IllegalStateException("LLM request failed: HTTP " + response.statusCode() + " " + response.body());
            }
            try {
                return Jsons.MAPPER.readValue(response.body(), new TypeReference<>() {
                });
            } catch (IOException e) {
                throw new IllegalStateException("Unable to parse LLM response", e);
            }
        });
    }

    private Map<String, Object> messageToMap(ChatMessage message) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("role", message.role);
        if (message.content != null) {
            out.put("content", message.content);
        }
        if (message.toolCalls != null) {
            out.put("tool_calls", message.toolCalls);
        }
        if (message.toolCallId != null) {
            out.put("tool_call_id", message.toolCallId);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    public static ChatMessage assistantMessage(Map<String, Object> response) {
        List<Map<String, Object>> choices = (List<Map<String, Object>>) response.getOrDefault("choices", List.of());
        if (choices.isEmpty()) {
            return new ChatMessage("assistant", "");
        }
        Map<String, Object> choice = choices.get(0);
        Map<String, Object> msg = (Map<String, Object>) choice.getOrDefault("message", Map.of());
        ChatMessage out = new ChatMessage();
        out.role = "assistant";
        out.content = (String) msg.get("content");
        out.toolCalls = (List<Map<String, Object>>) msg.get("tool_calls");
        return out;
    }

    @SuppressWarnings("unchecked")
    public static List<com.cragent.model.ToolCall> extractToolCalls(ChatMessage message) {
        List<com.cragent.model.ToolCall> calls = new ArrayList<>();
        if (message.toolCalls == null) {
            return calls;
        }
        for (Map<String, Object> raw : message.toolCalls) {
            String id = String.valueOf(raw.getOrDefault("id", java.util.UUID.randomUUID().toString()));
            Map<String, Object> fn = (Map<String, Object>) raw.getOrDefault("function", Map.of());
            String name = String.valueOf(fn.getOrDefault("name", raw.getOrDefault("name", "")));
            Object args = fn.getOrDefault("arguments", "{}");
            Map<String, Object> parsed = args instanceof String s ? Jsons.parseMap(s) : (Map<String, Object>) args;
            calls.add(new com.cragent.model.ToolCall(id, name, parsed));
        }
        return calls;
    }
}
