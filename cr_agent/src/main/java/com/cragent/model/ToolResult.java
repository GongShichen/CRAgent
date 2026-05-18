package com.cragent.model;

public class ToolResult {
    public String toolCallId;
    public String name;
    public boolean ok;
    public Object result;
    public String error;
    public boolean truncated;

    public static ToolResult ok(String toolCallId, String name, Object result, boolean truncated) {
        ToolResult out = new ToolResult();
        out.toolCallId = toolCallId;
        out.name = name;
        out.ok = true;
        out.result = result;
        out.truncated = truncated;
        return out;
    }

    public static ToolResult error(String toolCallId, String name, String error) {
        ToolResult out = new ToolResult();
        out.toolCallId = toolCallId;
        out.name = name;
        out.ok = false;
        out.error = error;
        return out;
    }
}

