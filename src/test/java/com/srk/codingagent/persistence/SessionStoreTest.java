package com.srk.codingagent.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.srk.codingagent.config.PermissionMode;
import java.io.IOException;
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
 * Unit tests for {@link SessionStore} — the session/lineage store (component C15,
 * ADR-0005). Oracles:
 * <ul>
 *   <li><b>NFR-LOG-LOCATION</b>: sessions live at
 *       {@code <store>/projects/<repo-key>/sessions/<session-id>.jsonl} beside
 *       {@code <session-id>.meta.json}.</li>
 *   <li><b>AC-7.2 capability</b>: a session's events are readable in seq order.</li>
 *   <li><b>INV-1</b>: reopening a session continues seq numbering gap-free.</li>
 *   <li><b>AC-13.2</b>: the derived meta sums token counts across usage events.</li>
 *   <li><b>AC-7.5</b>: a corrupt log surfaces rather than crashes.</li>
 *   <li><b>C15 invariant</b>: originals are never deleted — the store has no delete
 *       operation.</li>
 *   <li><b>ADR-0005</b>: the meta is a derived cache; the session id is supplied at
 *       the boundary, never generated.</li>
 * </ul>
 * The SUT (a real {@link SessionStore}) is never mocked; {@code @TempDir} provides a
 * real store root.
 */
class SessionStoreTest {

    private static final String REPO_KEY = "github.com-example-widget";
    private static final String SESSION_ID = "2026-06-17T090000Z-abc123";

    private static Event sessionStart() {
        return new Event(0, "2026-06-17T09:00:00Z", new SessionStartPayload(
                SessionMode.BROWNFIELD, REPO_KEY, "anthropic.claude-opus-4-8",
                PermissionMode.ASK_EVERY_TIME));
    }

    private static Event usage(int input, int output) {
        return new Event(0, "2026-06-17T09:00:06Z", ModelUsagePayload.of(input, output));
    }

    private static Event outcome(boolean success) {
        return new Event(0, "2026-06-17T09:01:16Z", new OutcomePayload("adhoc", success, 1));
    }

    @Test
    @DisplayName("logPath follows the documented layout projects/<repo>/sessions/<id>.jsonl (NFR-LOG-LOCATION)")
    void logPath_followsDocumentedLayout(@TempDir Path root) {
        // Oracle: NFR-LOG-LOCATION / ADR-0005 on-disk layout — the JSONL log lives under
        // projects/<repo-key>/sessions/<session-id>.jsonl relative to the store root.
        SessionStore store = new SessionStore(root);

        Path log = store.logPath(REPO_KEY, SESSION_ID);

        Path expected = root.resolve("projects").resolve(REPO_KEY).resolve("sessions")
                .resolve(SESSION_ID + ".jsonl");
        assertEquals(expected, log, "the log path must follow the ADR-0005 layout");
    }

    @Test
    @DisplayName("metaPath is the sibling <id>.meta.json in the sessions dir (NFR-LOG-LOCATION)")
    void metaPath_isSiblingMetaJson(@TempDir Path root) {
        // Oracle: NFR-LOG-LOCATION — the meta summary sits beside the log as
        // <session-id>.meta.json.
        SessionStore store = new SessionStore(root);

        Path meta = store.metaPath(REPO_KEY, SESSION_ID);

        Path expected = root.resolve("projects").resolve(REPO_KEY).resolve("sessions")
                .resolve(SESSION_ID + ".meta.json");
        assertEquals(expected, meta, "the meta path must be the sibling .meta.json");
    }

    @Test
    @DisplayName("openLog creates the sessions directory and the log file (create/locate)")
    void openLog_createsSessionsDirAndFile(@TempDir Path root) {
        // Oracle: C15 responsibility — "create/locate a session file under the
        // documented layout." Opening a log for a fresh session creates the path.
        SessionStore store = new SessionStore(root);

        try (EventLog log = store.openLog(REPO_KEY, SESSION_ID)) {
            log.append(sessionStart());
        }

        assertTrue(Files.exists(store.logPath(REPO_KEY, SESSION_ID)),
                "openLog must create the session log under the documented layout");
    }

    @Test
    @DisplayName("readEvents returns a session's events in seq order (AC-7.2 read capability, INV-1 order)")
    void readEvents_returnsInSeqOrder(@TempDir Path root) {
        // Oracle: AC-7.2 capability — read the session JSONL in seq order. INV-1
        // guarantees the written order is the gap-free seq order.
        SessionStore store = new SessionStore(root);
        try (EventLog log = store.openLog(REPO_KEY, SESSION_ID)) {
            log.append(sessionStart());
            log.append(usage(1820, 47));
            log.append(outcome(true));
        }

        List<Event> events = store.readEvents(REPO_KEY, SESSION_ID);

        assertEquals(3, events.size(), "all three appended events must read back");
        assertEquals(0, events.get(0).seq(), "events must be in seq order: first is seq 0");
        assertEquals(1, events.get(1).seq(), "second is seq 1");
        assertEquals(2, events.get(2).seq(), "third is seq 2 (gap-free)");
        assertEquals(EventType.SESSION_START, events.get(0).type());
        assertEquals(EventType.OUTCOME, events.get(2).type());
    }

    @Test
    @DisplayName("readEvents on a session with no log yet returns empty (not an error)")
    void readEvents_absentLog_returnsEmpty(@TempDir Path root) {
        // Oracle: a not-yet-written session is a legitimate empty read, not a fault
        // (parallels the absent-layer reading elsewhere in the codebase).
        SessionStore store = new SessionStore(root);

        assertTrue(store.readEvents(REPO_KEY, SESSION_ID).isEmpty(),
                "reading a session with no log must yield an empty list, not throw");
    }

    @Test
    @DisplayName("reopening a session continues seq numbering gap-free (INV-1 across opens)")
    void openLog_reopenContinuesSeq(@TempDir Path root) {
        // Oracle: INV-1 — seq is gap-free per session even across reopen. After two
        // events in one open, a fresh open's first append gets seq 2.
        SessionStore store = new SessionStore(root);
        try (EventLog log = store.openLog(REPO_KEY, SESSION_ID)) {
            log.append(sessionStart());
            log.append(usage(10, 5));
        }

        try (EventLog reopened = store.openLog(REPO_KEY, SESSION_ID)) {
            Event next = reopened.append(outcome(true));
            assertEquals(2, next.seq(), "reopening must continue seq numbering at 2 (gap-free, INV-1)");
        }

        List<Event> all = store.readEvents(REPO_KEY, SESSION_ID);
        assertEquals(3, all.size(), "all events across both opens must be present");
    }

    @Test
    @DisplayName("deriveMeta sums token counts across usage events and records the outcome (AC-13.2)")
    void deriveMeta_aggregatesTokensAndOutcome(@TempDir Path root) {
        // Oracle: AC-13.2 (token counts logged) + ADR-0005 (meta summarizes tokens,
        // status, outcome). The derived meta sums input/output tokens and captures the
        // outcome success flag.
        SessionStore store = new SessionStore(root);
        try (EventLog log = store.openLog(REPO_KEY, SESSION_ID)) {
            log.append(sessionStart());
            log.append(usage(1820, 47));
            log.append(usage(1990, 18));
            log.append(outcome(true));
        }

        SessionMeta meta = store.deriveMeta(REPO_KEY, SESSION_ID, SessionStatus.COMPLETED);

        assertEquals(4, meta.eventCount(), "the meta must count all events");
        assertEquals(1820 + 1990, meta.inputTokens(), "input tokens must be summed across usage events");
        assertEquals(47 + 18, meta.outputTokens(), "output tokens must be summed across usage events");
        assertEquals(Boolean.TRUE, meta.outcomeSuccess(), "the outcome success flag must be captured");
        assertEquals(SessionStatus.COMPLETED, meta.status());
    }

    @Test
    @DisplayName("deriveMeta leaves lineage edges null (a single session's events carry no parent edge)")
    void deriveMeta_lineageNullForRoot(@TempDir Path root) {
        // Oracle: ADR-0005 — lineage edges live in lineage.json, not in a session's own
        // events; deriveMeta cannot and does not infer a parent edge from one session.
        SessionStore store = new SessionStore(root);
        try (EventLog log = store.openLog(REPO_KEY, SESSION_ID)) {
            log.append(sessionStart());
        }

        SessionMeta meta = store.deriveMeta(REPO_KEY, SESSION_ID, SessionStatus.ACTIVE);

        assertEquals(null, meta.parentSessionId(), "a derived meta has no parent session id");
        assertEquals(null, meta.edgeType(), "a derived meta has no edge type");
    }

    @Test
    @DisplayName("writeMeta then re-read yields the derived summary as JSON (meta is a derived cache, ADR-0005)")
    void writeMeta_persistsDerivedSummary(@TempDir Path root) throws IOException {
        // Oracle: ADR-0005 — the .meta.json is a convenience cache of the log. Writing
        // the derived meta persists a JSON summary beside the log.
        SessionStore store = new SessionStore(root);
        try (EventLog log = store.openLog(REPO_KEY, SESSION_ID)) {
            log.append(sessionStart());
            log.append(usage(100, 50));
        }
        SessionMeta meta = store.deriveMeta(REPO_KEY, SESSION_ID, SessionStatus.ACTIVE);

        store.writeMeta(meta);

        Path metaPath = store.metaPath(REPO_KEY, SESSION_ID);
        assertTrue(Files.exists(metaPath), "the meta file must be written beside the log");
        String json = Files.readString(metaPath, StandardCharsets.UTF_8);
        assertTrue(json.contains("\"inputTokens\" : 100"), "the meta JSON must carry the summed input tokens");
        assertTrue(json.contains(SESSION_ID), "the meta JSON must name its session id (boundary-supplied)");
    }

    @Test
    @DisplayName("a corrupt log line surfaces a PersistenceException on read (AC-7.5: report, do not crash)")
    void readEvents_corruptLine_surfaced(@TempDir Path root) throws IOException {
        // Oracle: AC-7.5 — a corrupt/unreadable log is reported (a catchable exception),
        // not a crash, so the caller can offer a new session. A non-JSON line is corrupt.
        SessionStore store = new SessionStore(root);
        Path log = store.logPath(REPO_KEY, SESSION_ID);
        Files.createDirectories(log.getParent());
        Files.writeString(log, "this is not valid event json\n", StandardCharsets.UTF_8);

        assertThrows(PersistenceException.class, () -> store.readEvents(REPO_KEY, SESSION_ID),
                "a corrupt log line must surface as a PersistenceException (AC-7.5)");
    }

    @Test
    @DisplayName("SessionStore exposes no public delete/remove API (C15: originals never deleted)")
    void noPublicDeleteApi() {
        // Oracle: C15 invariant — "Originals never deleted on compaction." Assert
        // structurally that the store offers no delete/remove operation.
        for (Method method : SessionStore.class.getDeclaredMethods()) {
            if (method.isSynthetic()) {
                continue;
            }
            String name = method.getName().toLowerCase(Locale.ROOT);
            boolean isPublic = java.lang.reflect.Modifier.isPublic(method.getModifiers());
            boolean deletingName = name.startsWith("delete") || name.startsWith("remove")
                    || name.startsWith("purge");
            assertFalse(isPublic && deletingName,
                    "SessionStore must expose no delete API; found: " + method.getName());
        }
    }

    @Test
    @DisplayName("logPath rejects a blank session id (ids are boundary-supplied and required)")
    void logPath_blankSessionId_rejected(@TempDir Path root) {
        // Oracle: ADR-0005 — the session id is supplied at the boundary and is required
        // (sortable, timestamp-prefixed). A blank id is not a valid session.
        SessionStore store = new SessionStore(root);

        assertThrows(IllegalArgumentException.class, () -> store.logPath(REPO_KEY, "  "),
                "a blank session id must be rejected");
    }
}
