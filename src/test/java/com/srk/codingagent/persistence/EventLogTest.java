package com.srk.codingagent.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for {@link EventLog} — the append-only per-session JSONL writer
 * (component C14, ADR-0005). Oracles:
 * <ul>
 *   <li><b>CT-INV-1 / INV-1</b>: appending events assigns monotonic, gap-free
 *       {@code seq} from 0; there is no public update/delete API (append-only is
 *       enforced by the type's surface).</li>
 *   <li><b>INV-2 / NFR-LOG-DURABILITY</b>: each append is flushed to the underlying
 *       stream before {@code append} returns (log-before-act).</li>
 *   <li><b>AC-13.4</b>: when an event cannot be persisted, the failure is surfaced
 *       (an exception), not swallowed.</li>
 *   <li><b>ADR-0005</b>: the writer assigns seq and uses the caller's timestamp; it
 *       never derives ts in-process.</li>
 * </ul>
 * The SUT (a real {@link EventLog}) is never mocked; a {@link StringWriter} captures
 * output and a throwing {@link Writer} drives the failure path.
 */
class EventLogTest {

    private static Event outcome(String taskRef) {
        return new Event(0, "2026-06-17T09:00:00Z", new OutcomePayload(taskRef, true, 1));
    }

    @Test
    @DisplayName("append assigns monotonic, gap-free seq 0,1,2,... (CT-INV-1, INV-1)")
    void append_assignsMonotonicGapFreeSeq() {
        // Oracle: CT-INV-1 — "appending events yields seq 0,1,2,... gap-free". The
        // caller-supplied seq is ignored; the log owns sequence numbering.
        EventLog log = EventLog.over(new StringWriter(), "test");

        Event first = log.append(outcome("t0"));
        Event second = log.append(outcome("t1"));
        Event third = log.append(outcome("t2"));

        assertEquals(0, first.seq(), "the first appended event must get seq 0 (INV-1)");
        assertEquals(1, second.seq(), "the second must get seq 1 (gap-free)");
        assertEquals(2, third.seq(), "the third must get seq 2 (gap-free)");
    }

    @Test
    @DisplayName("the caller-supplied seq is overridden by the log's assignment (INV-1 owned by the log)")
    void append_overridesCallerSeq() {
        // Oracle: INV-1 — seq is the log's responsibility; a caller cannot make it
        // non-monotonic by supplying a seq. A caller event built with seq 99 still gets
        // the next monotonic seq.
        EventLog log = EventLog.over(new StringWriter(), "test");
        Event misnumbered = new Event(99, "2026-06-17T09:00:00Z", new OutcomePayload("t", true, 0));

        Event appended = log.append(misnumbered);

        assertEquals(0, appended.seq(), "the log must assign seq 0, ignoring the caller's 99");
    }

    @Test
    @DisplayName("each appended event is written as its own line, in order (AC-13.3, INV-1 order)")
    void append_writesOneLinePerEventInOrder() {
        // Oracle: AC-13.3 — JSONL is line-oriented; INV-1 — events are appended in
        // occurrence (seq) order, one per line.
        StringWriter sink = new StringWriter();
        EventLog log = EventLog.over(sink, "test");

        log.append(outcome("t0"));
        log.append(outcome("t1"));

        List<String> lines = sink.toString().lines().toList();
        assertEquals(2, lines.size(), "each event must occupy exactly one line");
        assertTrue(lines.get(0).contains("\"seq\":0"), "line 0 carries seq 0");
        assertTrue(lines.get(1).contains("\"seq\":1"), "line 1 carries seq 1");
    }

    @Test
    @DisplayName("the caller's timestamp is preserved verbatim; the writer never derives ts (ADR-0005)")
    void append_preservesCallerTimestamp() {
        // Oracle: ADR-0005 — ts is captured at the boundary and passed in; the writer
        // does not call Instant.now(). The emitted ts equals the supplied one.
        StringWriter sink = new StringWriter();
        EventLog log = EventLog.over(sink, "test");

        log.append(new Event(0, "2026-06-17T09:00:05Z", new OutcomePayload("t", true, 0)));

        assertTrue(sink.toString().contains("\"ts\":\"2026-06-17T09:00:05Z\""),
                "the writer must emit the caller-supplied timestamp verbatim (ADR-0005)");
    }

    @Test
    @DisplayName("each append flushes to the underlying stream before returning (INV-2, NFR-LOG-DURABILITY)")
    void append_flushesPerEvent() {
        // Oracle: INV-2 / NFR-LOG-DURABILITY — flush per event, before the loop acts on
        // it. A flush-counting writer must observe a flush during each append call.
        FlushCountingWriter sink = new FlushCountingWriter();
        EventLog log = EventLog.over(sink, "test");

        log.append(outcome("t0"));
        int afterFirst = sink.flushCount;
        log.append(outcome("t1"));
        int afterSecond = sink.flushCount;

        assertTrue(afterFirst >= 1, "the first append must flush before returning (INV-2)");
        assertTrue(afterSecond > afterFirst, "the second append must flush again (flush per event)");
    }

    @Test
    @DisplayName("a write failure is surfaced as a PersistenceException, not swallowed (AC-13.4)")
    void append_writeFailure_surfaced() {
        // Oracle: AC-13.4 — "if an event cannot be persisted, surface the failure
        // rather than continuing as if it were logged." A writer that throws on write
        // must cause append to throw.
        EventLog log = EventLog.over(new ThrowingWriter(), "failing-sink");

        assertThrows(PersistenceException.class, () -> log.append(outcome("t")),
                "a write/flush failure must surface as a PersistenceException (AC-13.4)");
    }

    @Test
    @DisplayName("after a surfaced write failure, nextSeq did not advance (the event was not logged)")
    void append_writeFailure_doesNotAdvanceSeq() {
        // Oracle: AC-13.4 — the agent must not continue "as if it were logged". A
        // failed append must not advance the sequence counter, so the failed event is
        // not treated as recorded.
        EventLog log = EventLog.over(new ThrowingWriter(), "failing-sink");

        assertThrows(PersistenceException.class, () -> log.append(outcome("t")));

        assertEquals(0, log.nextSeq(), "a failed append must not advance the sequence counter");
    }

    @Test
    @DisplayName("openForAppend continues seq numbering after existing events (INV-1 across opens)")
    void openForAppend_continuesSeqAfterExisting(@TempDir Path dir) {
        // Oracle: INV-1 — seq is gap-free per session, including across reopen. Opening
        // a log told there are 3 existing events assigns the next event seq 3.
        Path file = dir.resolve("s1.jsonl");

        try (EventLog log = EventLog.openForAppend(file, 3)) {
            Event appended = log.append(outcome("t"));
            assertEquals(3, appended.seq(), "the next seq after 3 existing events must be 3 (gap-free)");
        }
    }

    @Test
    @DisplayName("openForAppend writes durable lines to disk (NFR-LOG-DURABILITY, real file)")
    void openForAppend_writesToDisk(@TempDir Path dir) throws IOException {
        // Oracle: AC-13.3 / NFR-LOG-DURABILITY — appended events are persisted as JSONL
        // lines on disk and survive close.
        Path file = dir.resolve("s1.jsonl");

        try (EventLog log = EventLog.openForAppend(file, 0)) {
            log.append(outcome("t0"));
            log.append(outcome("t1"));
        }

        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        assertEquals(2, lines.size(), "both events must be persisted as lines");
        assertTrue(lines.get(0).contains("\"seq\":0"));
        assertTrue(lines.get(1).contains("\"seq\":1"));
    }

    @Test
    @DisplayName("EventLog exposes no public update/delete/remove/set API (CT-INV-1: append-only by surface)")
    void noPublicMutationApi_exceptAppend() {
        // Oracle: CT-INV-1 — "there is NO public API to update or delete an event;
        // append-only is enforced by the type's surface, not just convention." Assert
        // structurally that no public method name implies mutation of a logged event.
        for (Method method : EventLog.class.getDeclaredMethods()) {
            if (method.isSynthetic()) {
                continue;
            }
            String name = method.getName().toLowerCase(Locale.ROOT);
            boolean isPublic = java.lang.reflect.Modifier.isPublic(method.getModifiers());
            boolean mutatingName = name.startsWith("update") || name.startsWith("delete")
                    || name.startsWith("remove") || name.startsWith("set") || name.startsWith("edit");
            assertFalse(isPublic && mutatingName,
                    "EventLog must expose no public update/delete API; found: " + method.getName());
        }
    }

    /** A writer that records how many times {@code flush()} was called. */
    private static final class FlushCountingWriter extends Writer {
        private int flushCount;

        @Override
        public void write(char[] cbuf, int off, int len) {
            // no-op sink
        }

        @Override
        public void flush() {
            flushCount++;
        }

        @Override
        public void close() {
            // no-op
        }
    }

    /** A writer that fails every write, to drive the AC-13.4 persist-failure path. */
    private static final class ThrowingWriter extends Writer {
        @Override
        public void write(char[] cbuf, int off, int len) throws IOException {
            throw new IOException("simulated disk failure");
        }

        @Override
        public void flush() throws IOException {
            throw new IOException("simulated flush failure");
        }

        @Override
        public void close() {
            // no-op
        }
    }
}
