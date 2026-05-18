package com.cragent.llm;

import com.cragent.model.ChatMessage;

import java.util.List;
import java.util.Map;

public interface LlmClient {
    Map<String, Object> chat(List<ChatMessage> messages, List<Map<String, Object>> tools, double temperature);

    default Map<String, Object> chatJson(List<ChatMessage> messages, List<Map<String, Object>> tools, double temperature) {
        return chat(messages, tools, temperature);
    }
}
