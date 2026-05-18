package com.cragent.tools;

import java.util.LinkedHashMap;
import java.util.Map;

public record ToolSpec(String name, String description, Map<String, Object> parameters, ToolHandler handler, boolean write) {
    public Map<String, Object> openAiSchema() {
        Map<String, Object> fn = new LinkedHashMap<>();
        fn.put("name", name);
        fn.put("description", description);
        fn.put("parameters", parameters);
        return Map.of("type", "function", "function", fn);
    }
}

