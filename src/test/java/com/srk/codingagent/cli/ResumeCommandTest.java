package com.srk.codingagent.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.srk.codingagent.persistence.ContentBlock;
import com.srk.codingagent.persistence.EdgeType;
import com.srk.codingagent.persistence.Event;
import com.srk.codingagent.persistence.EventLog;
import com.srk.codingagent.persistence.ModelResponsePayload;
import com.srk.codingagent.persistence.PersistenceException;
import com.srk.codingagent.persistence.SessionLineage;
import com.srk.codingagent.persistence.SessionMeta;
import com.srk.codingagent.persistence.SessionReplay;
import com.srk.codingagent.persistence.SessionStatus;
import com.srk.codingagent.persistence.SessionStore;
import com.srk.codingagent.persistence.StopReason;
import com.srk.codingagent.persistence.UserMessagePayload;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for {@link ResumeCommand} — the CLI orchestration behind {@code resume} and
 * {@code sessions} (04-apis § 1.2).
 *
 * <p>Oracles:
 * <ul>
 *   <li><b>AC-7.1 / AC-15.2</b>: list resumable / past sessions, most-recent-first.</li>
 *   <li><b>AC-7.2</b>: resuming a selected session reconstructs its context by replaying its
 *       persisted events (reported as the reconstructed transcript).</li>
 *   <li><b>AC-7.4</b>: resuming a session with a compaction-derived continuation defaults to
 *       the latest continuation in the lineage.</li>
 *   <li><b>cli-exit-codes {@code 0}/{@code 2}</b>: listing nothing is a clean {@code 0};
 *       resuming a non-existent id is a usage error ({@code 2}).</li>
 *   <li><b>AC-7.5</b>: a corrupt selected-session log surfaces (a {@link PersistenceException}
 *       the launcher maps), it does not crash silently.</li>
 * </ul>
 *
 * <p>The SUT (a real {@link ResumeCommand}) drives a real {@link SessionStore} over a
 * {@code @TempDir}, a real {@link SessionReplay}, and a real {@link SessionLineage} — none
 * mocked; only the output {@link PrintStream} is captured to assert the user-facing report.
 */
class ResumeCommandTest {

    private static final String REPO_KEY = "one-shot";

    private final ByteArrayOutputStream sink = new ByteArrayOutputStream();
    private final PrintStream out = new PrintStream(sink, true, StandardCharsets.UTF_8);

    private ResumeCommand command(SessionStore store) {
        return new ResumeCommand(store, new SessionReplay(), new SessionLineage(), REPO_KEY, out);
    }

    private String output() {
        return sink.toString(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("AC-7.1: list reports the repo's sessions most-recent-first and exits 0")
    void list_reportsSessionsMostRecentFirst(@TempDir Path root) throws Exception {
        // Oracle: AC-7.1 — "list resumable sessions ... most-recent first." Two sessions, the
        // newer one must appear before the older one in the printed list, and the command exits 0.
        SessionStore store = new SessionStore(root);
        writeUserTurn(store, "2026-06-17T090000Z-older", "first");
        writeUserTurn(store, "2026-06-17T110000Z-newer", "second");
        setMtime(store, "2026-06-17T090000Z-older", "2026-06-17T09:00:00Z");
        setMtime(store, "2026-06-17T110000Z-newer", "2026-06-17T11:00:00Z");

        int code = command(store).list();

        assertEquals(0, code, "AC-7.1: listing succeeds (exit 0)");
        String report = output();
        int newerAt = report.indexOf("2026-06-17T110000Z-newer");
        int olderAt = report.indexOf("2026-06-17T090000Z-older");
        assertTrue(newerAt >= 0 && olderAt >= 0, "both sessions must be listed; was:\n" + report);
        assertTrue(newerAt < olderAt,
                "AC-7.1: the most-recent session must be listed before the older one;\n" + report);
    }

    @Test
    @DisplayName("AC-15.2: the list marks a compaction-derived continuation (incl. compacted)")
    void list_marksContinuation(@TempDir Path root) {
        // Oracle: AC-15.2 — "list past sessions for this repo (incl. compacted)." A continuation
        // (DERIVED_FROM) must be visibly marked in the listing so a reader sees the compacted
        // lineage, not just bare ids.
        SessionStore store = new SessionStore(root);
        writeUserTurn(store, "continuation", "continued");
        store.writeMeta(new SessionMeta("continuation", REPO_KEY, SessionStatus.ACTIVE, 1, 0, 0,
                "original", EdgeType.DERIVED_FROM, null));

        command(store).list();

        assertTrue(output().contains("[continuation of original]"),
                "AC-15.2: a derived continuation must be marked in the listing; was:\n" + output());
    }

    @Test
    @DisplayName("AC-7.1: listing a repo with no sessions reports cleanly and exits 0")
    void list_noSessions_cleanZero(@TempDir Path root) {
        // Oracle: AC-7.1 — the empty case is "list nothing cleanly": a clear message and exit 0,
        // not an error.
        int code = command(new SessionStore(root)).list();

        assertEquals(0, code, "listing nothing is a clean exit 0");
        assertTrue(output().contains("No sessions"),
                "the empty list prints a clear no-sessions message; was: " + output());
    }

    @Test
    @DisplayName("AC-7.2: resuming a session replays its events and reports the reconstructed transcript")
    void resume_replaysAndReportsTranscript(@TempDir Path root) {
        // Oracle: AC-7.2 — "reconstruct its context by replaying the session's persisted events."
        // A session with a user turn and an assistant turn replays into 2 conversation turns; the
        // command reports that it resumed the session and reconstructed the turns, and exits 0.
        SessionStore store = new SessionStore(root);
        try (EventLog log = store.openLog(REPO_KEY, "2026-06-17T090000Z-abc")) {
            log.append(userTurn("hello"));
            log.append(assistantTurn("hi there"));
        }

        int code = command(store).resume("2026-06-17T090000Z-abc");

        assertEquals(0, code, "AC-7.2: resuming an existing session succeeds (exit 0)");
        String report = output();
        assertTrue(report.contains("Resuming session '2026-06-17T090000Z-abc'"),
                "the report must name the resumed session; was:\n" + report);
        assertTrue(report.contains("2 conversation turn(s)"),
                "AC-7.2: the report must state the reconstructed transcript size (2 turns);\n" + report);
    }

    @Test
    @DisplayName("AC-7.4: resuming defaults to the latest compaction-derived continuation")
    void resume_defaultsToLatestContinuation(@TempDir Path root) {
        // Oracle: AC-7.4 — "resume the latest continuation in the lineage by default." Original
        // session has a DERIVED_FROM continuation; resuming the original must default to the
        // continuation (the report names the continuation it diverted to).
        SessionStore store = new SessionStore(root);
        try (EventLog log = store.openLog(REPO_KEY, "original")) {
            log.append(userTurn("original work"));
        }
        try (EventLog log = store.openLog(REPO_KEY, "continuation")) {
            log.append(userTurn("continued work"));
            log.append(assistantTurn("ok"));
        }
        store.writeMeta(new SessionMeta("continuation", REPO_KEY, SessionStatus.ACTIVE, 2, 0, 0,
                "original", EdgeType.DERIVED_FROM, null));

        int code = command(store).resume("original");

        assertEquals(0, code);
        String report = output();
        assertTrue(report.contains("Resuming latest continuation 'continuation' of session 'original'"),
                "AC-7.4: resuming the original must default to its latest continuation;\n" + report);
        assertTrue(report.contains("2 conversation turn(s)"),
                "AC-7.4: the reconstructed transcript is the CONTINUATION's (2 turns), not the "
                        + "original's (1 turn);\n" + report);
    }

    @Test
    @DisplayName("cli-exit-codes 2: resuming a non-existent session id is a usage error")
    void resume_unknownId_usageError(@TempDir Path root) {
        // Oracle: cli-exit-codes 2 — a resume of an id with no session on disk is a bad invocation;
        // the command names the id and returns exit 2, rather than pretending to resume nothing.
        int code = command(new SessionStore(root)).resume("no-such-session");

        assertEquals(2, code, "resuming an unknown session id returns usage/config exit 2");
        assertTrue(output().contains("no-such-session"),
                "the message names the unknown session id; was: " + output());
    }

    @Test
    @DisplayName("AC-7.5: resuming a session whose log is corrupt surfaces (does not crash)")
    void resume_corruptLog_surfaces(@TempDir Path root) throws Exception {
        // Oracle: AC-7.5 — "if a session's persisted events are corrupt or unreadable, the agent
        // reports it ... rather than crashing." Resuming a corrupt-log session surfaces a
        // PersistenceException for the launcher to map (it is not swallowed).
        SessionStore store = new SessionStore(root);
        Path log = store.logPath(REPO_KEY, "corrupt");
        Files.createDirectories(log.getParent());
        Files.writeString(log, "not valid json\n", StandardCharsets.UTF_8);

        assertThrows(PersistenceException.class, () -> command(store).resume("corrupt"),
                "AC-7.5: a corrupt selected session surfaces, not crashes");
    }

    @Test
    @DisplayName("resume rejects a blank session id; constructor rejects a blank repo key")
    void rejectsBadInput(@TempDir Path root) {
        SessionStore store = new SessionStore(root);
        assertThrows(IllegalArgumentException.class, () -> command(store).resume("  "),
                "a blank session id is rejected");
        assertThrows(IllegalArgumentException.class,
                () -> new ResumeCommand(store, new SessionReplay(), new SessionLineage(), "  ", out),
                "a blank repo key is rejected");
    }

    private static Event userTurn(String text) {
        return new Event(0, "2026-06-17T09:00:05Z",
                new UserMessagePayload(List.of(ContentBlock.text(text))));
    }

    private static Event assistantTurn(String text) {
        return new Event(0, "2026-06-17T09:00:06Z",
                new ModelResponsePayload(StopReason.END_TURN, List.of(ContentBlock.text(text))));
    }

    private static void writeUserTurn(SessionStore store, String sessionId, String text) {
        try (EventLog log = store.openLog(REPO_KEY, sessionId)) {
            log.append(new Event(0, "2026-06-17T09:00:05Z",
                    new UserMessagePayload(List.of(ContentBlock.text(text)))));
        }
    }

    private static void setMtime(SessionStore store, String sessionId, String instant)
            throws Exception {
        Files.setLastModifiedTime(store.logPath(REPO_KEY, sessionId),
                java.nio.file.attribute.FileTime.from(java.time.Instant.parse(instant)));
    }
}
