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
        return new Truncated(Map.of("truncated", true, "preview", text.substring(0, maxResultChars), "original_chars", text.length()), true);
    }

    private record Truncated(Object value, boolean truncated) {
    }
}
