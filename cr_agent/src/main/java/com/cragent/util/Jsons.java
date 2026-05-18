package com.cragent.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.util.Map;

public final class Jsons {
    public static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private Jsons() {
    }

    public static String stringify(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Unable to serialize JSON", e);
        }
    }

    public static String pretty(Object value) {
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Unable to serialize JSON", e);
        }
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseMap(String value) {
        try {
            return MAPPER.readValue(value, Map.class);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Unable to parse JSON object", e);
        }
    }

    public static JsonNode parseNode(String value) {
        try {
            return MAPPER.readTree(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Unable to parse JSON", e);
        }
    }
}

