package com.cragent.model;

import java.util.List;
import java.util.Map;

public class ChatMessage {
    public String role;
    public String content;
    public List<Map<String, Object>> toolCalls;
    public String toolCallId;

    public ChatMessage() {
    }

    public ChatMessage(String role, String content) {
        this.role = role;
        this.content = content;
    }

    public static ChatMessage tool(String toolCallId, String content) {
        ChatMessage message = new ChatMessage("tool", content);
        message.toolCallId = toolCallId;
        return message;
    }
}

