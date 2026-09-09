package com.synechis.fulfillment.contracts;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

public final class Json {
    public static final JsonMapper MAPPER = JsonMapper.builder().build();

    private Json() {
    }

    public static String canonical(Object value) {
        return write(normalize(value));
    }

    private static Object normalize(Object v) {
        if (v instanceof Map<?, ?> m) {
            var sorted = new java.util.TreeMap<String, Object>();
            m.forEach((k, x) -> sorted.put(k.toString(), normalize(x)));
            return sorted;
        }
        if (v instanceof java.util.List<?> l) return l.stream().map(Json::normalize).toList();
        if (v instanceof Number n) return new java.math.BigDecimal(n.toString()).stripTrailingZeros();
        return v;
    }

    public static String write(Object value) {
        return MAPPER.writeValueAsString(value);
    }

    public static <T> T read(String value, Class<T> type) {
        return MAPPER.readValue(value, type);
    }

    public static Map<String, Object> map(String value) {
        return MAPPER.readValue(value, new TypeReference<Map<String, Object>>() {
        });
    }
}
