package com.cragent.agent;

import com.cragent.config.Settings;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class LspAnalyzer {
    private final Settings settings;

    public LspAnalyzer(Settings settings) {
        this.settings = settings;
    }

    public Map<String, Object> workspaceContext(Path repoPath, RepoAuditIndexer.AuditIndex index) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", settings.lspEnabled());
        if (!settings.lspEnabled()) {
            out.put("status", "disabled");
            return out;
        }

        List<Map<String, Object>> servers = detectServers(index.stack());
        out.put("servers", servers);

        List<Map<String, Object>> symbols = new ArrayList<>();
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        List<Map<String, Object>> errors = new ArrayList<>();

        for (ServerSpec server : relevantServers(index.stack())) {
            if (!LspServerRegistry.commandExists(server.executable())) {
                errors.add(Map.of(
                        "language", server.language(),
                        "server", server.command(),
                        "status", "missing",
                        "error", "language server command is not installed; LSP is skipped for this language in this run",
                        "install_hint", server.installHint()
                ));
                continue;
            }
            try (JsonRpcLspClient client = open(repoPath, server)) {
                List<RepoAuditIndexer.AuditFile> files = index.files().stream()
                        .filter(file -> server.matches(file.path(), file.language()))
                        .toList();
                for (RepoAuditIndexer.AuditFile file : files) {
                    client.didOpen(file.path(), server.languageId(file.language()), file.content());
                }
                client.waitForDiagnostics();
                diagnostics.put(server.language(), client.diagnosticsSnapshot());
                for (RepoAuditIndexer.AuditFile file : files) {
                    Object result = client.documentSymbol(file.path());
                    symbols.addAll(normalizeDocumentSymbols(file.path(), file.language(), result));
                }
            } catch (Exception e) {
                errors.add(Map.of("language", server.language(), "server", server.command(), "error", e.getMessage()));
            }
        }

        out.put("status", errors.isEmpty() ? "ok" : "partial");
        out.put("symbol_count", symbols.size());
        out.put("symbols_preview", symbols.stream().limit(200).toList());
        out.put("diagnostics", diagnostics);
        out.put("errors", errors);
        return out;
    }

    public List<Map<String, Object>> detectServers(Map<String, Object> stack) {
        return relevantServers(stack).stream().map(server -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("language", server.language());
            item.put("command", server.command());
            item.put("available", LspServerRegistry.commandExists(server.executable()));
            item.put("install_hint", server.installHint());
            return item;
        }).toList();
    }

    public Object documentSymbols(Path repoPath, RepoAuditIndexer.AuditIndex index, String relativePath) {
        RepoAuditIndexer.AuditFile file = file(index, relativePath);
        ServerSpec server = serverFor(file);
        try (JsonRpcLspClient client = open(repoPath, server)) {
            client.didOpen(file.path(), server.languageId(file.language()), file.content());
            return client.documentSymbol(file.path());
        }
    }

    public Object definition(Path repoPath, RepoAuditIndexer.AuditIndex index, String relativePath, int line, int character) {
        RepoAuditIndexer.AuditFile file = file(index, relativePath);
        ServerSpec server = serverFor(file);
        try (JsonRpcLspClient client = open(repoPath, server)) {
            openSameLanguageFiles(client, server, index);
            return client.definition(file.path(), line, character);
        }
    }

    public Object references(Path repoPath, RepoAuditIndexer.AuditIndex index, String relativePath, int line, int character, boolean includeDeclaration) {
        RepoAuditIndexer.AuditFile file = file(index, relativePath);
        ServerSpec server = serverFor(file);
        try (JsonRpcLspClient client = open(repoPath, server)) {
            openSameLanguageFiles(client, server, index);
            return client.references(file.path(), line, character, includeDeclaration);
        }
    }

    public Object hover(Path repoPath, RepoAuditIndexer.AuditIndex index, String relativePath, int line, int character) {
        RepoAuditIndexer.AuditFile file = file(index, relativePath);
        ServerSpec server = serverFor(file);
        try (JsonRpcLspClient client = open(repoPath, server)) {
            client.didOpen(file.path(), server.languageId(file.language()), file.content());
            return client.hover(file.path(), line, character);
        }
    }

    public Object workspaceSymbols(Path repoPath, RepoAuditIndexer.AuditIndex index, String query) {
        List<Map<String, Object>> results = new ArrayList<>();
        List<Map<String, Object>> errors = new ArrayList<>();
        for (ServerSpec server : relevantServers(index.stack())) {
            if (!LspServerRegistry.commandExists(server.executable())) {
                errors.add(Map.of("language", server.language(), "status", "missing", "error", "language server command is not installed; LSP is skipped for this language in this run", "install_hint", server.installHint()));
                continue;
            }
            try (JsonRpcLspClient client = open(repoPath, server)) {
                Object result = client.workspaceSymbol(query);
                results.add(Map.of("language", server.language(), "symbols", result == null ? List.of() : result));
            } catch (Exception e) {
                errors.add(Map.of("language", server.language(), "error", e.getMessage()));
            }
        }
        return Map.of("query", query == null ? "" : query, "results", results, "errors", errors);
    }

    public Object diagnostics(Path repoPath, RepoAuditIndexer.AuditIndex index) {
        Map<String, Object> context = workspaceContext(repoPath, index);
        return context.getOrDefault("diagnostics", Map.of());
    }

    private JsonRpcLspClient open(Path repoPath, ServerSpec server) {
        if (!settings.lspEnabled()) {
            throw new IllegalStateException("LSP is disabled by CR_AGENT_LSP_ENABLED=false");
        }
        if (!LspServerRegistry.commandExists(server.executable())) {
            throw new IllegalStateException("Missing LSP server `" + server.command() + "`. " + server.installHint());
        }
        JsonRpcLspClient client = new JsonRpcLspClient(repoPath, server.language(), server.command(), Duration.ofSeconds(settings.lspTimeoutSeconds()));
        client.start();
        return client;
    }

    private void openSameLanguageFiles(JsonRpcLspClient client, ServerSpec server, RepoAuditIndexer.AuditIndex index) {
        for (RepoAuditIndexer.AuditFile file : index.files()) {
            if (server.matches(file.path(), file.language())) {
                client.didOpen(file.path(), server.languageId(file.language()), file.content());
            }
        }
        client.waitForDiagnostics();
    }

    private RepoAuditIndexer.AuditFile file(RepoAuditIndexer.AuditIndex index, String relativePath) {
        return index.files().stream()
                .filter(candidate -> candidate.path().equals(relativePath))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("File not found in repo index: " + relativePath));
    }

    private ServerSpec serverFor(RepoAuditIndexer.AuditFile file) {
        return serverSpecs().stream()
                .filter(server -> server.matches(file.path(), file.language()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No configured LSP server for language: " + file.language()));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> normalizeDocumentSymbols(String path, String language, Object result) {
        if (!(result instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                collectSymbol(path, language, (Map<String, Object>) map, out, null);
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private void collectSymbol(String path, String language, Map<String, Object> symbol, List<Map<String, Object>> out, String parent) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("name", symbol.get("name"));
        item.put("kind", symbol.get("kind"));
        item.put("path", path);
        item.put("language", language);
        item.put("parent", parent);
        Object range = symbol.get("range");
        if (range instanceof Map<?, ?> rangeMap) {
            Object start = rangeMap.get("start");
            Object end = rangeMap.get("end");
            if (start instanceof Map<?, ?> startMap) {
                item.put("start_line", intValue(startMap.get("line")) + 1);
            }
            if (end instanceof Map<?, ?> endMap) {
                item.put("end_line", intValue(endMap.get("line")) + 1);
            }
        }
        out.add(item);
        Object children = symbol.get("children");
        if (children instanceof List<?> childList) {
            for (Object child : childList) {
                if (child instanceof Map<?, ?> childMap) {
                    collectSymbol(path, language, (Map<String, Object>) childMap, out, String.valueOf(symbol.get("name")));
                }
            }
        }
    }

    private List<ServerSpec> relevantServers(Map<String, Object> stack) {
        return serverSpecs().stream().filter(server -> hasLanguage(stack, server.language()) || server.aliases().stream().anyMatch(alias -> hasLanguage(stack, alias))).toList();
    }

    public static List<ServerSpec> supportedServers() {
        return List.of(
                new ServerSpec("java", List.of("kotlin"), "jdtls", "jdtls", List.of(".java", ".kt"), "Install Eclipse JDT LS and ensure `jdtls` is on PATH."),
                new ServerSpec("typescript", List.of("javascript"), "typescript-language-server --stdio", "typescript-language-server", List.of(".ts", ".tsx", ".js", ".jsx", ".mjs", ".cjs"), "npm install -g typescript typescript-language-server"),
                new ServerSpec("python", List.of(), "pyright-langserver --stdio", "pyright-langserver", List.of(".py"), "npm install -g pyright"),
                new ServerSpec("go", List.of(), "gopls", "gopls", List.of(".go"), "go install golang.org/x/tools/gopls@latest"),
                new ServerSpec("rust", List.of(), "rust-analyzer", "rust-analyzer", List.of(".rs"), "Install rust-analyzer and ensure it is on PATH.")
        );
    }

    private List<ServerSpec> serverSpecs() {
        return supportedServers();
    }

    @SuppressWarnings("unchecked")
    private static boolean hasLanguage(Map<String, Object> stack, String language) {
        Object languages = stack.get("languages");
        if (languages instanceof Map<?, ?> map && map.containsKey(language)) {
            return true;
        }
        return stack.containsKey(language);
    }

    private static int intValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Exception e) {
            return 0;
        }
    }

    public record ServerSpec(String language, List<String> aliases, String command, String executable, List<String> extensions, String installHint) {
        public boolean matches(String path, String detectedLanguage) {
            String lower = path.toLowerCase(Locale.ROOT);
            return language.equals(detectedLanguage) || aliases.contains(detectedLanguage) || extensions.stream().anyMatch(lower::endsWith);
        }

        public String languageId(String detectedLanguage) {
            if ("javascript".equals(detectedLanguage) || pathLikeJs(detectedLanguage)) {
                return "javascript";
            }
            return switch (detectedLanguage) {
                case "typescript" -> "typescript";
                case "kotlin" -> "kotlin";
                default -> language;
            };
        }

        private boolean pathLikeJs(String value) {
            return "jsx".equals(value) || "mjs".equals(value) || "cjs".equals(value);
        }
    }
}
