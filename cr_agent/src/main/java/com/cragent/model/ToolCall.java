package com.cragent.model;

import java.util.HashMap;
import java.util.Map;

public record ToolCall(String id, String name, Map<String, Object> arguments) {
    public ToolCall {
        if (arguments == null) {
            arguments = new HashMap<>();
        }
    }
}

