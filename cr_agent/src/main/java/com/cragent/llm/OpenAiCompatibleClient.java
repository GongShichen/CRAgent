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
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class OpenAiCompatibleClient implements LlmClient {
    private final Settings settings;
    private final HttpClient client;

    public OpenAiCompatibleClient(Settings settings) {
        this(settings, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.min(60, Math.max(5, settings.llmTimeoutSeconds()))))
                .build());
    }

    OpenAiCompatibleClient(Settings settings, HttpClient client) {
        this.settings = settings;
        this.client = client;
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
        String thinkingMode = thinkingMode();
        if (!thinkingMode.isBlank()) {
            payload.put("thinking", Map.of("type", thinkingMode));
        }
        if (tools != null && !tools.isEmpty()) {
            payload.put("tools", tools);
            payload.put("tool_choice", "auto");
        }
        if (jsonMode) {
            payload.put("response_format", Map.of("type", "json_object"));
        }

        String body = Jsons.stringify(payload);
        URI uri = URI.create(settings.openaiBaseUrl().replaceAll("/$", "") + "/chat/completions");
        Retry.RetryPolicy policy = Retry.policy(
                settings.llmRetryMaxAttempts(),
                settings.llmRetryInitialBackoffMillis(),
                settings.llmRetryMaxBackoffMillis()
        );
        return Retry.run("LLM request", policy, () -> {
            int timeoutSeconds = Math.max(1, settings.llmTimeoutSeconds());
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(uri)
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .header("Authorization", "Bearer " + settings.openaiApiKey())
                    .header("api-key", settings.openaiApiKey())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .get(timeoutSeconds, TimeUnit.SECONDS);
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                if (Retry.retryableStatus(response.statusCode())) {
                    throw new RetryableLlmHttpException(response.statusCode(), response.body(), retryAfterMillis(response));
                }
                throw new IllegalStateException("LLM request failed: HTTP " + response.statusCode() + " " + response.body());
            }
            try {
                return Jsons.MAPPER.readValue(response.body(), new TypeReference<>() {
                });
            } catch (IOException e) {
                throw new IllegalStateException("Unable to parse LLM response", e);
            }
        }, (attempt, error) -> {
            long providerDelay = retryAfterMillis(error);
            if (providerDelay > 0) {
                sleep(providerDelay);
            }
        });
    }

    private static long retryAfterMillis(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof RetryableLlmHttpException retryable) {
                return retryable.retryAfterMillis();
            }
            current = current.getCause();
        }
        return 0L;
    }

    private static long retryAfterMillis(HttpResponse<?> response) {
        return response.headers().firstValue("retry-after")
                .map(String::trim)
                .flatMap(value -> {
                    try {
                        return java.util.Optional.of(Math.max(0L, Long.parseLong(value) * 1000L));
                    } catch (NumberFormatException e) {
                        return java.util.Optional.empty();
                    }
                })
                .orElse(0L);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("LLM retry-after sleep interrupted", e);
        }
    }

    private static final class RetryableLlmHttpException extends IOException {
        private final int statusCode;
        private final long retryAfterMillis;

        private RetryableLlmHttpException(int statusCode, String body, long retryAfterMillis) {
            super("LLM retryable HTTP " + statusCode + " " + body);
            this.statusCode = statusCode;
            this.retryAfterMillis = retryAfterMillis;
        }

        int statusCode() {
            return statusCode;
        }

        long retryAfterMillis() {
            return retryAfterMillis;
        }
    }

    private Map<String, Object> messageToMap(ChatMessage message) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("role", message.role);
        if (message.content != null) {
            out.put("content", message.content);
        }
        if (message.reasoningContent != null && !message.reasoningContent.isBlank()) {
            out.put("reasoning_content", message.reasoningContent);
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
        out.reasoningContent = (String) msg.get("reasoning_content");
        out.toolCalls = (List<Map<String, Object>>) msg.get("tool_calls");
        return out;
    }

    private String thinkingMode() {
        String configured = settings.llmThinkingMode() == null ? "" : settings.llmThinkingMode().trim().toLowerCase();
        if (configured.equals("enabled") || configured.equals("disabled")) {
            return configured;
        }
        if (configured.equals("auto") || configured.isBlank()) {
            String baseUrl = settings.openaiBaseUrl() == null ? "" : settings.openaiBaseUrl().toLowerCase();
            if (baseUrl.contains("deepseek.com")) {
                return "disabled";
            }
        }
        return "";
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
