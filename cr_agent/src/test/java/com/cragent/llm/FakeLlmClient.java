package com.cragent.llm;

import com.cragent.model.ChatMessage;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class FakeLlmClient implements LlmClient {
    @Override
    public Map<String, Object> chat(List<ChatMessage> messages, List<Map<String, Object>> tools, double temperature) {
        String content = """
                {
                  "summary": "Dry-run simulated Java review completed.",
                  "issues": [
                    {
                      "severity": "medium",
                      "category": "maintainability",
                      "file": "src/example.py",
                      "line": 1,
                      "body": "Simulated issue for validating the Java review pipeline.",
                      "suggestion": "Configure real credentials to review live PRs.",
                      "auto_fixable": false,
                      "confidence": 0.8
                    }
                  ],
                  "shouldComment": true,
                  "shouldCreateFixPr": false,
                  "shouldUpdateMemory": true
                }
                """;
        return new LinkedHashMap<>(Map.of(
                "choices", List.of(Map.of(
                        "finish_reason", "stop",
                        "message", Map.of("role", "assistant", "content", content)
                )),
                "usage", Map.of("prompt_tokens", 0, "completion_tokens", 0)
        ));
    }
}

