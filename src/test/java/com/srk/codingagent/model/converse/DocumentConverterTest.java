package com.srk.codingagent.model.converse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.document.Document;

/**
 * Unit tests for {@link DocumentConverter} — the bidirectional bridge between plain
 * Java JSON values and the SDK {@link Document} type used for {@code toolUse.input} and
 * a {@code toolResult} {@code json} content block (§ 7, § 6.A.1).
 *
 * <p>Oracle: § 7 carries tool input as an object and tool result content as arbitrary
 * JSON. The contract this enforces is a faithful, structure-preserving round trip of
 * the JSON value space (object, array, string, number, boolean, null) so a toolUse
 * input or toolResult content survives the wire mapping unchanged. The SUT (the real
 * converter) is never mocked.
 */
class DocumentConverterTest {

    @Test
    @DisplayName("§ 7: a nested tool-input object round-trips unchanged")
    void roundTripsNestedObject() {
        // Oracle: § 7 — toolUse input is an object that must cross the wire unchanged. A
        // value built from JSON-shaped Java types must equal itself after toDocument then
        // toValue.
        Map<String, Object> input = Map.of(
                "path", "/etc/hosts",
                "recursive", true,
                "limits", Map.of("depth", 3L));

        Object roundTripped = DocumentConverter.toValue(DocumentConverter.toDocument(input));

        assertEquals(input, roundTripped,
                "§ 7: a nested tool-input object must round-trip unchanged");
    }

    @Test
    @DisplayName("§ 7: an array value round-trips with element order preserved")
    void roundTripsList() {
        // Oracle: § 7 — JSON content may be an array; order and element values must survive.
        List<Object> list = List.of("a", 1L, false);

        Object roundTripped = DocumentConverter.toValue(DocumentConverter.toDocument(list));

        assertEquals(list, roundTripped, "§ 7: an array value must round-trip with order preserved");
    }

    @Test
    @DisplayName("integral numbers stay integral on the round trip (no 3 → 3.0 drift)")
    void preservesIntegralNumbers() {
        // Oracle: § 7 — tool input fidelity. A JSON integer must come back as an integral
        // value (Long), not a double, so a count like 3 is not replayed as 3.0.
        Document document = DocumentConverter.toDocument(Map.of("count", 3));

        Object value = DocumentConverter.toValue(document);

        assertTrue(value instanceof Map, "an object converts to a Map");
        assertEquals(3L, ((Map<?, ?>) value).get("count"),
                "an integral input number must stay integral (Long), not become a double");
    }

    @Test
    @DisplayName("a fractional number round-trips as a double")
    void preservesFractionalNumbers() {
        // Oracle: § 7 — value fidelity. A fractional number must round-trip as a double.
        Document document = DocumentConverter.toDocument(Map.of("ratio", 0.85));

        Object value = DocumentConverter.toValue(document);

        assertEquals(0.85, ((Map<?, ?>) value).get("ratio"),
                "a fractional number must round-trip as a double");
    }

    @Test
    @DisplayName("a null value maps to a null document and back to null")
    void roundTripsNull() {
        // Oracle: § 7 — JSON null is a representable value; toDocument(null) is a null
        // document and toValue of it is Java null.
        Document document = DocumentConverter.toDocument(null);

        assertTrue(document.isNull(), "a null value maps to a null document");
        assertNull(DocumentConverter.toValue(document), "a null document maps back to null");
    }

    @Test
    @DisplayName("a scalar string round-trips unchanged")
    void roundTripsScalarString() {
        // Oracle: § 7 — toolResult content may be a bare string; it must survive unchanged.
        Object value = DocumentConverter.toValue(DocumentConverter.toDocument("plain text"));

        assertEquals("plain text", value, "a scalar string must round-trip unchanged");
    }

    @Test
    @DisplayName("an unsupported value type is rejected")
    void rejectsUnsupportedType() {
        // Oracle: § 7 — only JSON value types are representable. A non-JSON type (e.g. a
        // raw Object) has no document form and must be rejected, not silently coerced.
        assertThrows(IllegalArgumentException.class,
                () -> DocumentConverter.toDocument(new Object()));
    }

    @Test
    @DisplayName("a non-string map key is rejected")
    void rejectsNonStringKey() {
        // Oracle: § 7 — JSON object keys are strings. A map with a non-string key has no
        // JSON object representation and must be rejected.
        assertThrows(IllegalArgumentException.class,
                () -> DocumentConverter.toDocument(Map.of(1, "value")));
    }
}
