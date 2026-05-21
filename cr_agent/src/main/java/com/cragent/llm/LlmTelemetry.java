package com.cragent.llm;

import com.cragent.trace.TraceRecorder;

import java.util.LinkedHashMap;
import java.util.Map;

public final class LlmTelemetry {
    private LlmTelemetry() {
    }

    public static void recordResponse(TraceRecorder trace, String phase, int iteration, Map<String, Object> response) {
        recordResponse(trace, phase, iteration, response, Map.of());
    }

    public static void recordResponse(TraceRecorder trace, String phase, int iteration,
                                      Map<String, Object> response, Map<String, Object> extra) {
        Map<String, Object> responsePayload = new LinkedHashMap<>();
        responsePayload.put("phase", phase);
        if (iteration > 0) {
            responsePayload.put("iteration", iteration);
        }
        if (extra != null) {
            responsePayload.putAll(extra);
        }
        responsePayload.put("response", response);
        trace.record("llm_response", responsePayload);

        Map<String, Object> usage = usage(response);
        if (!usage.isEmpty()) {
            Map<String, Object> usagePayload = new LinkedHashMap<>();
            usagePayload.put("phase", phase);
            if (iteration > 0) {
                usagePayload.put("iteration", iteration);
            }
            if (extra != null) {
                usagePayload.putAll(extra);
            }
            usagePayload.put("usage", usage);
            addCacheMetrics(usagePayload, usage);
            trace.record("llm_usage", usagePayload);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> usage(Map<String, Object> response) {
        Object raw = response == null ? null : response.get("usage");
        if (raw instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            map.forEach((key, value) -> out.put(String.valueOf(key), value));
            return out;
        }
        return Map.of();
    }

    private static void addCacheMetrics(Map<String, Object> payload, Map<String, Object> usage) {
        long hit = number(usage.get("prompt_cache_hit_tokens"));
        long miss = number(usage.get("prompt_cache_miss_tokens"));
        if (hit == 0 && miss == 0) {
            return;
        }
        payload.put("prompt_cache_hit_tokens", hit);
        payload.put("prompt_cache_miss_tokens", miss);
        long total = hit + miss;
        if (total > 0) {
            payload.put("prompt_cache_hit_ratio", (double) hit / (double) total);
        }
    }

    private static long number(Object value) {
        if (value instanceof Number n) {
            return n.longValue();
        }
        if (value instanceof String s && !s.isBlank()) {
            try {
                return Long.parseLong(s);
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }
}
