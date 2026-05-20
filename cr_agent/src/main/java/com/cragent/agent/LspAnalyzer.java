package com.cragent.agent;

import com.cragent.config.Settings;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
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

    public Object capabilities(Path repoPath, RepoAuditIndexer.AuditIndex index) {
        return Map.of(
                "enabled", settings.lspEnabled(),
                "servers", detectServers(index.stack()),
                "tools", List.of(
                        "lsp_detect_servers",
                        "lsp_workspace_symbols",
                        "lsp_document_symbols",
                        "lsp_definition",
                        "lsp_references",
                        "lsp_hover",
                        "lsp_diagnostics",
                        "lsp_symbol_at_position",
                        "lsp_changed_symbols",
                        "lsp_call_graph",
                        "lsp_related_tests_by_symbol",
                        "lsp_evidence_bundle"
                ),
                "notes", "All LSP tools are read-only. Missing servers skip LSP for this task instead of failing review."
        );
    }

    public Object symbolAtPosition(Path repoPath, RepoAuditIndexer.AuditIndex index, String relativePath, int line) {
        RepoAuditIndexer.AuditFile file = file(index, relativePath);
        List<Map<String, Object>> symbols = documentSymbolMaps(repoPath, index, file);
        Map<String, Object> nearest = nearestSymbol(symbols, line);
        return Map.of(
                "path", relativePath,
                "line", line,
                "symbol", nearest == null ? Map.of() : nearest,
                "symbols", symbols
        );
    }

    public Object changedSymbols(Path repoPath, RepoAuditIndexer.AuditIndex index, List<Map<String, Object>> changedFiles) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> changed : changedFiles) {
            String path = String.valueOf(changed.getOrDefault("filename", changed.getOrDefault("path", "")));
            if (path.isBlank()) {
                continue;
            }
            RepoAuditIndexer.AuditFile file = maybeFile(index, path);
            if (file == null) {
                continue;
            }
            List<Integer> lines = changedLines(changed);
            List<Map<String, Object>> symbols = documentSymbolMaps(repoPath, index, file);
            List<Map<String, Object>> matched = lines.stream()
                    .map(line -> nearestSymbol(symbols, line))
                    .filter(symbol -> symbol != null && !symbol.isEmpty())
                    .distinct()
                    .toList();
            out.add(Map.of(
                    "path", path,
                    "language", file.language(),
                    "changed_lines", lines,
                    "symbols", matched
            ));
        }
        return Map.of("changed_symbols", out);
    }

    public Object callGraph(Path repoPath, RepoAuditIndexer.AuditIndex index, String relativePath, int line, int character) {
        RepoAuditIndexer.AuditFile file = file(index, relativePath);
        Map<String, Object> symbol = nearestSymbol(documentSymbolMaps(repoPath, index, file), line);
        Object hover = safe(() -> hover(repoPath, index, relativePath, line, character));
        Object definition = safe(() -> definition(repoPath, index, relativePath, line, character));
        Object references = safe(() -> references(repoPath, index, relativePath, line, character, false));
        return Map.of(
                "path", relativePath,
                "line", line,
                "character", character,
                "symbol", symbol == null ? Map.of() : symbol,
                "hover", hover,
                "definition", definition,
                "references", references,
                "summary", Map.of(
                        "reference_count", countList(references),
                        "definition_count", countList(definition)
                )
        );
    }

    public Object relatedTestsBySymbol(Path repoPath, RepoAuditIndexer.AuditIndex index, String relativePath, int line, int character) {
        RepoAuditIndexer.AuditFile file = file(index, relativePath);
        Map<String, Object> symbol = nearestSymbol(documentSymbolMaps(repoPath, index, file), line);
        String symbolName = symbol == null ? stem(relativePath) : String.valueOf(symbol.getOrDefault("name", stem(relativePath)));
        String sourceStem = stem(relativePath).toLowerCase(Locale.ROOT);
        String symbolLower = symbolName.toLowerCase(Locale.ROOT);
        List<Map<String, Object>> tests = index.files().stream()
                .filter(RepoAuditIndexer.AuditFile::test)
                .filter(test -> {
                    String path = test.path().toLowerCase(Locale.ROOT);
                    String content = test.content().toLowerCase(Locale.ROOT);
                    return path.contains(sourceStem) || content.contains(symbolLower) || content.contains(sourceStem);
                })
                .limit(20)
                .map(test -> Map.<String, Object>of(
                        "path", test.path(),
                        "language", test.language(),
                        "lines", test.lines(),
                        "match_reason", "test path/content references source stem or symbol name"
                ))
                .toList();
        return Map.of(
                "path", relativePath,
                "symbol", symbol == null ? Map.of("name", symbolName) : symbol,
                "related_tests", tests,
                "count", tests.size()
        );
    }

    public Object evidenceBundle(Path repoPath, RepoAuditIndexer.AuditIndex index, String relativePath, int line, int character) {
        RepoAuditIndexer.AuditFile file = file(index, relativePath);
        Map<String, Object> symbol = nearestSymbol(documentSymbolMaps(repoPath, index, file), line);
        Object hover = safe(() -> hover(repoPath, index, relativePath, line, character));
        Object definition = safe(() -> definition(repoPath, index, relativePath, line, character));
        Object references = safe(() -> references(repoPath, index, relativePath, line, character, false));
        Object tests = relatedTestsBySymbol(repoPath, index, relativePath, line, character);
        return Map.of(
                "path", relativePath,
                "language", file.language(),
                "line", line,
                "character", character,
                "symbol", symbol == null ? Map.of() : symbol,
                "source_excerpt", excerpt(file.content(), line, 6),
                "hover", hover,
                "definition", definition,
                "references", references,
                "related_tests", tests
        );
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

    private RepoAuditIndexer.AuditFile maybeFile(RepoAuditIndexer.AuditIndex index, String relativePath) {
        return index.files().stream()
                .filter(candidate -> candidate.path().equals(relativePath))
                .findFirst()
                .orElse(null);
    }

    private List<Map<String, Object>> documentSymbolMaps(Path repoPath, RepoAuditIndexer.AuditIndex index, RepoAuditIndexer.AuditFile file) {
        ServerSpec server = serverFor(file);
        try (JsonRpcLspClient client = open(repoPath, server)) {
            openSameLanguageFiles(client, server, index);
            Object result = client.documentSymbol(file.path());
            return normalizeDocumentSymbols(file.path(), file.language(), result);
        }
    }

    private static Map<String, Object> nearestSymbol(List<Map<String, Object>> symbols, int line) {
        return symbols.stream()
                .filter(symbol -> intValue(symbol.get("start_line")) <= line && intValue(symbol.get("end_line")) >= line)
                .max(Comparator.comparingInt(symbol -> intValue(symbol.get("start_line"))))
                .orElseGet(() -> symbols.stream()
                        .filter(symbol -> intValue(symbol.get("start_line")) <= line)
                        .max(Comparator.comparingInt(symbol -> intValue(symbol.get("start_line"))))
                        .orElse(null));
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
                new ServerSpec("java", List.of(), "jdtls", "jdtls", List.of(".java"), "Install Eclipse JDT LS and ensure `jdtls` is on PATH."),
                new ServerSpec("kotlin", List.of(), "kotlin-language-server", "kotlin-language-server", List.of(".kt", ".kts"), "Install kotlin-language-server and ensure `kotlin-language-server` is on PATH."),
                new ServerSpec("typescript", List.of("javascript"), "typescript-language-server --stdio", "typescript-language-server", List.of(".ts", ".tsx", ".js", ".jsx", ".mjs", ".cjs"), "npm install -g typescript typescript-language-server"),
                new ServerSpec("python", List.of(), "pyright-langserver --stdio", "pyright-langserver", List.of(".py"), "npm install -g pyright"),
                new ServerSpec("go", List.of(), "gopls", "gopls", List.of(".go"), "go install golang.org/x/tools/gopls@latest"),
                new ServerSpec("rust", List.of(), "rust-analyzer", "rust-analyzer", List.of(".rs"), "Install rust-analyzer and ensure it is on PATH."),
                new ServerSpec("csharp", List.of(), "csharp-ls", "csharp-ls", List.of(".cs"), "dotnet tool install --global csharp-ls"),
                new ServerSpec("php", List.of(), "intelephense --stdio", "intelephense", List.of(".php"), "npm install -g intelephense"),
                new ServerSpec("swift", List.of(), "sourcekit-lsp", "sourcekit-lsp", List.of(".swift"), "Install Xcode command line tools or Swift toolchain and ensure `sourcekit-lsp` is on PATH."),
                new ServerSpec("cpp", List.of("c", "c-header", "objective-c"), "clangd", "clangd", List.of(".c", ".h", ".cc", ".cpp", ".cxx", ".hh", ".hpp", ".hxx", ".m", ".mm"), "Install LLVM clangd and ensure `clangd` is on PATH."),
                new ServerSpec("ruby", List.of(), "ruby-lsp", "ruby-lsp", List.of(".rb", ".gemspec"), "gem install ruby-lsp"),
                new ServerSpec("yaml", List.of("config"), "yaml-language-server --stdio", "yaml-language-server", List.of(".yml", ".yaml"), "npm install -g yaml-language-server"),
                new ServerSpec("json", List.of(), "vscode-json-languageserver --stdio", "vscode-json-languageserver", List.of(".json"), "npm install -g vscode-langservers-extracted"),
                new ServerSpec("dockerfile", List.of(), "docker-langserver --stdio", "docker-langserver", List.of("Dockerfile", ".dockerfile"), "npm install -g dockerfile-language-server-nodejs")
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
                case "csharp" -> "csharp";
                case "php" -> "php";
                case "c" -> "c";
                case "c-header" -> "c";
                case "cpp" -> "cpp";
                case "objective-c" -> "objective-c";
                case "swift" -> "swift";
                case "ruby" -> "ruby";
                case "yaml" -> "yaml";
                case "json" -> "json";
                case "dockerfile" -> "dockerfile";
                default -> language;
            };
        }

        private boolean pathLikeJs(String value) {
            return "jsx".equals(value) || "mjs".equals(value) || "cjs".equals(value);
        }
    }

    @FunctionalInterface
    private interface LspSupplier {
        Object get();
    }

    private static Object safe(LspSupplier supplier) {
        try {
            Object value = supplier.get();
            return value == null ? List.of() : value;
        } catch (Exception e) {
            return Map.of("error", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    private static int countList(Object value) {
        return value instanceof List<?> list ? list.size() : 0;
    }

    @SuppressWarnings("unchecked")
    private static List<Integer> changedLines(Map<String, Object> changed) {
        Object explicit = changed.get("changed_lines");
        if (explicit instanceof List<?> list) {
            return list.stream().filter(Number.class::isInstance).map(Number.class::cast).map(Number::intValue).distinct().toList();
        }
        String patch = String.valueOf(changed.getOrDefault("patch", ""));
        List<Integer> lines = new ArrayList<>();
        int newLine = 0;
        for (String row : patch.split("\\R")) {
            if (row.startsWith("@@")) {
                int plus = row.indexOf('+');
                int comma = row.indexOf(',', plus);
                int space = row.indexOf(' ', plus);
                int end = comma > plus ? comma : (space > plus ? space : row.length());
                try {
                    newLine = Integer.parseInt(row.substring(plus + 1, end));
                } catch (Exception ignored) {
                    newLine = 0;
                }
            } else if (row.startsWith("+") && !row.startsWith("+++")) {
                if (newLine > 0) {
                    lines.add(newLine);
                }
                newLine++;
            } else if (!row.startsWith("-")) {
                newLine++;
            }
        }
        return lines.stream().distinct().limit(200).toList();
    }

    private static String excerpt(String content, int line, int context) {
        String[] lines = content == null ? new String[0] : content.split("\\R", -1);
        int start = Math.max(1, line - context);
        int end = Math.min(lines.length, line + context);
        StringBuilder out = new StringBuilder();
        for (int i = start; i <= end; i++) {
            out.append(i).append(": ").append(lines[i - 1]).append('\n');
        }
        return out.toString();
    }

    private static String stem(String path) {
        String name = path.contains("/") ? path.substring(path.lastIndexOf('/') + 1) : path;
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
}
