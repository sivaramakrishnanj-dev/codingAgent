package com.srk.codingagent.tool.memory;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.srk.codingagent.memory.MemoryStore;
import com.srk.codingagent.persistence.EventLog;
import java.io.StringWriter;
import java.nio.file.Path;
import software.amazon.awssdk.core.document.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests that the memory tools render their JSON-Schema {@code inputSchema} documents
 * (component C12, ADR-0001 — each tool declares an {@code inputSchema} the registry renders
 * into a {@code toolSpec}). The tools are the SUT; the assertions exercise the
 * {@link ReadMemoryTool#inputSchema()} / {@link WriteMemoryTool#inputSchema()} path and the
 * schema documents behind it.
 *
 * <p>Oracle: ADR-0007 input contracts — {@code read_memory} requires {@code slug};
 * {@code write_memory} requires {@code slug}, {@code tier}, {@code why}, {@code body}.
 */
class MemoryToolSchemaRenderTest {

    @Test
    @DisplayName("ADR-0001/ADR-0007: read_memory's input schema is an object requiring slug")
    void readMemorySchema(@TempDir Path store) {
        Document schema = new ReadMemoryTool(new MemoryStore(store), "repo").inputSchema();

        assertTrue(schema.isMap(), "the input schema is a JSON-Schema object document");
        String json = schema.toString();
        assertTrue(json.contains("object"), "type is object: " + json);
        assertTrue(json.contains("slug"), "read_memory requires slug: " + json);
    }

    @Test
    @DisplayName("ADR-0001/ADR-0007: write_memory's input schema requires slug, tier, why, body")
    void writeMemorySchema(@TempDir Path store) {
        Document schema = new WriteMemoryTool(new MemoryStore(store), EventLog.over(new StringWriter(), "log"),
                () -> "2026-06-22T10:00:00Z", "sess", "repo").inputSchema();

        String json = schema.toString();
        assertTrue(json.contains("slug"), "requires slug: " + json);
        assertTrue(json.contains("tier"), "requires tier: " + json);
        assertTrue(json.contains("why"), "requires why: " + json);
        assertTrue(json.contains("body"), "requires body: " + json);
    }
}
