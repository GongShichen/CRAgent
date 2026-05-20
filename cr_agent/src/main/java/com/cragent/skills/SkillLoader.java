package com.cragent.skills;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

public class SkillLoader {
    private final Path skillsDir;

    public SkillLoader() {
        this(Path.of("src/main/resources/skills"));
    }

    public SkillLoader(Path skillsDir) {
        this.skillsDir = skillsDir;
    }

    public String loadSkill(String skillName, boolean includeReferences) {
        Path skillDir = skillsDir.resolve(skillName);
        Path skillMd = skillDir.resolve("SKILL.md");
        String body;
        if (Files.exists(skillMd)) {
            body = read(skillMd);
        } else {
            body = readResource("skills/" + skillName + "/SKILL.md");
        }
        body = stripFrontmatter(body);
        if (!includeReferences) {
            return body;
        }
        List<String> refs = new ArrayList<>();
        Path refsDir = skillDir.resolve("references");
        if (Files.exists(refsDir)) {
            try (Stream<Path> stream = Files.list(refsDir)) {
                stream.filter(p -> p.getFileName().toString().endsWith(".md"))
                        .sorted(Comparator.comparing(Path::toString))
                        .forEach(p -> refs.add("===== Reference: " + p.getFileName() + " =====\n\n" + read(p)));
            } catch (IOException e) {
                throw new IllegalStateException("Unable to read references: " + refsDir, e);
            }
        }
        return refs.isEmpty() ? body : body + "\n\n" + String.join("\n\n", refs);
    }

    public String loadCombined(String... skillNames) {
        List<String> parts = new ArrayList<>();
        for (String skillName : skillNames) {
            try {
                parts.add("===== " + skillName + " =====\n\n" + loadSkill(skillName, true));
            } catch (RuntimeException ignored) {
                // Missing optional skill: keep loading the rest.
            }
        }
        return String.join("\n\n", parts);
    }

    public String loadReference(String skillName, String referenceName) {
        Path ref = skillsDir.resolve(skillName).resolve("references").resolve(referenceName);
        if (Files.exists(ref)) {
            return read(ref);
        }
        return readResource("skills/" + skillName + "/references/" + referenceName);
    }

    public List<SkillDescriptor> languageSkillCatalog() {
        List<SkillDescriptor> out = new ArrayList<>();
        if (!Files.exists(skillsDir)) {
            return out;
        }
        try (Stream<Path> stream = Files.list(skillsDir)) {
            stream.filter(Files::isDirectory)
                    .filter(path -> path.getFileName().toString().startsWith("code-review-lang-"))
                    .sorted(Comparator.comparing(Path::toString))
                    .forEach(path -> {
                        Path skillMd = path.resolve("SKILL.md");
                        if (Files.exists(skillMd)) {
                            out.add(descriptorFromSkill(read(skillMd), path.getFileName().toString()));
                        }
                    });
        } catch (IOException e) {
            throw new IllegalStateException("Unable to scan language skills: " + skillsDir, e);
        }
        return out;
    }

    public String loadSelectedSkills(List<String> skillNames) {
        List<String> parts = new ArrayList<>();
        for (String skillName : skillNames) {
            if (skillName == null || skillName.isBlank() || !skillName.startsWith("code-review-lang-")) {
                continue;
            }
            try {
                parts.add("===== " + skillName + " =====\n\n" + loadSkill(skillName, false));
            } catch (RuntimeException ignored) {
                // Missing optional language skill: keep loading the rest.
            }
        }
        return String.join("\n\n", parts);
    }

    public static String defaultPrompt() {
        SkillLoader loader = new SkillLoader();
        List<String> parts = new ArrayList<>();
        for (String skillName : List.of(
                "code-review",
                "code-review-act",
                "code-review-test-gen"
        )) {
            try {
                parts.add("===== " + skillName + " =====\n\n" + loader.loadSkill(skillName, false));
            } catch (RuntimeException ignored) {
                // Missing optional skill: keep loading the rest.
            }
        }
        for (String[] ref : List.of(
                new String[]{"code-review", "review-checklist.md"},
                new String[]{"code-review-act", "review-patterns.md"}
        )) {
            try {
                parts.add("===== Reference: " + ref[0] + "/" + ref[1] + " =====\n\n" + loader.loadReference(ref[0], ref[1]));
            } catch (RuntimeException ignored) {
                // Missing optional reference: keep loading the rest.
            }
        }
        try {
            parts.add("===== code-review-java-runtime =====\n\n" + loader.loadSkill("code-review-java-runtime", false));
        } catch (RuntimeException ignored) {
            // Missing optional runtime overlay: keep loading the rest.
        }
        String prompt = String.join("\n\n", parts);
        if (!prompt.isBlank()) {
            return prompt;
        }
        return "You are a professional code review agent. Return structured JSON.";
    }

    private static String stripFrontmatter(String text) {
        if (text.startsWith("---")) {
            int end = text.indexOf("\n---", 3);
            if (end >= 0) {
                return text.substring(end + 4).stripLeading();
            }
        }
        return text;
    }

    private static SkillDescriptor descriptorFromSkill(String text, String fallbackName) {
        Map<String, String> frontmatter = parseFrontmatter(text);
        return new SkillDescriptor(
                frontmatter.getOrDefault("name", fallbackName),
                frontmatter.getOrDefault("description", ""),
                parseList(frontmatter.get("languages")),
                parseList(frontmatter.get("file_patterns")),
                parseList(frontmatter.get("risk_triggers")),
                parseList(frontmatter.get("modes"))
        );
    }

    private static Map<String, String> parseFrontmatter(String text) {
        Map<String, String> out = new LinkedHashMap<>();
        if (!text.startsWith("---")) {
            return out;
        }
        int end = text.indexOf("\n---", 3);
        if (end < 0) {
            return out;
        }
        String[] lines = text.substring(3, end).split("\\R");
        for (String line : lines) {
            int idx = line.indexOf(':');
            if (idx <= 0) {
                continue;
            }
            String key = line.substring(0, idx).trim().toLowerCase(Locale.ROOT);
            String value = line.substring(idx + 1).trim();
            out.put(key, value);
        }
        return out;
    }

    private static List<String> parseList(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        String normalized = value.trim();
        if (normalized.startsWith("[") && normalized.endsWith("]")) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        List<String> out = new ArrayList<>();
        for (String part : normalized.split(",")) {
            String item = part.trim().replaceAll("^['\"]|['\"]$", "");
            if (!item.isBlank()) {
                out.add(item);
            }
        }
        return out;
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to read " + path, e);
        }
    }

    private static String readResource(String name) {
        URL url = SkillLoader.class.getClassLoader().getResource(name);
        if (url == null) {
            throw new IllegalArgumentException("Resource not found: " + name);
        }
        try {
            return Files.readString(Path.of(url.toURI()), StandardCharsets.UTF_8);
        } catch (IOException | URISyntaxException e) {
            throw new IllegalStateException("Unable to read resource: " + name, e);
        }
    }
}
