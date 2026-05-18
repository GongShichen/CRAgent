package com.cragent.memory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.cragent.util.Jsons;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class MemoryStore {
    private final Path root;

    public MemoryStore(Path root) {
        this.root = root;
    }

    public Path path(String name) {
        return root.resolve(name + ".jsonl");
    }

    public void append(String name, Map<String, Object> record) {
        try {
            Files.createDirectories(root);
            Files.writeString(path(name), Jsons.stringify(record) + System.lineSeparator(), StandardCharsets.UTF_8,
                    Files.exists(path(name)) ? StandardOpenOption.APPEND : StandardOpenOption.CREATE);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to append memory", e);
        }
    }

    public List<Map<String, Object>> read(String name) {
        Path path = path(name);
        if (!Files.exists(path)) {
            return new ArrayList<>();
        }
        try {
            List<Map<String, Object>> records = new ArrayList<>();
            for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                if (!line.isBlank()) {
                    records.add(Jsons.MAPPER.readValue(line, new TypeReference<>() {
                    }));
                }
            }
            return records;
        } catch (IOException e) {
            throw new IllegalStateException("Unable to read memory", e);
        }
    }

    public void overwrite(String name, List<Map<String, Object>> records) {
        try {
            Files.createDirectories(root);
            StringBuilder out = new StringBuilder();
            for (Map<String, Object> record : records) {
                out.append(Jsons.stringify(record)).append(System.lineSeparator());
            }
            Files.writeString(path(name), out.toString(), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to write memory", e);
        }
    }
}

