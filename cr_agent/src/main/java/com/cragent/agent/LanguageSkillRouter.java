package com.cragent.agent;

import com.cragent.config.Settings;
import com.cragent.llm.LlmClient;
import com.cragent.llm.LlmTelemetry;
import com.cragent.llm.OpenAiCompatibleClient;
import com.cragent.model.ChatMessage;
import com.cragent.skills.SkillDescriptor;
import com.cragent.skills.SkillLoader;
import com.cragent.trace.TraceRecorder;
import com.cragent.util.Jsons;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class LanguageSkillRouter {
    private final Settings settings;
    private final LlmClient llm;
    private final TraceRecorder trace;
    private final SkillLoader loader;

    public LanguageSkillRouter(Settings settings, LlmClient llm, TraceRecorder trace) {
        this(settings, llm, trace, new SkillLoader());
    }

    public LanguageSkillRouter(Settings settings, LlmClient llm, TraceRecorder trace, SkillLoader loader) {
        this.settings = settings;
        this.llm = llm;
        this.trace = trace;
        this.loader = loader;
    }

    public Selection selectForDiff(Map<String, Object> triage, Map<String, Object> analysis) {
        return select("diff", filesFromTriage(triage), analysis, analysis);
    }

    public Selection selectForRepoAudit(List<Map<String, Object>> manifest, Map<String, Object> risk, Map<String, Object> sharedAnalysis) {
        Map<String, Object> context = new LinkedHashMap<>(sharedAnalysis);
        context.put("risk_model", risk);
        return select("repo_audit", filesFromManifest(manifest), context, sharedAnalysis);
    }

    public Selection selectForRepoBatch(List<RepoAuditIndexer.AuditSlice> batch, Map<String, Object> risk,
                                       Map<String, Object> lspContext, Map<String, Object> sharedAnalysis) {
        Map<String, Object> context = new LinkedHashMap<>(sharedAnalysis);
        context.put("risk_model", risk);
        context.put("lsp_context", lspContext);
        return select("repo_audit", filesFromSlices(batch), context, sharedAnalysis);
    }

    private Selection select(String mode, List<Map<String, Object>> files, Map<String, Object> context, Map<String, Object> analysisToUpdate) {
        if (!settings.languageSkillsEnabled()) {
            return Selection.empty();
        }
        List<SkillDescriptor> catalog = loader.languageSkillCatalog();
        trace.record("language_skill_catalog", Map.of(
                "mode", mode,
                "descriptors", catalog.stream().map(SkillDescriptor::toMap).toList()
        ));
        List<Map<String, Object>> selected = tryLlmSelect(mode, files, context, catalog);
        if (selected.isEmpty()) {
            selected = fallbackSelect(mode, files, context, catalog);
        }
        selected = selected.stream()
                .filter(item -> item.get("name") != null)
                .collect(ArrayList::new, (list, item) -> {
                    String name = String.valueOf(item.get("name"));
                    boolean seen = list.stream().anyMatch(existing -> name.equals(existing.get("name")));
                    if (!seen) {
                        list.add(item);
                    }
                }, ArrayList::addAll);
        int max = Math.max(1, settings.languageSkillMaxSelected());
        if (selected.size() > max) {
            selected = selected.stream().limit(max).toList();
        }
        String prompt = loader.loadSelectedSkills(selected.stream().map(item -> String.valueOf(item.get("name"))).toList());
        Map<String, Object> metadata = Map.of("mode", mode, "selected_skills", selected);
        trace.record("language_skill_selection", metadata);
        if (analysisToUpdate != null) {
            analysisToUpdate.put("language_skill_selection", selected);
        }
        return new Selection(selected, prompt);
    }

    private List<Map<String, Object>> tryLlmSelect(String mode, List<Map<String, Object>> files, Map<String, Object> context,
                                                   List<SkillDescriptor> catalog) {
        if (llm == null || catalog.isEmpty()) {
            return List.of();
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("instruction", """
                Select only the language-specific code review skills needed for this target.
                Return exactly one JSON object: {"selected_skills":[{"name":"code-review-lang-...","reason":"short reason","matched_languages":["..."],"matched_files":["..."]}]}.
                Use only provided descriptors. Do not select unrelated languages.
                """);
        payload.put("skill_descriptors", catalog.stream().map(SkillDescriptor::toMap).toList());
        payload.put("mode", mode);
        payload.put("files", files);
        payload.put("risk_model", context.getOrDefault("risk_model", Map.of()));
        payload.put("risk_probes", context.getOrDefault("risk_probes", List.of()));
        payload.put("lsp_context", compact(context.get("lsp_context")));
        payload.put("static_checks", compact(context.get("static_checks")));
        List<ChatMessage> messages = List.of(
                new ChatMessage("system", "You are a language skill router. You expose only skill names selected from the catalog."),
                new ChatMessage("user", Jsons.stringify(payload))
        );
        trace.record("llm_request", Map.of(
                "phase", "LANGUAGE_SKILL_ROUTER",
                "mode", mode,
                "messages", messages,
                "tools", List.of(),
                "temperature", 0.0
        ));
        try {
            Map<String, Object> response = llm.chatJson(messages, List.of(), 0.0);
            LlmTelemetry.recordResponse(trace, "LANGUAGE_SKILL_ROUTER", 1, response, Map.of("mode", mode));
            Map<String, Object> parsed = Jsons.parseMap(OpenAiCompatibleClient.assistantMessage(response).content);
            return normalizeSelected(parsed.get("selected_skills"), catalog, files);
        } catch (Exception e) {
            trace.record("warning", Map.of("phase", "LANGUAGE_SKILL_ROUTER", "mode", mode, "error", safeMessage(e)));
            return List.of();
        }
    }

    private List<Map<String, Object>> fallbackSelect(String mode, List<Map<String, Object>> files, Map<String, Object> context,
                                                     List<SkillDescriptor> catalog) {
        Set<String> selectedNames = new LinkedHashSet<>();
        Map<String, Set<String>> matchedFiles = new LinkedHashMap<>();
        Map<String, Set<String>> matchedLanguages = new LinkedHashMap<>();
        for (Map<String, Object> file : files) {
            String path = String.valueOf(file.getOrDefault("path", file.getOrDefault("filename", "")));
            String language = String.valueOf(file.getOrDefault("language", languageForPath(path)));
            for (SkillDescriptor descriptor : catalog) {
                if (!descriptor.modes().isEmpty() && !descriptor.modes().contains(mode)) {
                    continue;
                }
                if (matches(descriptor, language, path, context)) {
                    selectedNames.add(descriptor.name());
                    matchedFiles.computeIfAbsent(descriptor.name(), ignored -> new LinkedHashSet<>()).add(path);
                    matchedLanguages.computeIfAbsent(descriptor.name(), ignored -> new LinkedHashSet<>()).add(language);
                }
            }
        }
        return selectedNames.stream()
                .sorted(Comparator.comparingInt(name -> priority(name, context)))
                .map(name -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("name", name);
                    item.put("reason", "Fallback selection matched language/path/risk triggers.");
                    item.put("matched_languages", new ArrayList<>(matchedLanguages.getOrDefault(name, Set.of())));
                    item.put("matched_files", new ArrayList<>(matchedFiles.getOrDefault(name, Set.of())).stream().limit(12).toList());
                    return item;
                })
                .toList();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> normalizeSelected(Object raw, List<SkillDescriptor> catalog, List<Map<String, Object>> files) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        Set<String> valid = catalog.stream().map(SkillDescriptor::name).collect(LinkedHashSet::new, Set::add, Set::addAll);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object entry : list) {
            if (!(entry instanceof Map<?, ?> map)) {
                continue;
            }
            String name = String.valueOf(map.get("name"));
            if (!valid.contains(name)) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", name);
            Object reason = map.get("reason");
            item.put("reason", reason == null ? "Selected by language skill router." : String.valueOf(reason));
            item.put("matched_languages", stringList(map.get("matched_languages")));
            item.put("matched_files", stringList(map.get("matched_files")).isEmpty()
                    ? files.stream().map(file -> String.valueOf(file.getOrDefault("path", file.getOrDefault("filename", "")))).limit(12).toList()
                    : stringList(map.get("matched_files")).stream().limit(12).toList());
            out.add(item);
        }
        return out;
    }

    private static List<Map<String, Object>> filesFromTriage(Map<String, Object> triage) {
        List<Map<String, Object>> out = new ArrayList<>();
        Object raw = triage.get("changed_files");
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    Map<String, Object> file = new LinkedHashMap<>();
                    Object filename = map.get("filename");
                    Object path = filename == null ? map.get("path") : filename;
                    file.put("path", path == null ? "" : String.valueOf(path));
                    file.put("language", languageForPath(String.valueOf(file.get("path"))));
                    Object patch = map.get("patch");
                    file.put("patch", patch == null ? "" : String.valueOf(patch));
                    out.add(file);
                }
            }
        }
        return out;
    }

    private static List<Map<String, Object>> filesFromManifest(List<Map<String, Object>> manifest) {
        return manifest.stream().map(item -> {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("path", item.getOrDefault("path", ""));
            out.put("language", item.getOrDefault("language", languageForPath(String.valueOf(item.getOrDefault("path", "")))));
            out.put("sensitive", item.getOrDefault("sensitive", false));
            out.put("config", item.getOrDefault("config", false));
            return out;
        }).toList();
    }

    private static List<Map<String, Object>> filesFromSlices(List<RepoAuditIndexer.AuditSlice> batch) {
        return batch.stream().map(slice -> Map.<String, Object>of(
                "path", slice.path(),
                "language", languageForPath(slice.path()),
                "start_line", slice.startLine(),
                "end_line", slice.endLine()
        )).toList();
    }

    private static boolean matches(SkillDescriptor descriptor, String language, String path, Map<String, Object> context) {
        String lowerPath = path.toLowerCase(Locale.ROOT);
        String lowerLanguage = language.toLowerCase(Locale.ROOT);
        if (descriptor.languages().stream().map(s -> s.toLowerCase(Locale.ROOT)).anyMatch(s -> s.equals(lowerLanguage))) {
            return true;
        }
        if (descriptor.filePatterns().stream().map(s -> s.toLowerCase(Locale.ROOT)).anyMatch(pattern -> pathMatches(lowerPath, pattern))) {
            return true;
        }
        String contextText = Jsons.stringify(Map.of(
                "risk_model", context.getOrDefault("risk_model", Map.of()),
                "risk_probes", context.getOrDefault("risk_probes", List.of())
        )).toLowerCase(Locale.ROOT);
        return descriptor.riskTriggers().stream().map(s -> s.toLowerCase(Locale.ROOT)).anyMatch(contextText::contains);
    }

    private static boolean pathMatches(String lowerPath, String pattern) {
        String p = pattern.toLowerCase(Locale.ROOT);
        if (p.startsWith("*")) {
            return lowerPath.endsWith(p.substring(1));
        }
        if (p.endsWith("*")) {
            return lowerPath.startsWith(p.substring(0, p.length() - 1));
        }
        return lowerPath.contains(p);
    }

    private static String languageForPath(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".java")) return "java";
        if (lower.endsWith(".kt") || lower.endsWith(".kts")) return "kotlin";
        if (lower.endsWith(".ts") || lower.endsWith(".tsx")) return "typescript";
        if (lower.endsWith(".js") || lower.endsWith(".jsx") || lower.endsWith(".mjs") || lower.endsWith(".cjs")) return "javascript";
        if (lower.endsWith(".go")) return "go";
        if (lower.endsWith(".py")) return "python";
        if (lower.endsWith(".rs")) return "rust";
        if (lower.endsWith(".cs")) return "csharp";
        if (lower.endsWith(".php")) return "php";
        if (lower.endsWith(".rb") || lower.endsWith("gemfile") || lower.endsWith(".gemspec")) return "ruby";
        if (lower.endsWith(".swift")) return "swift";
        if (lower.endsWith(".m") || lower.endsWith(".mm")) return "objective-c";
        if (lower.endsWith(".c") || lower.endsWith(".h")) return "c";
        if (lower.endsWith(".cc") || lower.endsWith(".cpp") || lower.endsWith(".cxx") || lower.endsWith(".hpp") || lower.endsWith(".hh") || lower.endsWith(".hxx")) return "cpp";
        if (lower.endsWith(".yml") || lower.endsWith(".yaml") || lower.endsWith(".json") || lower.endsWith(".toml")
                || lower.endsWith(".xml") || lower.endsWith("dockerfile") || lower.endsWith("makefile")
                || lower.endsWith("cmakelists.txt") || lower.endsWith("package.swift") || lower.endsWith("podfile")) return "config";
        return "text";
    }

    private static int priority(String skillName, Map<String, Object> context) {
        String text = Jsons.stringify(context).toLowerCase(Locale.ROOT);
        if (skillName.contains("config") && text.contains("dependency/build")) return 0;
        if (skillName.contains("c-cpp") && text.contains("native-memory")) return 0;
        if (skillName.contains("swift") && text.contains("mobile")) return 0;
        if (skillName.contains("ruby") && text.contains("framework-security")) return 0;
        return 1;
    }

    private static Object compact(Object value) {
        String text = Jsons.stringify(value == null ? Map.of() : value);
        return text.length() <= 8000 ? value : text.substring(0, 8000) + "\n...[truncated]";
    }

    private static List<String> stringList(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (Object item : list) {
            if (item != null && !String.valueOf(item).isBlank()) {
                out.add(String.valueOf(item));
            }
        }
        return out;
    }

    private static String safeMessage(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    public record Selection(List<Map<String, Object>> selectedSkills, String prompt) {
        public static Selection empty() {
            return new Selection(List.of(), "");
        }

        public boolean hasPrompt() {
            return prompt != null && !prompt.isBlank();
        }
    }
}
