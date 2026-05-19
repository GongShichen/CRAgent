package com.cragent.cli;

import com.cragent.llm.LlmClient;
import com.cragent.llm.OpenAiCompatibleClient;
import com.cragent.model.ChatMessage;
import com.cragent.skills.SkillLoader;
import com.cragent.util.Jsons;

import java.util.List;
import java.util.Locale;
import java.util.Map;

public class LlmIntentRouter {
    private static final double MIN_CONFIDENCE = 0.55;

    private final LlmClient llm;

    public LlmIntentRouter(LlmClient llm) {
        this.llm = llm;
    }

    public ChatCommandParser.ChatIntent route(String input) {
        try {
            String skill = new SkillLoader().loadSkill("code-review-intent", false);
            Map<String, Object> response = llm.chatJson(List.of(
                    new ChatMessage("system", skill),
                    new ChatMessage("user", Jsons.stringify(Map.of("input", input)))
            ), List.of(), 0.0);
            ChatMessage assistant = OpenAiCompatibleClient.assistantMessage(response);
            Map<String, Object> parsed = Jsons.parseMap(assistant.content == null ? "{}" : assistant.content);
            ChatCommandParser.ChatIntent intent = fromMap(parsed);
            return valid(intent, parsed) ? intent : ChatCommandParser.parse(input);
        } catch (Exception e) {
            return ChatCommandParser.parse(input);
        }
    }

    private static boolean valid(ChatCommandParser.ChatIntent intent, Map<String, Object> raw) {
        double confidence = number(raw.get("confidence"), 0.0);
        if (confidence < MIN_CONFIDENCE) {
            return false;
        }
        return switch (intent.type()) {
            case PR -> intent.repo() != null && intent.pr() != null && intent.pr() > 0;
            case COMMITS -> intent.repo() != null && intent.base() != null && intent.head() != null;
            case REPO, REPO_AUDIT -> intent.repo() != null;
            case HELP, EXIT, UNKNOWN -> true;
        };
    }

    private static ChatCommandParser.ChatIntent fromMap(Map<String, Object> raw) {
        String type = text(raw.get("type")).toUpperCase(Locale.ROOT);
        String repo = ChatCommandParser.normalizeRepo(text(raw.get("repo")));
        Boolean dryRun = booleanOrNull(raw.get("dry_run"));
        return switch (type) {
            case "PR" -> ChatCommandParser.ChatIntent.pr(repo, (int) number(raw.get("pr"), 0), dryRun);
            case "COMMITS" -> ChatCommandParser.ChatIntent.commits(repo, text(raw.get("base")), text(raw.get("head")), dryRun);
            case "REPO_AUDIT" -> ChatCommandParser.ChatIntent.repoAudit(repo, dryRun);
            case "REPO_LATEST", "REPO" -> ChatCommandParser.ChatIntent.repo(repo, dryRun);
            case "HELP" -> ChatCommandParser.ChatIntent.help();
            case "EXIT" -> ChatCommandParser.ChatIntent.exit();
            default -> ChatCommandParser.ChatIntent.unknown(text(raw.getOrDefault("reason", "Unable to classify intent.")));
        };
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static double number(Object value, double fallback) {
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return Double.parseDouble(text(value));
        } catch (Exception e) {
            return fallback;
        }
    }

    private static Boolean booleanOrNull(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        String text = text(value);
        if (text.isBlank() || "null".equalsIgnoreCase(text)) {
            return null;
        }
        return Boolean.parseBoolean(text);
    }
}
