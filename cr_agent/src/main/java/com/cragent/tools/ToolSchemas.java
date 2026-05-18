package com.cragent.tools;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ToolSchemas {
    private ToolSchemas() {
    }

    public static Map<String, Object> object(Map<String, Object> properties, List<String> required) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", "object");
        out.put("properties", properties);
        out.put("required", required);
        return out;
    }

    public static Map<String, Object> str(String description) {
        return Map.of("type", "string", "description", description);
    }

    public static Map<String, Object> integer(String description) {
        return Map.of("type", "integer", "description", description);
    }

    public static Map<String, Object> bool(String description) {
        return Map.of("type", "boolean", "description", description);
    }

    public static Map<String, Object> array(String description) {
        return Map.of("type", "array", "description", description, "items", Map.of("type", "object"));
    }
}
