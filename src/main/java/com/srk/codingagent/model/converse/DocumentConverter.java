package com.srk.codingagent.model.converse;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import software.amazon.awssdk.core.SdkNumber;
import software.amazon.awssdk.core.document.Document;

/**
 * Converts between plain Java JSON values and the AWS SDK's {@link Document} type, the
 * untyped JSON carrier Converse uses for {@code toolUse.input} and a {@code toolResult}
 * {@code json} content block (03-data-model.md § 7, § 6.A.1).
 *
 * <p>Our domain carries tool input as a {@code Map<String, Object>}
 * ({@link com.srk.codingagent.persistence.ContentBlock.ToolUse#input()}) and tool
 * result content as an arbitrary {@code Object}
 * ({@link com.srk.codingagent.persistence.ContentBlock.ToolResult#content()}) — the
 * same shape Jackson produces when reading JSON (maps, lists, strings, numbers,
 * booleans, {@code null}). This converter bridges that shape to a {@link Document} for
 * the request boundary and back for the response boundary, so the rest of the system
 * never touches the SDK {@code Document} type (component C4: provider-agnostic surface).
 *
 * <p>Number handling preserves integrality on the round trip: a {@code Document} number
 * is unwrapped to a {@link Long} when it has no fractional part and fits in a
 * {@code long}, otherwise to a {@link Double}. This keeps a tool input like
 * {@code {"count": 3}} an integer rather than {@code 3.0} when it is replayed.
 */
final class DocumentConverter {

    private DocumentConverter() {
    }

    /**
     * Converts a plain Java JSON value to an SDK {@link Document}.
     *
     * @param value a JSON value: {@link Map} (string keys), {@link List}, {@link String},
     *              {@link Boolean}, a {@link Number} subtype, or {@code null}.
     * @return the equivalent {@link Document}; {@code Document.fromNull()} for
     *         {@code null}.
     * @throws IllegalArgumentException if {@code value} (or a nested value) is of a type
     *                                  that has no JSON document representation, or a map
     *                                  key is not a {@link String}.
     */
    static Document toDocument(Object value) {
        if (value == null) {
            return Document.fromNull();
        }
        if (value instanceof String s) {
            return Document.fromString(s);
        }
        if (value instanceof Boolean b) {
            return Document.fromBoolean(b);
        }
        if (value instanceof Number n) {
            return numberToDocument(n);
        }
        if (value instanceof Map<?, ?> map) {
            return mapToDocument(map);
        }
        if (value instanceof List<?> list) {
            return listToDocument(list);
        }
        throw new IllegalArgumentException(
                "unsupported JSON value type: " + value.getClass().getName());
    }

    /**
     * Converts an SDK {@link Document} to a plain Java JSON value (the inverse of
     * {@link #toDocument(Object)}).
     *
     * @param document the document to convert; must not be {@code null}. Use
     *                 {@code Document.fromNull()} to represent a JSON {@code null}.
     * @return the equivalent Java value: {@link Map} (insertion-ordered), {@link List},
     *         {@link String}, {@link Boolean}, {@link Long} or {@link Double}, or
     *         {@code null} for a null document.
     */
    static Object toValue(Document document) {
        if (document == null || document.isNull()) {
            return null;
        }
        if (document.isString()) {
            return document.asString();
        }
        if (document.isBoolean()) {
            return document.asBoolean();
        }
        if (document.isNumber()) {
            return numberFromDocument(document.asNumber());
        }
        if (document.isMap()) {
            Map<String, Object> result = new LinkedHashMap<>();
            document.asMap().forEach((key, val) -> result.put(key, toValue(val)));
            return result;
        }
        if (document.isList()) {
            List<Object> result = new ArrayList<>();
            document.asList().forEach(element -> result.add(toValue(element)));
            return result;
        }
        // Document is a closed set of JSON node types; the branches above are total.
        throw new IllegalArgumentException("unsupported document node: " + document);
    }

    private static Document numberToDocument(Number n) {
        if (n instanceof Integer || n instanceof Long || n instanceof Short || n instanceof Byte) {
            return Document.fromNumber(n.longValue());
        }
        return Document.fromNumber(n.doubleValue());
    }

    private static Object numberFromDocument(SdkNumber number) {
        double asDouble = number.doubleValue();
        long asLong = number.longValue();
        // Preserve integrality: a whole number with no fractional part and within long
        // range round-trips as a Long, not a Double (so {"count": 3} stays 3).
        if (asDouble == (double) asLong) {
            return asLong;
        }
        return asDouble;
    }

    private static Document mapToDocument(Map<?, ?> map) {
        Map<String, Document> documentMap = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new IllegalArgumentException(
                        "JSON object keys must be strings but found: " + entry.getKey());
            }
            documentMap.put(key, toDocument(entry.getValue()));
        }
        return Document.fromMap(documentMap);
    }

    private static Document listToDocument(List<?> list) {
        List<Document> documentList = new ArrayList<>(list.size());
        for (Object element : list) {
            documentList.add(toDocument(element));
        }
        return Document.fromList(documentList);
    }
}
