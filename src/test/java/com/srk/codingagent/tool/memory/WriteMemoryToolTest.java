package com.srk.codingagent.tool.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.srk.codingagent.memory.MemoryEntry;
import com.srk.codingagent.memory.MemoryStore;
import com.srk.codingagent.memory.MemoryTier;
import com.srk.codingagent.persistence.EventLog;
import com.srk.codingagent.persistence.OperationClass;
import com.srk.codingagent.tool.ToolInvocationException;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for the {@code write_memory} tool (component C12, ADR-0007, AC-12.1/12.2/12.3/12.4).
 * The tool is the SUT, wired to a real {@link MemoryStore} over a {@link TempDir} and a real
 * {@link EventLog} over a {@link StringWriter} (the collaborators are genuine — no mocks of
 * the SUT or the store).
 *
 * <p>Oracles: AC-12.1 (an explicit "remember X" call writes an entry), AC-12.3 (classify
 * GLOBAL/PROJECT and store in the corresponding tier), AC-12.4 (the write is recorded as a
 * MEMORY_WRITE event in the session log), ADR-0004 (write_memory is Class X — gated), and
 * INV-13 (an entry persists only via this explicit call).
 */
class WriteMemoryToolTest {

    private static final String SESSION = "2026-06-22T09-00-00-sess";
    private static final String REPO_KEY = "github.com_srk_codingagent";
    private static final Supplier<String> CLOCK = () -> "2026-06-22T10:00:00Z";

    private WriteMemoryTool tool(Path store, EventLog log) {
        return new WriteMemoryTool(new MemoryStore(store), log, CLOCK, SESSION, REPO_KEY);
    }

    @Test
    @DisplayName("ADR-0004: write_memory is Class X (SIDE_EFFECTING), gated by the permission mode")
    void writeMemoryIsClassX(@TempDir Path store) {
        // Oracle: ADR-0007 / ADR-0004 — write_memory is Class X (a gated write). The tool
        // records SIDE_EFFECTING so the gate (T-0.7) classifies it.
        WriteMemoryTool tool = tool(store, EventLog.over(new StringWriter(), "log"));
        assertEquals(OperationClass.SIDE_EFFECTING, tool.operationClass(),
                "write_memory is Class X (ADR-0007, ADR-0004)");
        assertEquals("write_memory", tool.name());
    }

    @Test
    @DisplayName("AC-12.1 / AC-12.3: an explicit write of a GLOBAL entry writes it into the global tier")
    void explicitGlobalWrite(@TempDir Path store) {
        // Oracle: AC-12.1 — an explicit instruction to remember writes a memory entry;
        // AC-12.3 — a GLOBAL classification stores it in the global tier.
        MemoryStore memory = new MemoryStore(store);
        WriteMemoryTool tool = new WriteMemoryTool(memory, EventLog.over(new StringWriter(), "log"),
                CLOCK, SESSION, REPO_KEY);

        Object result = tool.handle(Map.of(
                "slug", "use-jitter", "tier", "GLOBAL", "why", "approved learning", "body", "add jitter"));

        MemoryEntry written = memory.readEntry("use-jitter", REPO_KEY).orElseThrow();
        assertEquals(MemoryTier.GLOBAL, written.tier(), "the entry is stored in the GLOBAL tier (AC-12.3)");
        assertEquals("add jitter", written.body().strip(), "the body is the learning prose");
        assertTrue(String.valueOf(result).startsWith("ok"), "the tool returns an ok summary: " + result);
    }

    @Test
    @DisplayName("AC-12.3: a PROJECT classification stores the entry in the project tier")
    void explicitProjectWrite(@TempDir Path store) {
        MemoryStore memory = new MemoryStore(store);
        WriteMemoryTool tool = new WriteMemoryTool(memory, EventLog.over(new StringWriter(), "log"),
                CLOCK, SESSION, REPO_KEY);

        tool.handle(Map.of("slug", "repo-fact", "tier", "PROJECT", "why", "w", "body", "b"));

        assertTrue(memory.readEntry(MemoryTier.PROJECT, "repo-fact", REPO_KEY).isPresent(),
                "a PROJECT entry is stored in the project tier (AC-12.3)");
        assertTrue(memory.readEntry(MemoryTier.GLOBAL, "repo-fact", REPO_KEY).isEmpty(),
                "it is NOT in the global tier");
    }

    @Test
    @DisplayName("AC-12.2: the written entry carries provenance (created from clock, originSession)")
    void writesProvenance(@TempDir Path store) {
        // Oracle: AC-12.2 — provenance: when (created), why, originating session. created comes
        // from the injected boundary clock (ADR-0005 — never Instant.now()).
        MemoryStore memory = new MemoryStore(store);
        WriteMemoryTool tool = new WriteMemoryTool(memory, EventLog.over(new StringWriter(), "log"),
                CLOCK, SESSION, REPO_KEY);

        tool.handle(Map.of("slug", "prov-slug", "tier", "GLOBAL", "why", "the why", "body", "b"));

        MemoryEntry written = memory.readEntry("prov-slug", REPO_KEY).orElseThrow();
        assertEquals("2026-06-22T10:00:00Z", written.created(), "created is the injected boundary timestamp");
        assertEquals(SESSION, written.originSession(), "originSession is the injected session id");
        assertEquals("the why", written.why(), "why is the supplied provenance");
    }

    @Test
    @DisplayName("AC-12.4: an explicit write records a MEMORY_WRITE event in the session log")
    void logsMemoryWriteEvent(@TempDir Path store) {
        // Oracle: AC-12.4 — the agent records memory writes as events in the session log. The
        // appended line is a MEMORY_WRITE event carrying the slug, tier, and provenance.
        StringWriter sink = new StringWriter();
        EventLog log = EventLog.over(sink, "log");
        WriteMemoryTool tool = new WriteMemoryTool(new MemoryStore(store), log, CLOCK, SESSION, REPO_KEY);

        tool.handle(Map.of("slug", "logged-slug", "tier", "PROJECT", "why", "audit me", "body", "b"));

        String line = sink.toString();
        assertTrue(line.contains("\"type\":\"MEMORY_WRITE\""), "a MEMORY_WRITE event is logged (AC-12.4): " + line);
        assertTrue(line.contains("\"slug\":\"logged-slug\""), "the event records the slug");
        assertTrue(line.contains("\"tier\":\"PROJECT\""), "the event records the tier");
        assertTrue(line.contains("\"originSession\":\"" + SESSION + "\""), "the event records provenance");
    }

    @Test
    @DisplayName("AC-12.4: exactly one MEMORY_WRITE event is logged per write")
    void logsExactlyOneEventPerWrite(@TempDir Path store) {
        StringWriter sink = new StringWriter();
        EventLog log = EventLog.over(sink, "log");
        WriteMemoryTool tool = new WriteMemoryTool(new MemoryStore(store), log, CLOCK, SESSION, REPO_KEY);

        tool.handle(Map.of("slug", "once-slug", "tier", "GLOBAL", "why", "w", "body", "b"));

        long count = sink.toString().lines().filter(l -> l.contains("MEMORY_WRITE")).count();
        assertEquals(1, count, "exactly one MEMORY_WRITE event per write");
    }

    @Test
    @DisplayName("a tier outside GLOBAL/PROJECT is surfaced as a tool error")
    void rejectsBadTier(@TempDir Path store) {
        WriteMemoryTool tool = tool(store, EventLog.over(new StringWriter(), "log"));

        ToolInvocationException ex = assertThrows(ToolInvocationException.class,
                () -> tool.handle(Map.of("slug", "x-slug", "tier", "LOCAL", "why", "w", "body", "b")));
        assertTrue(ex.getMessage().contains("GLOBAL or PROJECT"), ex.getMessage());
    }

    @Test
    @DisplayName("a non-kebab slug is surfaced as a tool error, and nothing is written or logged")
    void rejectsBadSlug(@TempDir Path store) {
        // Oracle: schema slug pattern — a bad slug must not produce a half-written entry. The
        // entry is built (and validated) before the store write, so a bad slug fails before any
        // file or event.
        StringWriter sink = new StringWriter();
        MemoryStore memory = new MemoryStore(store);
        WriteMemoryTool tool = new WriteMemoryTool(memory, EventLog.over(sink, "log"), CLOCK, SESSION, REPO_KEY);

        assertThrows(ToolInvocationException.class,
                () -> tool.handle(Map.of("slug", "Bad Slug", "tier", "GLOBAL", "why", "w", "body", "b")));
        assertTrue(memory.readEntry("Bad Slug", REPO_KEY).isEmpty(), "no entry is written for a bad slug");
        assertFalse(sink.toString().contains("MEMORY_WRITE"), "no MEMORY_WRITE event is logged on failure");
    }

    @Test
    @DisplayName("missing required inputs are surfaced as tool errors")
    void rejectsMissingInputs(@TempDir Path store) {
        WriteMemoryTool tool = tool(store, EventLog.over(new StringWriter(), "log"));

        assertThrows(ToolInvocationException.class,
                () -> tool.handle(Map.of("tier", "GLOBAL", "why", "w", "body", "b")));
        assertThrows(ToolInvocationException.class,
                () -> tool.handle(Map.of("slug", "s-slug", "tier", "GLOBAL", "body", "b")));
    }

    @Test
    @DisplayName("the constructor rejects null collaborators and a blank session/repo key")
    void constructorValidates(@TempDir Path store) {
        MemoryStore memory = new MemoryStore(store);
        EventLog log = EventLog.over(new StringWriter(), "log");
        assertThrows(NullPointerException.class,
                () -> new WriteMemoryTool(null, log, CLOCK, SESSION, REPO_KEY));
        assertThrows(NullPointerException.class,
                () -> new WriteMemoryTool(memory, null, CLOCK, SESSION, REPO_KEY));
        assertThrows(IllegalArgumentException.class,
                () -> new WriteMemoryTool(memory, log, CLOCK, " ", REPO_KEY));
    }
}
