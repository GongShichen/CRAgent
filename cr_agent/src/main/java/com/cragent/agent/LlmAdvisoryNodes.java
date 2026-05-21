package com.cragent.agent;

import com.cragent.config.Settings;
import com.cragent.llm.LlmClient;
import com.cragent.llm.LlmTelemetry;
import com.cragent.llm.OpenAiCompatibleClient;
import com.cragent.model.ChatMessage;
import com.cragent.model.ReviewResult;
import com.cragent.trace.TraceRecorder;
import com.cragent.util.Jsons;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class LlmAdvisoryNodes {
    private LlmAdvisoryNodes() {
    }

    public static Map<String, Object> triageAdvice(Settings settings, LlmClient llm, TraceRecorder trace,
                                                   String target, Map<String, Object> triage) {
        if (!settings.llmTriageAdvice()) {
            return Map.of("enabled", false);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("instruction", """
                Return JSON only: {"risk_level":"low|medium|high","semantic_risk_types":["..."],"review_focus":["..."],"human_attention_advice":false,"reason":"short"}.
                This is advisory. Do not override hard rules such as docs-only, draft, large PR, or explicit human_required.
                """);
        payload.put("target", target);
        payload.put("triage", compact(triage));
        return callJson(llm, trace, "triage_advice", payload, Map.of(
                "enabled", true,
                "risk_level", "unknown",
                "semantic_risk_types", List.of(),
                "review_focus", List.of(),
                "human_attention_advice", false,
                "reason", "triage advice unavailable"
        ));
    }

    public static Map<String, Object> contextScout(Settings settings, LlmClient llm, TraceRecorder trace,
                                                   String mode, Map<String, Object> triage, Map<String, Object> analysis) {
        if (!settings.llmContextScout()) {
            return fallbackContextScout(mode, triage, analysis, "disabled");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("instruction", """
                Return JSON only: {"keywords":["..."],"symbols":["..."],"paths":["..."],"test_targets":["..."],"config_targets":["..."],"reason":"short"}.
                You are not ranking context. Produce retrieval intents for local hybrid retrieval/RRF.
                """);
        payload.put("mode", mode);
        payload.put("triage", compact(triage));
        payload.put("risk_model", analysis.getOrDefault("risk_model", Map.of()));
        payload.put("regression_test_reasoning", analysis.getOrDefault("regression_test_reasoning", Map.of()));
        payload.put("risk_probes", analysis.getOrDefault("risk_probes", List.of()));
        payload.put("repo_manifest", limited(analysis.getOrDefault("repo_manifest", List.of()), 80));
        Map<String, Object> result = callJson(llm, trace, "context_scout", payload, fallbackContextScout(mode, triage, analysis, "llm_failed"));
        return normalizeScout(result, mode, triage, analysis);
    }

    public static Map<String, Object> riskRefinement(Settings settings, LlmClient llm, TraceRecorder trace,
                                                     Map<String, Object> triage, Map<String, Object> analysis,
                                                     Map<String, Object> baseRisk) {
        if (!settings.llmRiskRefinement()) {
            return baseRisk;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("instruction", """
                Return JSON only: {"risk_level":"low|medium|high","risk_types":["..."],"review_focus":["..."],"semantic_risk_reasons":["..."],"focus_files":["..."]}.
                Preserve concrete base risk signals and only refine/add semantic risk. Do not remove hard risk types without reason.
                """);
        payload.put("triage", compact(triage));
        payload.put("base_risk_model", baseRisk);
        payload.put("context_summary", contextSummary(analysis));
        Map<String, Object> advisory = callJson(llm, trace, "risk_refinement", payload, Map.of());
        if (advisory.isEmpty()) {
            return baseRisk;
        }
        Map<String, Object> merged = new LinkedHashMap<>(baseRisk);
        mergeStringList(merged, "risk_types", advisory.get("risk_types"));
        mergeStringList(merged, "review_focus", advisory.get("review_focus"));
        mergeStringList(merged, "semantic_risk_reasons", advisory.get("semantic_risk_reasons"));
        mergeStringList(merged, "focus_files", advisory.get("focus_files"));
        String level = string(advisory.get("risk_level"));
        if (List.of("low", "medium", "high").contains(level)) {
            merged.put("llm_risk_level", level);
            merged.put("risk_level", maxRisk(String.valueOf(baseRisk.getOrDefault("risk_level", "low")), level));
        }
        merged.put("llm_refined", true);
        return merged;
    }

    public static Map<String, Object> testGapReasoning(Settings settings, LlmClient llm, TraceRecorder trace,
                                                       Map<String, Object> triage, Map<String, Object> analysis,
                                                       Map<String, Object> baseReasoning) {
        if (!settings.llmTestReasoning()) {
            return baseReasoning;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("instruction", """
                Return JSON only: {"likely_test_gap":false,"missing_cases":["..."],"insufficient_existing_tests_reason":"short","files_needing_test_consideration":["..."]}.
                Only flag concrete behavior/test gaps supported by changed behavior or risk probes.
                """);
        payload.put("triage", compact(triage));
        payload.put("base_test_reasoning", baseReasoning);
        payload.put("risk_model", analysis.getOrDefault("risk_model", Map.of()));
        payload.put("related_tests", limited(analysis.getOrDefault("related_tests", List.of()), 30));
        payload.put("risk_probes", limited(analysis.getOrDefault("risk_probes", List.of()), 30));
        Map<String, Object> advisory = callJson(llm, trace, "test_gap_reasoning", payload, Map.of());
        if (advisory.isEmpty()) {
            return baseReasoning;
        }
        Map<String, Object> merged = new LinkedHashMap<>(baseReasoning);
        if (advisory.get("likely_test_gap") instanceof Boolean b) {
            merged.put("llm_likely_test_gap", b);
            merged.put("likely_test_gap", Boolean.TRUE.equals(baseReasoning.get("likely_test_gap")) || b);
        }
        mergeStringList(merged, "missing_cases", advisory.get("missing_cases"));
        mergeStringList(merged, "files_needing_test_consideration", advisory.get("files_needing_test_consideration"));
        if (advisory.get("insufficient_existing_tests_reason") != null) {
            merged.put("insufficient_existing_tests_reason", advisory.get("insufficient_existing_tests_reason"));
        }
        merged.put("llm_refined", true);
        return merged;
    }

    public static Map<String, Object> actPlan(Settings settings, LlmClient llm, TraceRecorder trace,
                                              String repo, String target, Map<String, Object> triage,
                                              Map<String, Object> analysis, ReviewResult reviewResult) {
        if (!settings.llmActPlanning()) {
            return Map.of("enabled", false);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("instruction", """
                Return JSON only: {"comment":true,"update_memory":true,"create_fix_pr":false,"generate_tests":false,"reasons":["..."]}.
                This is advisory only. The deterministic Act node enforces permissions, dry-run, and write-tool policy.
                """);
        payload.put("repo", repo);
        payload.put("target", target);
        payload.put("triage", compact(triage));
        payload.put("analysis_summary", contextSummary(analysis));
        payload.put("issues", reviewResult.issues);
        return callJson(llm, trace, "act_plan", payload, Map.of("enabled", true, "comment", reviewResult.shouldComment,
                "update_memory", reviewResult.shouldUpdateMemory, "create_fix_pr", reviewResult.shouldCreateFixPr,
                "generate_tests", false, "reasons", List.of("act plan unavailable")));
    }

    private static Map<String, Object> callJson(LlmClient llm, TraceRecorder trace, String phase,
                                                Map<String, Object> payload, Map<String, Object> fallback) {
        List<ChatMessage> messages = List.of(
                new ChatMessage("system", "You are a bounded advisory node in a code review agent. Return valid JSON only."),
                new ChatMessage("user", Jsons.stringify(payload))
        );
        trace.record("llm_request", Map.of(
                "phase", phase,
                "iteration", 1,
                "messages", messages,
                "tools", List.of(),
                "temperature", 0.0
        ));
        try {
            Map<String, Object> response = llm.chatJson(messages, List.of(), 0.0);
            LlmTelemetry.recordResponse(trace, phase, 1, response);
            Map<String, Object> parsed = Jsons.parseMap(OpenAiCompatibleClient.assistantMessage(response).content);
            Map<String, Object> out = new LinkedHashMap<>(parsed);
            out.putIfAbsent("enabled", true);
            trace.record(phase, Map.of("result", out));
            return out;
        } catch (Exception e) {
            trace.record("warning", Map.of("phase", phase, "warning", "LLM advisory node failed; using fallback", "error", safeMessage(e)));
            trace.record(phase, Map.of("result", fallback, "fallback", true));
            return fallback;
        }
    }

    private static Map<String, Object> fallbackContextScout(String mode, Map<String, Object> triage,
                                                            Map<String, Object> analysis, String reason) {
        List<String> keywords = new ArrayList<>();
        List<String> paths = new ArrayList<>();
        for (Map<String, Object> file : listOfMaps(triage.get("changed_files"))) {
            String path = String.valueOf(file.getOrDefault("filename", file.getOrDefault("path", "")));
            paths.add(path);
            keywords.addAll(tokens(path));
            keywords.addAll(tokens(String.valueOf(file.getOrDefault("patch", ""))));
        }
        for (Object focus : listOfObjects(mapOf(analysis.get("risk_model")).get("review_focus"))) {
            keywords.addAll(tokens(String.valueOf(focus)));
        }
        return Map.of(
                "enabled", true,
                "mode", mode,
                "keywords", keywords.stream().distinct().limit(40).toList(),
                "symbols", List.of(),
                "paths", paths.stream().distinct().limit(40).toList(),
                "test_targets", List.of(),
                "config_targets", List.of(),
                "reason", reason
        );
    }

    private static Map<String, Object> normalizeScout(Map<String, Object> raw, String mode,
                                                      Map<String, Object> triage, Map<String, Object> analysis) {
        Map<String, Object> fallback = fallbackContextScout(mode, triage, analysis, "fallback_terms");
        Map<String, Object> out = new LinkedHashMap<>(raw);
        out.put("mode", mode);
        out.put("keywords", mergeLimitedStrings(raw.get("keywords"), fallback.get("keywords"), 80));
        out.put("symbols", mergeLimitedStrings(raw.get("symbols"), fallback.get("symbols"), 40));
        out.put("paths", mergeLimitedStrings(raw.get("paths"), fallback.get("paths"), 80));
        out.put("test_targets", mergeLimitedStrings(raw.get("test_targets"), fallback.get("test_targets"), 40));
        out.put("config_targets", mergeLimitedStrings(raw.get("config_targets"), fallback.get("config_targets"), 40));
        out.putIfAbsent("reason", "context scout");
        return out;
    }

    private static Map<String, Object> contextSummary(Map<String, Object> analysis) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("risk_model", analysis.getOrDefault("risk_model", Map.of()));
        out.put("regression_test_reasoning", analysis.getOrDefault("regression_test_reasoning", Map.of()));
        Object context = analysis.get("context_engine");
        if (context instanceof Map<?, ?> map) {
            out.put("context_summary", map.get("context_summary"));
        }
        out.put("lsp_status", mapOf(analysis.get("lsp_context")).getOrDefault("status", "unknown"));
        out.put("static_checks_count", listOfMaps(analysis.get("static_checks")).size());
        return out;
    }

    private static Map<String, Object> compact(Map<String, Object> input) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : input.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof List<?> list) {
                out.put(entry.getKey(), list.stream().limit(30).toList());
            } else if (value instanceof String text) {
                out.put(entry.getKey(), text.length() > 4000 ? text.substring(0, 4000) + "\n...[truncated]" : text);
            } else {
                out.put(entry.getKey(), value);
            }
        }
        return out;
    }

    private static Object limited(Object value, int limit) {
        if (value instanceof List<?> list) {
            return list.stream().limit(limit).toList();
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> listOfMaps(Object value) {
        if (value instanceof List<?> list) {
            return list.stream().filter(Map.class::isInstance).map(item -> (Map<String, Object>) item).toList();
        }
        return List.of();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapOf(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private static List<Object> listOfObjects(Object value) {
        return value instanceof List<?> list ? new ArrayList<>(list) : List.of();
    }

    private static List<String> mergeLimitedStrings(Object first, Object second, int limit) {
        List<String> out = new ArrayList<>();
        addStrings(out, first);
        addStrings(out, second);
        return out.stream().filter(s -> !s.isBlank()).distinct().limit(limit).toList();
    }

    private static void mergeStringList(Map<String, Object> target, String key, Object value) {
        target.put(key, mergeLimitedStrings(target.get(key), value, 80));
    }

    private static void addStrings(List<String> out, Object raw) {
        if (raw instanceof List<?> list) {
            list.stream().map(String::valueOf).forEach(out::add);
        } else if (raw instanceof String text) {
            out.add(text);
        }
    }

    private static List<String> tokens(String text) {
        String lower = text == null ? "" : text.toLowerCase();
        return java.util.Arrays.stream(lower.split("[^a-zA-Z0-9_.$/-]+"))
                .filter(token -> token.length() >= 3)
                .filter(token -> !Set.of("public", "private", "return", "class", "const", "function", "import", "from", "this", "that", "with").contains(token))
                .limit(60)
                .toList();
    }

    private static String maxRisk(String a, String b) {
        return riskRank(b) < riskRank(a) ? b : a;
    }

    private static int riskRank(String risk) {
        return switch (risk == null ? "" : risk.toLowerCase()) {
            case "high" -> 0;
            case "medium" -> 1;
            default -> 2;
        };
    }

    private static String string(Object value) {
        return value == null ? "" : String.valueOf(value).toLowerCase();
    }

    private static String safeMessage(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }
}
