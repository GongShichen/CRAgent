package com.cragent.tools;

import com.cragent.agent.LspAnalyzer;
import com.cragent.agent.RepoAuditIndexer;
import com.cragent.config.Settings;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static com.cragent.tools.ToolSchemas.bool;
import static com.cragent.tools.ToolSchemas.integer;
import static com.cragent.tools.ToolSchemas.object;
import static com.cragent.tools.ToolSchemas.str;

public class LspTools {
    private final Settings settings;
    private boolean disabledForTask;

    public LspTools(Settings settings) {
        this.settings = settings;
    }

    public void register(ToolRouter router) {
        router.register(new ToolSpec("lsp_detect_servers", "Detect required language servers for a repository stack and report install status.", object(Map.of(
                "repo_path", str("Local repository path")
        ), List.of("repo_path")), this::detectServers, false));
        router.register(new ToolSpec("lsp_workspace_symbols", "Run LSP workspace/symbol across configured language servers.", object(Map.of(
                "repo_path", str("Local repository path"),
                "query", str("Symbol query, empty string for all supported servers")
        ), List.of("repo_path")), this::workspaceSymbols, false));
        router.register(new ToolSpec("lsp_document_symbols", "Run LSP textDocument/documentSymbol for one repository file.", object(Map.of(
                "repo_path", str("Local repository path"),
                "path", str("Relative file path")
        ), List.of("repo_path", "path")), this::documentSymbols, false));
        router.register(new ToolSpec("lsp_definition", "Run LSP textDocument/definition at a file position.", object(Map.of(
                "repo_path", str("Local repository path"),
                "path", str("Relative file path"),
                "line", integer("1-based line number"),
                "character", integer("0-based character offset")
        ), List.of("repo_path", "path", "line", "character")), this::definition, false));
        router.register(new ToolSpec("lsp_references", "Run LSP textDocument/references at a file position.", object(Map.of(
                "repo_path", str("Local repository path"),
                "path", str("Relative file path"),
                "line", integer("1-based line number"),
                "character", integer("0-based character offset"),
                "include_declaration", bool("Whether to include the declaration in references")
        ), List.of("repo_path", "path", "line", "character")), this::references, false));
        router.register(new ToolSpec("lsp_hover", "Run LSP textDocument/hover at a file position.", object(Map.of(
                "repo_path", str("Local repository path"),
                "path", str("Relative file path"),
                "line", integer("1-based line number"),
                "character", integer("0-based character offset")
        ), List.of("repo_path", "path", "line", "character")), this::hover, false));
        router.register(new ToolSpec("lsp_diagnostics", "Open supported files through real LSP servers and collect publishDiagnostics.", object(Map.of(
                "repo_path", str("Local repository path")
        ), List.of("repo_path")), this::diagnostics, false));
        router.register(new ToolSpec("lsp_capabilities", "Report available read-only LSP servers and high-level CR LSP tools.", object(Map.of(
                "repo_path", str("Local repository path")
        ), List.of("repo_path")), this::capabilities, false));
        router.register(new ToolSpec("lsp_symbol_at_position", "Find the nearest document symbol for a file and line.", object(Map.of(
                "repo_path", str("Local repository path"),
                "path", str("Relative file path"),
                "line", integer("1-based line number")
        ), List.of("repo_path", "path", "line")), this::symbolAtPosition, false));
        router.register(new ToolSpec("lsp_changed_symbols", "Map changed files and diff lines to containing symbols.", object(Map.of(
                "repo_path", str("Local repository path"),
                "changed_files", ToolSchemas.array("Changed files with filename/path and patch or changed_lines")
        ), List.of("repo_path", "changed_files")), this::changedSymbols, false));
        router.register(new ToolSpec("lsp_call_graph", "Build a read-only call graph evidence bundle for a symbol position using definition, references, and hover.", object(Map.of(
                "repo_path", str("Local repository path"),
                "path", str("Relative file path"),
                "line", integer("1-based line number"),
                "character", integer("0-based character offset")
        ), List.of("repo_path", "path", "line", "character")), this::callGraph, false));
        router.register(new ToolSpec("lsp_related_tests_by_symbol", "Find test files related to the symbol at a file position.", object(Map.of(
                "repo_path", str("Local repository path"),
                "path", str("Relative file path"),
                "line", integer("1-based line number"),
                "character", integer("0-based character offset")
        ), List.of("repo_path", "path", "line", "character")), this::relatedTestsBySymbol, false));
        router.register(new ToolSpec("lsp_evidence_bundle", "Bundle symbol, hover, definition, references, source excerpt, and related tests for a review candidate.", object(Map.of(
                "repo_path", str("Local repository path"),
                "path", str("Relative file path"),
                "line", integer("1-based line number"),
                "character", integer("0-based character offset")
        ), List.of("repo_path", "path", "line", "character")), this::evidenceBundle, false));
    }

    private Object detectServers(Map<String, Object> args) {
        RepoAuditIndexer.AuditIndex index = index(args);
        return new LspAnalyzer(settings).detectServers(index.stack());
    }

    private Object workspaceSymbols(Map<String, Object> args) {
        return runLsp(() -> {
            Path root = path(args);
            RepoAuditIndexer.AuditIndex index = new RepoAuditIndexer(settings).index(root);
            return new LspAnalyzer(settings).workspaceSymbols(root, index, String.valueOf(args.getOrDefault("query", "")));
        });
    }

    private Object documentSymbols(Map<String, Object> args) {
        return runLsp(() -> {
            Path root = path(args);
            RepoAuditIndexer.AuditIndex index = new RepoAuditIndexer(settings).index(root);
            return new LspAnalyzer(settings).documentSymbols(root, index, String.valueOf(args.get("path")));
        });
    }

    private Object definition(Map<String, Object> args) {
        return runLsp(() -> {
            Path root = path(args);
            RepoAuditIndexer.AuditIndex index = new RepoAuditIndexer(settings).index(root);
            return new LspAnalyzer(settings).definition(root, index, String.valueOf(args.get("path")), intArg(args, "line", 1), intArg(args, "character", 0));
        });
    }

    private Object references(Map<String, Object> args) {
        return runLsp(() -> {
            Path root = path(args);
            RepoAuditIndexer.AuditIndex index = new RepoAuditIndexer(settings).index(root);
            boolean includeDeclaration = Boolean.parseBoolean(String.valueOf(args.getOrDefault("include_declaration", "true")));
            return new LspAnalyzer(settings).references(root, index, String.valueOf(args.get("path")), intArg(args, "line", 1), intArg(args, "character", 0), includeDeclaration);
        });
    }

    private Object hover(Map<String, Object> args) {
        return runLsp(() -> {
            Path root = path(args);
            RepoAuditIndexer.AuditIndex index = new RepoAuditIndexer(settings).index(root);
            return new LspAnalyzer(settings).hover(root, index, String.valueOf(args.get("path")), intArg(args, "line", 1), intArg(args, "character", 0));
        });
    }

    private Object diagnostics(Map<String, Object> args) {
        return runLsp(() -> {
            Path root = path(args);
            RepoAuditIndexer.AuditIndex index = new RepoAuditIndexer(settings).index(root);
            return new LspAnalyzer(settings).diagnostics(root, index);
        });
    }

    private Object capabilities(Map<String, Object> args) {
        return runLsp(() -> {
            Path root = path(args);
            RepoAuditIndexer.AuditIndex index = new RepoAuditIndexer(settings).index(root);
            return new LspAnalyzer(settings).capabilities(root, index);
        });
    }

    private Object symbolAtPosition(Map<String, Object> args) {
        return runLsp(() -> {
            Path root = path(args);
            RepoAuditIndexer.AuditIndex index = new RepoAuditIndexer(settings).index(root);
            return new LspAnalyzer(settings).symbolAtPosition(root, index, String.valueOf(args.get("path")), intArg(args, "line", 1));
        });
    }

    @SuppressWarnings("unchecked")
    private Object changedSymbols(Map<String, Object> args) {
        return runLsp(() -> {
            Path root = path(args);
            RepoAuditIndexer.AuditIndex index = new RepoAuditIndexer(settings).index(root);
            Object raw = args.getOrDefault("changed_files", List.of());
            List<Map<String, Object>> files = raw instanceof List<?> list
                    ? list.stream().filter(Map.class::isInstance).map(Map.class::cast).map(map -> (Map<String, Object>) map).toList()
                    : List.of();
            return new LspAnalyzer(settings).changedSymbols(root, index, files);
        });
    }

    private Object callGraph(Map<String, Object> args) {
        return runLsp(() -> {
            Path root = path(args);
            RepoAuditIndexer.AuditIndex index = new RepoAuditIndexer(settings).index(root);
            return new LspAnalyzer(settings).callGraph(root, index, String.valueOf(args.get("path")), intArg(args, "line", 1), intArg(args, "character", 0));
        });
    }

    private Object relatedTestsBySymbol(Map<String, Object> args) {
        return runLsp(() -> {
            Path root = path(args);
            RepoAuditIndexer.AuditIndex index = new RepoAuditIndexer(settings).index(root);
            return new LspAnalyzer(settings).relatedTestsBySymbol(root, index, String.valueOf(args.get("path")), intArg(args, "line", 1), intArg(args, "character", 0));
        });
    }

    private Object evidenceBundle(Map<String, Object> args) {
        return runLsp(() -> {
            Path root = path(args);
            RepoAuditIndexer.AuditIndex index = new RepoAuditIndexer(settings).index(root);
            return new LspAnalyzer(settings).evidenceBundle(root, index, String.valueOf(args.get("path")), intArg(args, "line", 1), intArg(args, "character", 0));
        });
    }

    private Object runLsp(Supplier<Object> supplier) {
        if (disabledForTask) {
            return skipped();
        }
        try {
            Object result = supplier.get();
            if (containsMissingServer(result)) {
                disabledForTask = true;
            }
            return result;
        } catch (IllegalStateException e) {
            if (e.getMessage() != null && e.getMessage().contains("Missing LSP server")) {
                disabledForTask = true;
                return Map.of(
                        "status", "skipped",
                        "lsp_disabled_for_task", true,
                        "reason", e.getMessage()
                );
            }
            throw e;
        }
    }

    @SuppressWarnings("unchecked")
    private static boolean containsMissingServer(Object value) {
        if (value instanceof Map<?, ?> map) {
            if ("missing".equals(String.valueOf(map.get("status")))) {
                return true;
            }
            Object error = map.get("error");
            if (error != null && String.valueOf(error).contains("language server command is not installed")) {
                return true;
            }
            for (Object child : map.values()) {
                if (containsMissingServer(child)) {
                    return true;
                }
            }
        }
        if (value instanceof Iterable<?> iterable) {
            for (Object child : iterable) {
                if (containsMissingServer(child)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static Map<String, Object> skipped() {
        return Map.of(
                "status", "skipped",
                "lsp_disabled_for_task", true,
                "reason", "LSP was skipped earlier in this task because a required server is not installed."
        );
    }

    private RepoAuditIndexer.AuditIndex index(Map<String, Object> args) {
        return new RepoAuditIndexer(settings).index(path(args));
    }

    private static Path path(Map<String, Object> args) {
        return Path.of(String.valueOf(args.get("repo_path"))).toAbsolutePath().normalize();
    }

    private static int intArg(Map<String, Object> args, String key, int fallback) {
        Object value = args.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Exception e) {
            return fallback;
        }
    }
}
