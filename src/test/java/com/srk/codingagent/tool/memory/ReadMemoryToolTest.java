package com.srk.codingagent.tool.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.srk.codingagent.memory.MemoryEntry;
import com.srk.codingagent.memory.MemoryStatus;
import com.srk.codingagent.memory.MemoryStore;
import com.srk.codingagent.memory.MemoryTier;
import com.srk.codingagent.persistence.OperationClass;
import com.srk.codingagent.tool.ToolInvocationException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for the {@code read_memory} tool (component C12, ADR-0007). The tool is the SUT, wired
 * to a real {@link MemoryStore} over a {@link TempDir}.
 *
 * <p>Oracles: ADR-0007 / ADR-0004 (read_memory is Class R — auto-approved, not gated;
 * {@code read_memory(slug)} pulls a full entry on demand), and INV-14 / AC-14.2 (the entry is
 * re-read from disk on each call — a hand-deleted entry reports as not found).
 */
class ReadMemoryToolTest {

    private static final String REPO_KEY = "github.com_srk_codingagent";

    private MemoryEntry entry(String slug, MemoryTier tier, String body) {
        return new MemoryEntry(slug, tier, "2026-06-22T10:00:00Z", "sess", "why",
                MemoryStatus.ACTIVE, body);
    }

    @Test
    @DisplayName("ADR-0004: read_memory is Class R (READ), never gated")
    void readMemoryIsClassR(@TempDir Path store) {
        // Oracle: ADR-0007 / ADR-0004 — read_memory is Class R (read, auto-approved).
        ReadMemoryTool tool = new ReadMemoryTool(new MemoryStore(store), REPO_KEY);
        assertEquals(OperationClass.READ, tool.operationClass(), "read_memory is Class R (ADR-0004)");
        assertEquals("read_memory", tool.name());
    }

    @Test
    @DisplayName("ADR-0007: read_memory(slug) returns the full entry body on demand")
    void readsFullEntryBody(@TempDir Path store) {
        // Oracle: ADR-0007 — the full entry is pulled on demand via read_memory(slug).
        MemoryStore memory = new MemoryStore(store);
        memory.write(entry("a-slug", MemoryTier.GLOBAL, "the full learning prose"), REPO_KEY);

        Object result = new ReadMemoryTool(memory, REPO_KEY).handle(Map.of("slug", "a-slug"));

        assertEquals("the full learning prose", String.valueOf(result).strip(),
                "read_memory returns the entry's full body (ADR-0007)");
    }

    @Test
    @DisplayName("ADR-0007: read_memory finds a PROJECT-tier entry too")
    void readsProjectTierEntry(@TempDir Path store) {
        MemoryStore memory = new MemoryStore(store);
        memory.write(entry("proj-slug", MemoryTier.PROJECT, "project learning"), REPO_KEY);

        Object result = new ReadMemoryTool(memory, REPO_KEY).handle(Map.of("slug", "proj-slug"));

        assertEquals("project learning", String.valueOf(result).strip());
    }

    @Test
    @DisplayName("a missing slug input is surfaced as a tool error")
    void rejectsMissingSlug(@TempDir Path store) {
        ReadMemoryTool tool = new ReadMemoryTool(new MemoryStore(store), REPO_KEY);
        assertThrows(ToolInvocationException.class, () -> tool.handle(Map.of()));
    }

    @Test
    @DisplayName("an unknown slug is surfaced as a tool error")
    void rejectsUnknownSlug(@TempDir Path store) {
        ReadMemoryTool tool = new ReadMemoryTool(new MemoryStore(store), REPO_KEY);
        ToolInvocationException ex = assertThrows(ToolInvocationException.class,
                () -> tool.handle(Map.of("slug", "no-such-slug")));
        assertTrue(ex.getMessage().contains("no-such-slug"), ex.getMessage());
    }

    @Test
    @DisplayName("INV-14 / AC-14.2: a hand-deleted entry reports as not found on the next read (no cache)")
    void honoursExternalDelete(@TempDir Path store) throws IOException {
        // Oracle: INV-14 / AC-14.2 — re-read from disk each call; a hand-deleted entry is gone
        // on the next read.
        MemoryStore memory = new MemoryStore(store);
        memory.write(entry("temp-slug", MemoryTier.GLOBAL, "body"), REPO_KEY);
        ReadMemoryTool tool = new ReadMemoryTool(memory, REPO_KEY);
        assertEquals("body", String.valueOf(tool.handle(Map.of("slug", "temp-slug"))).strip());

        Files.delete(store.resolve("memory/temp-slug.md"));

        assertThrows(ToolInvocationException.class, () -> tool.handle(Map.of("slug", "temp-slug")),
                "the deleted entry is honoured as gone on the next read (INV-14)");
    }

    @Test
    @DisplayName("INV-14 / AC-14.2: a hand-edited entry's new body is returned on the next read")
    void honoursExternalEdit(@TempDir Path store) throws IOException {
        MemoryStore memory = new MemoryStore(store);
        memory.write(entry("edit-slug", MemoryTier.GLOBAL, "first body"), REPO_KEY);
        ReadMemoryTool tool = new ReadMemoryTool(memory, REPO_KEY);

        Path file = store.resolve("memory/edit-slug.md");
        Files.writeString(file, Files.readString(file, StandardCharsets.UTF_8)
                .replace("first body", "edited body"), StandardCharsets.UTF_8);

        assertEquals("edited body", String.valueOf(tool.handle(Map.of("slug", "edit-slug"))).strip(),
                "the hand-edited body is returned (INV-14, AC-14.2)");
    }

    @Test
    @DisplayName("the constructor rejects a null store and a blank repo key")
    void constructorValidates(@TempDir Path store) {
        assertThrows(NullPointerException.class, () -> new ReadMemoryTool(null, REPO_KEY));
        assertThrows(IllegalArgumentException.class,
                () -> new ReadMemoryTool(new MemoryStore(store), " "));
    }
}
