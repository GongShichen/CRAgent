package com.cragent.tools;

import java.util.Map;

@FunctionalInterface
public interface ToolHandler {
    Object handle(Map<String, Object> args);
}

