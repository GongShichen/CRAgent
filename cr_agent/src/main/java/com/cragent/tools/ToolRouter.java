package com.cragent.tools;

import com.cragent.model.ToolCall;
import com.cragent.model.ToolResult;
import com.cragent.trace.TraceRecorder;
import com.cragent.util.Jsons;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ToolRouter {
    private final Map<String, ToolSpec> tools = new LinkedHashMap<>();
    private final boolean dryRun;
    private final TraceRecorder trace;
    private final int maxResultChars;

    public ToolRouter(boolean dryRun, TraceRecorder trace, int maxResultChars) {
        this.dryRun = dryRun;
        this.trace = trace;
        this.maxResultChars = maxResultChars;
    }

    public void register(ToolSpec spec) {
        if (tools.containsKey(spec.name())) {
            throw new IllegalArgumentException("Tool already registered: " + spec.name());
        }
        tools.put(spec.name(), spec);
    }

    public List<Map<String, Object>> schemas() {
        return tools.values().stream().map(ToolSpec::openAiSchema).toList();
    }

    public ToolResult call(ToolCall call) {
        trace.record("tool_call", Map.of("tool_call", Map.of("id", call.id(), "name", call.name(), "arguments", call.arguments())));
        ToolSpec spec = tools.get(call.name());
        if (spec == null) {
            ToolResult result = ToolResult.error(call.id(), call.name(), "Unknown tool: " + call.name());
            trace.record("tool_result", Map.of("tool_result", result));
            return result;
        }
        if (dryRun && spec.write()) {
            ToolResult result = ToolResult.ok(call.id(), call.name(), Map.of(
                    "dry_run", true,
                    "tool", call.name(),
                    "arguments", call.arguments()
            ), false);
            trace.record("tool_result", Map.of("tool_result", result));
            return result;
        }
        try {
            Object value = spec.handler().handle(call.arguments());
            Truncated truncated = truncate(value);
            ToolResult result = ToolResult.ok(call.id(), call.name(), truncated.value(), truncated.truncated());
            trace.record("tool_result", Map.of("tool_result", result));
            return result;
        } catch (Exception e) {
            ToolResult result = ToolResult.error(call.id(), call.name(), e.getMessage());
            trace.record("tool_result", Map.of("tool_result", result));
            return result;
        }
    }

    public boolean hasTool(String name) {
        return tools.containsKey(name);
    }

    private Truncated truncate(Object value) {
        String text = Jsons.stringify(value);
        if (text.length() <= maxResultChars) {
            return new Truncated(value, false);
        }
        if (value instanceof List<?> list) {
            return new Truncated(truncatedListPreview(list, text.length()), true);
        }
        return new Truncated(Map.of("truncated", true, "preview", text.substring(0, maxResultChars), "original_chars", text.length()), true);
    }

    private Map<String, Object> truncatedListPreview(List<?> list, int originalChars) {
        java.util.ArrayList<Object> previewItems = new java.util.ArrayList<>();
        int budget = Math.max(256, maxResultChars - 256);
        for (Object item : list) {
            java.util.ArrayList<Object> candidate = new java.util.ArrayList<>(previewItems);
            candidate.add(item);
            if (!previewItems.isEmpty() && Jsons.stringify(candidate).length() > budget) {
                break;
            }
            if (Jsons.stringify(candidate).length() > budget && previewItems.isEmpty()) {
                previewItems.add(item);
                break;
            }
            previewItems.add(item);
        }
        return Map.of(
                "truncated", true,
                "items_preview", previewItems,
                "preview_count", previewItems.size(),
                "total_items", list.size(),
                "original_chars", originalChars
        );
    }

    private record Truncated(Object value, boolean truncated) {
    }
}
