package com.srk.codingagent.cli;

import com.srk.codingagent.model.converse.ConverseMessage;
import com.srk.codingagent.persistence.Event;
import com.srk.codingagent.persistence.PersistenceException;
import com.srk.codingagent.persistence.SessionLineage;
import com.srk.codingagent.persistence.SessionListing;
import com.srk.codingagent.persistence.SessionReplay;
import com.srk.codingagent.persistence.SessionStore;
import java.io.PrintStream;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The testable orchestration behind the {@code resume} and {@code sessions} subcommands
 * (04-apis § 1.2): it lists a repository's sessions most-recent-first (AC-7.1 / AC-15.2)
 * and, when a session id is given, reconstructs that session's conversation context by
 * replaying its persisted events into a {@code messages[]} (AC-7.2), defaulting to the
 * latest compaction-continuation in the session's lineage (AC-7.4).
 *
 * <p>This is the seam {@link Main} delegates the session subcommands to, so the listing,
 * lineage-default, and replay logic is unit-tested against an injected {@link SessionStore}
 * (a real store over a temp dir — never mocked) without the production composition. The
 * <em>continuation wiring</em> — feeding the reconstructed {@code messages[]} into a live,
 * continued {@link com.srk.codingagent.loop.AgentLoop} so the developer keeps talking — is
 * deliberately left to where it composes (it needs the production Bedrock client built by
 * {@link AgentLoopFactory}, the same live-credentials seam the one-shot/REPL paths use);
 * {@link #resume(String)} produces and reports the reconstructed transcript that wiring
 * starts from. See the task handoff's continuation-seam rationale.
 *
 * <p><b>Library/CLI split (NFR-LOG, 04-apis § 1.6).</b> This CLI-layer command owns its
 * user-facing output (it writes to the injected {@link PrintStream}); the persistence
 * library it drives never writes to stdout/stderr.
 */
public final class ResumeCommand {

    private static final Logger LOGGER = LoggerFactory.getLogger(ResumeCommand.class);

    /** Listing timestamp format (UTC instant rendered ISO-8601 to the second). */
    private static final DateTimeFormatter LISTED_AT =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss'Z'")
                    .withZone(java.time.ZoneOffset.UTC);

    private final SessionStore store;
    private final SessionReplay replay;
    private final SessionLineage lineage;
    private final String repoKey;
    private final PrintStream out;

    /**
     * Creates a command over the session store and reconstruction collaborators for one
     * repository.
     *
     * @param store   the session/lineage store to list and read from; must not be
     *                {@code null}.
     * @param replay  the events&rarr;{@code messages[]} reconstructor (AC-7.2); must not be
     *                {@code null}.
     * @param lineage the latest-continuation resolver (AC-7.4); must not be {@code null}.
     * @param repoKey the repository key to scope listing/resume to; non-blank.
     * @param out     the stream the listing and resume summary are written to; must not be
     *                {@code null}.
     * @throws NullPointerException     if any reference argument is {@code null}.
     * @throws IllegalArgumentException if {@code repoKey} is blank.
     */
    public ResumeCommand(
            SessionStore store,
            SessionReplay replay,
            SessionLineage lineage,
            String repoKey,
            PrintStream out) {
        this.store = Objects.requireNonNull(store, "store");
        this.replay = Objects.requireNonNull(replay, "replay");
        this.lineage = Objects.requireNonNull(lineage, "lineage");
        if (Objects.requireNonNull(repoKey, "repoKey").isBlank()) {
            throw new IllegalArgumentException("repoKey must be non-blank");
        }
        this.repoKey = repoKey;
        this.out = Objects.requireNonNull(out, "out");
    }

    /**
     * Lists the repository's resumable sessions, most-recent-first (AC-7.1 / AC-15.2),
     * writing one line per session, and returns the process exit code.
     *
     * <p>An empty repository is not an error: the command prints a clear "no sessions"
     * line and returns {@link ExitCode#OK} ({@code 0}) — listing nothing cleanly.
     *
     * @return {@link ExitCode#OK} ({@code 0}) — listing always succeeds (a corrupt store is
     *         surfaced by the {@link SessionStore} as an exception the launcher maps).
     */
    public int list() {
        List<SessionListing> sessions = store.listSessions(repoKey);
        if (sessions.isEmpty()) {
            out.println("No sessions for this repository.");
            LOGGER.info("listed 0 sessions for repoKey={}", repoKey);
            return ExitCode.OK.code();
        }
        out.println("Sessions (most-recent-first):");
        for (SessionListing listing : sessions) {
            out.println(formatListing(listing));
        }
        LOGGER.info("listed {} session(s) for repoKey={}", sessions.size(), repoKey);
        return ExitCode.OK.code();
    }

    /**
     * Resumes a session: resolves the latest continuation in its lineage (AC-7.4),
     * reconstructs the conversation context by replaying its persisted events into a
     * {@code messages[]} (AC-7.2), reports the reconstructed transcript, and returns the
     * process exit code.
     *
     * <p>A session id that names no session (no event log under the repo) is a bad
     * invocation: the command prints a stderr-style line via {@code out} naming the id and
     * returns {@link ExitCode#USAGE_CONFIG} ({@code 2}) — the same fail-fast code a bad CLI
     * argument gets (a resume of a non-existent id is a usage error, not a crash; AC-7.5's
     * corrupt-log surface is handled by {@link SessionStore#readEvents} throwing, which the
     * launcher maps separately).
     *
     * @param sessionId the session id to resume; non-blank.
     * @return {@link ExitCode#OK} ({@code 0}) when the session was found and replayed, or
     *         {@link ExitCode#USAGE_CONFIG} ({@code 2}) when no such session exists.
     * @throws NullPointerException     if {@code sessionId} is {@code null}.
     * @throws IllegalArgumentException if {@code sessionId} is blank.
     * @throws PersistenceException     if the selected session's log is corrupt/unreadable
     *                                  (AC-7.5 — surfaced for the launcher to map).
     */
    public int resume(String sessionId) {
        if (Objects.requireNonNull(sessionId, "sessionId").isBlank()) {
            throw new IllegalArgumentException("sessionId must be non-blank");
        }
        String target = lineage.latestContinuation(sessionId, store.listSessions(repoKey));
        List<Event> events = store.readEvents(repoKey, target);
        if (events.isEmpty()) {
            out.println("No session found with id '" + sessionId + "'.");
            LOGGER.warn("resume requested for unknown session id '{}' (repoKey={})", sessionId, repoKey);
            return ExitCode.USAGE_CONFIG.code();
        }
        List<ConverseMessage> messages = replay.replay(events);
        announceResume(sessionId, target, events.size(), messages.size());
        return ExitCode.OK.code();
    }

    /** Prints the resume summary: which session, the continuation chosen, and the transcript size. */
    private void announceResume(String requested, String target, int eventCount, int turnCount) {
        if (target.equals(requested)) {
            out.println("Resuming session '" + target + "'.");
        } else {
            // AC-7.4: defaulted to the latest continuation in the lineage.
            out.println("Resuming latest continuation '" + target + "' of session '" + requested + "'.");
        }
        out.println("Replayed " + eventCount + " event(s) into " + turnCount + " conversation turn(s).");
        LOGGER.info("resumed session '{}' (continuation of '{}'): {} events -> {} turns",
                target, requested, eventCount, turnCount);
    }

    private static String formatListing(SessionListing listing) {
        StringBuilder line = new StringBuilder("  ")
                .append(listing.sessionId())
                .append("  (")
                .append(LISTED_AT.format(listing.lastModified()))
                .append(')');
        if (listing.isDerivedContinuation()) {
            line.append("  [continuation of ").append(listing.parentSessionId()).append(']');
        }
        return line.toString();
    }
}
