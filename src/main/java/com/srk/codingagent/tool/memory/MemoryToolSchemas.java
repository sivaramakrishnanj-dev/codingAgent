package com.srk.codingagent.tool.memory;

import java.util.List;
import java.util.Map;
import software.amazon.awssdk.core.document.Document;

/**
 * Authors the JSON-Schema {@code inputSchema} documents for the memory tools (C12) as SDK
 * {@link Document}s, mirroring the built-in tools' {@code ToolSchemas} approach: each tool
 * declares its input contract once and the {@code ToolRegistry} renders it straight into a
 * {@code toolSpec.inputSchema} (ADR-0001, ADR-0007).
 *
 * <p>The memory tools live in their own package ({@code com.srk.codingagent.tool.memory},
 * 02-architecture § 6), so these schemas are authored here rather than reaching into the
 * sibling package's package-private {@code ToolSchemas}. The schemas are small fixed
 * structures, so building them directly as {@link Document}s keeps the component
 * self-contained.
 *
 * <p>Package-private utility; not part of the memory-tool public API.
 */
final class MemoryToolSchemas {

    private static final String TYPE_OBJECT = "object";
    private static final String TYPE_STRING = "string";

    private MemoryToolSchemas() {
        // Non-instantiable.
    }

    /**
     * The {@code read_memory} input schema: required {@code slug} (the kebab-case id of the
     * entry to pull in full).
     *
     * @return the input-schema document for {@code read_memory}.
     */
    static Document readMemory() {
        return objectSchema(
                Map.of("slug", stringProperty(
                        "Kebab-case slug of the memory entry to read in full (from the memory index).")),
                List.of("slug"));
    }

    /**
     * The {@code write_memory} input schema: required {@code slug}, {@code tier}
     * ({@code GLOBAL}/{@code PROJECT}), {@code why} (one-line provenance), and {@code body}
     * (the learning prose) (ADR-0007, AC-12.1/AC-12.2/AC-12.3).
     *
     * @return the input-schema document for {@code write_memory}.
     */
    static Document writeMemory() {
        return objectSchema(
                Map.of(
                        "slug", stringProperty(
                                "Kebab-case id for the entry's filename, e.g. 'integration-tests-need-profile'."),
                        "tier", stringProperty(
                                "GLOBAL for a cross-project learning or PROJECT for one specific to this repo."),
                        "why", stringProperty(
                                "One-line provenance: why this is worth remembering."),
                        "body", stringProperty(
                                "The learning itself, in markdown — what to remember and the symbol it concerns.")),
                List.of("slug", "tier", "why", "body"));
    }

    private static Document objectSchema(Map<String, Document> properties, List<String> required) {
        return Document.mapBuilder()
                .putString("type", TYPE_OBJECT)
                .putDocument("properties", Document.fromMap(properties))
                .putDocument("required", Document.fromList(
                        required.stream().map(Document::fromString).toList()))
                .build();
    }

    private static Document stringProperty(String description) {
        return Document.mapBuilder()
                .putString("type", TYPE_STRING)
                .putString("description", description)
                .build();
    }
}
