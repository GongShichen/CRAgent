package com.cragent.agent;

import com.cragent.util.Jsons;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

public class JsonRpcLspClient implements AutoCloseable {
    private final Path root;
    private final String language;
    private final String command;
    private final Duration timeout;
    private final AtomicInteger ids = new AtomicInteger(1);
    private final Map<Integer, CompletableFuture<Map<String, Object>>> pending = new ConcurrentHashMap<>();
    private final Map<String, List<Map<String, Object>>> diagnostics = new ConcurrentHashMap<>();
    private Process process;
    private BufferedOutputStream stdin;
    private Thread readerThread;

    public JsonRpcLspClient(Path root, String language, String command, Duration timeout) {
        this.root = root;
        this.language = language;
        this.command = command;
        this.timeout = timeout;
    }

    public void start() {
        try {
            process = new ProcessBuilder("/bin/sh", "-lc", command)
                    .directory(root.toFile())
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            stdin = new BufferedOutputStream(process.getOutputStream());
            readerThread = new Thread(() -> readLoop(process.getInputStream()), "lsp-reader-" + language);
            readerThread.setDaemon(true);
            readerThread.start();
            initialize();
        } catch (IOException e) {
            throw new IllegalStateException("Unable to start LSP server for " + language + " using `" + command + "`: " + e.getMessage(), e);
        }
    }

    public Map<String, Object> initialize() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("processId", ProcessHandle.current().pid());
        params.put("rootUri", root.toUri().toString());
        params.put("rootPath", root.toString());
        params.put("capabilities", Map.of(
                "textDocument", Map.of(
                        "documentSymbol", Map.of("hierarchicalDocumentSymbolSupport", true),
                        "definition", Map.of(),
                        "references", Map.of(),
                        "hover", Map.of(),
                        "publishDiagnostics", Map.of()
                ),
                "workspace", Map.of("symbol", Map.of())
        ));
        params.put("workspaceFolders", List.of(Map.of("uri", root.toUri().toString(), "name", root.getFileName().toString())));
        Map<String, Object> response = request("initialize", params);
        notify("initialized", Map.of());
        return response;
    }

    public void didOpen(String relativePath, String languageId, String text) {
        Path file = root.resolve(relativePath).normalize();
        notify("textDocument/didOpen", Map.of("textDocument", Map.of(
                "uri", file.toUri().toString(),
                "languageId", languageId,
                "version", 1,
                "text", text
        )));
    }

    public Object documentSymbol(String relativePath) {
        return request("textDocument/documentSymbol", Map.of(
                "textDocument", Map.of("uri", root.resolve(relativePath).normalize().toUri().toString())
        )).get("result");
    }

    public Object definition(String relativePath, int line, int character) {
        return request("textDocument/definition", textPosition(relativePath, line, character)).get("result");
    }

    public Object references(String relativePath, int line, int character, boolean includeDeclaration) {
        Map<String, Object> params = new LinkedHashMap<>(textPosition(relativePath, line, character));
        params.put("context", Map.of("includeDeclaration", includeDeclaration));
        return request("textDocument/references", params).get("result");
    }

    public Object hover(String relativePath, int line, int character) {
        return request("textDocument/hover", textPosition(relativePath, line, character)).get("result");
    }

    public Object workspaceSymbol(String query) {
        return request("workspace/symbol", Map.of("query", query == null ? "" : query)).get("result");
    }

    public Map<String, List<Map<String, Object>>> diagnosticsSnapshot() {
        return new LinkedHashMap<>(diagnostics);
    }

    public void waitForDiagnostics() {
        try {
            Thread.sleep(Math.min(1500, Math.max(200, timeout.toMillis() / 4)));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public Map<String, Object> request(String method, Object params) {
        int id = ids.getAndIncrement();
        CompletableFuture<Map<String, Object>> future = new CompletableFuture<>();
        pending.put(id, future);
        send(Map.of("jsonrpc", "2.0", "id", id, "method", method, "params", params == null ? Map.of() : params));
        try {
            Map<String, Object> response = future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (response.containsKey("error")) {
                throw new IllegalStateException("LSP `" + method + "` failed: " + response.get("error"));
            }
            return response;
        } catch (TimeoutException e) {
            pending.remove(id);
            throw new IllegalStateException("LSP `" + method + "` timed out after " + timeout.toSeconds() + "s");
        } catch (Exception e) {
            pending.remove(id);
            if (e instanceof IllegalStateException state) {
                throw state;
            }
            throw new IllegalStateException("LSP `" + method + "` failed: " + e.getMessage(), e);
        }
    }

    public void notify(String method, Object params) {
        send(Map.of("jsonrpc", "2.0", "method", method, "params", params == null ? Map.of() : params));
    }

    private Map<String, Object> textPosition(String relativePath, int line, int character) {
        return Map.of(
                "textDocument", Map.of("uri", root.resolve(relativePath).normalize().toUri().toString()),
                "position", Map.of("line", Math.max(0, line - 1), "character", Math.max(0, character))
        );
    }

    private synchronized void send(Object message) {
        try {
            byte[] body = Jsons.stringify(message).getBytes(StandardCharsets.UTF_8);
            String header = "Content-Length: " + body.length + "\r\n\r\n";
            stdin.write(header.getBytes(StandardCharsets.US_ASCII));
            stdin.write(body);
            stdin.flush();
        } catch (IOException e) {
            throw new IllegalStateException("Unable to write LSP message: " + e.getMessage(), e);
        }
    }

    private void readLoop(java.io.InputStream raw) {
        try (BufferedInputStream input = new BufferedInputStream(raw)) {
            while (true) {
                Map<String, String> headers = readHeaders(input);
                if (headers.isEmpty()) {
                    return;
                }
                int length = Integer.parseInt(headers.getOrDefault("content-length", "0"));
                byte[] body = input.readNBytes(length);
                if (body.length != length) {
                    return;
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> message = Jsons.MAPPER.readValue(body, Map.class);
                handleMessage(message);
            }
        } catch (Exception ignored) {
            pending.values().forEach(future -> future.completeExceptionally(new IllegalStateException("LSP server stopped")));
        }
    }

    @SuppressWarnings("unchecked")
    private void handleMessage(Map<String, Object> message) {
        Object id = message.get("id");
        if (id instanceof Number number) {
            CompletableFuture<Map<String, Object>> future = pending.remove(number.intValue());
            if (future != null) {
                future.complete(message);
            }
            return;
        }
        if ("textDocument/publishDiagnostics".equals(message.get("method"))) {
            Object params = message.get("params");
            if (params instanceof Map<?, ?> map) {
                String uri = String.valueOf(map.get("uri"));
                Object items = map.get("diagnostics");
                if (items instanceof List<?> list) {
                    List<Map<String, Object>> converted = new ArrayList<>();
                    for (Object item : list) {
                        if (item instanceof Map<?, ?> diagnostic) {
                            converted.add((Map<String, Object>) diagnostic);
                        }
                    }
                    diagnostics.put(pathFromUri(uri), converted);
                }
            }
        }
    }

    private Map<String, String> readHeaders(BufferedInputStream input) throws IOException {
        Map<String, String> headers = new LinkedHashMap<>();
        while (true) {
            String line = readAsciiLine(input);
            if (line == null) {
                return Map.of();
            }
            if (line.isEmpty()) {
                return headers;
            }
            int idx = line.indexOf(':');
            if (idx > 0) {
                headers.put(line.substring(0, idx).toLowerCase(), line.substring(idx + 1).trim());
            }
        }
    }

    private String readAsciiLine(BufferedInputStream input) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        while (true) {
            int b = input.read();
            if (b == -1) {
                return null;
            }
            if (b == '\n') {
                byte[] bytes = out.toByteArray();
                int len = bytes.length;
                if (len > 0 && bytes[len - 1] == '\r') {
                    len--;
                }
                return new String(bytes, 0, len, StandardCharsets.US_ASCII);
            }
            out.write(b);
        }
    }

    private String pathFromUri(String uri) {
        try {
            Path path = Path.of(URI.create(uri));
            return root.relativize(path).toString();
        } catch (Exception e) {
            return uri;
        }
    }

    @Override
    public void close() {
        try {
            if (process != null && process.isAlive()) {
                try {
                    request("shutdown", Map.of());
                } catch (Exception ignored) {
                    // Best effort shutdown.
                }
                try {
                    notify("exit", Map.of());
                } catch (Exception ignored) {
                    // Best effort exit.
                }
                process.waitFor(1, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }
}
