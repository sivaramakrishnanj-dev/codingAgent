package com.srk.codingagent.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The session / lineage store (component C15, ADR-0005): persists sessions keyed by
 * repository under the user-global store and gives access to a session's append-only
 * event log and its derived {@code .meta.json} summary.
 *
 * <p><b>On-disk layout (NFR-LOG-LOCATION).</b> Sessions live under
 * {@code <store>/projects/<repo-key>/sessions/}; each session is a
 * {@code <session-id>.jsonl} event log (the source of truth) beside a
 * {@code <session-id>.meta.json} summary. The store root defaults to
 * {@code ~/.codingagent} (the same root the config store uses) but is injectable so
 * the store is unit-testable against a temporary directory.
 *
 * <p><b>Boundary-captured ids (ADR-0005).</b> Both the {@code repoKey} and the
 * {@code sessionId} are supplied by the caller; this store never derives a repo key
 * from a git remote (a later session task) and never generates a session id with
 * {@code UUID.randomUUID()}. Originals are never deleted (C15 invariant): the store
 * has no delete operation.
 *
 * <p><b>Read-in-seq-order (AC-7.2 capability).</b> {@link #readEvents} returns a
 * session's events parsed in the order written; INV-1 guarantees that order is the
 * gap-free {@code seq} order. The replay reconstruction itself (events &rarr;
 * {@code messages[]}) lives in {@link SessionReplay}; this store provides the ordered
 * read it consumes.
 *
 * <p><b>Listing (AC-7.1 / AC-15.2).</b> {@link #listSessions} enumerates a
 * repository's sessions most-recent-first (by log modification time), so {@code resume}
 * and {@code sessions} can offer a developer their resumable sessions; {@link #readMeta}
 * reads a session's derived {@code .meta.json} summary (its lineage edge feeds the
 * latest-continuation-default walk, AC-7.4).
 */
public final class SessionStore {

    private static final Logger LOGGER = LoggerFactory.getLogger(SessionStore.class);

    private static final String STORE_DIR_NAME = ".codingagent";
    private static final String PROJECTS_DIR_NAME = "projects";
    private static final String SESSIONS_DIR_NAME = "sessions";
    private static final String LOG_SUFFIX = ".jsonl";
    private static final String META_SUFFIX = ".meta.json";

    /**
     * Most-recent-first listing order (AC-7.1): newest log modification time first, ties
     * broken by session id descending so the order is total and deterministic.
     */
    private static final Comparator<SessionListing> MOST_RECENT_FIRST =
            Comparator.comparing(SessionListing::lastModified).reversed()
                    .thenComparing(Comparator.comparing(SessionListing::sessionId).reversed());

    private final Path storeRoot;
    private final EventCodec codec;
    private final ObjectMapper metaMapper;

    /**
     * Creates a store rooted at the given store directory.
     *
     * @param storeRoot the store root ({@code ~/.codingagent} in production); must
     *                  not be {@code null}.
     */
    public SessionStore(Path storeRoot) {
        this.storeRoot = Objects.requireNonNull(storeRoot, "storeRoot");
        this.codec = new EventCodec();
        this.metaMapper = JsonMapper.builder()
                .enable(SerializationFeature.INDENT_OUTPUT)
                .build();
    }

    /**
     * Creates a store rooted at the default store directory ({@code ~/.codingagent}),
     * derived from the {@code user.home} system property.
     *
     * @return a {@code SessionStore} for the current user's home directory.
     */
    public static SessionStore forUserHome() {
        return new SessionStore(Path.of(System.getProperty("user.home"), STORE_DIR_NAME));
    }

    /**
     * Returns the JSONL log path for a session
     * ({@code <store>/projects/<repoKey>/sessions/<sessionId>.jsonl}).
     *
     * @param repoKey   the repository key; non-blank.
     * @param sessionId the session id; non-blank.
     * @return the log path; never {@code null}.
     * @throws IllegalArgumentException if {@code repoKey} or {@code sessionId} is
     *                                  blank.
     */
    public Path logPath(String repoKey, String sessionId) {
        Payloads.requireNonBlank(sessionId, "sessionId");
        return sessionsDir(repoKey).resolve(sessionId + LOG_SUFFIX);
    }

    /**
     * Returns the meta-summary path for a session
     * ({@code <store>/projects/<repoKey>/sessions/<sessionId>.meta.json}).
     *
     * @param repoKey   the repository key; non-blank.
     * @param sessionId the session id; non-blank.
     * @return the meta path; never {@code null}.
     * @throws IllegalArgumentException if {@code repoKey} or {@code sessionId} is
     *                                  blank.
     */
    public Path metaPath(String repoKey, String sessionId) {
        Payloads.requireNonBlank(sessionId, "sessionId");
        return sessionsDir(repoKey).resolve(sessionId + META_SUFFIX);
    }

    /**
     * Opens the session's event log for appending, creating the sessions directory
     * and the log file if absent. Sequence numbering continues after the events
     * already in the file, so reopening a session never reuses or skips a {@code seq}
     * (INV-1 across opens).
     *
     * @param repoKey   the repository key; non-blank.
     * @param sessionId the session id (captured at the boundary); non-blank.
     * @return an open {@link EventLog} positioned to append after existing events.
     * @throws IllegalArgumentException if {@code repoKey} or {@code sessionId} is
     *                                  blank.
     * @throws PersistenceException     if the directory or file cannot be prepared.
     */
    public EventLog openLog(String repoKey, String sessionId) {
        Path log = logPath(repoKey, sessionId);
        createSessionsDir(repoKey);
        int existing = countEvents(log);
        return EventLog.openForAppend(log, existing);
    }

    /**
     * Reads a session's events in written (seq) order.
     *
     * @param repoKey   the repository key; non-blank.
     * @param sessionId the session id; non-blank.
     * @return the events in order; an empty list if the session has no log yet. Never
     *         {@code null}.
     * @throws IllegalArgumentException if {@code repoKey} or {@code sessionId} is
     *                                  blank.
     * @throws PersistenceException     if the log exists but cannot be read, or a
     *                                  line is corrupt (AC-7.5 — surfaced, not a
     *                                  crash).
     */
    public List<Event> readEvents(String repoKey, String sessionId) {
        Path log = logPath(repoKey, sessionId);
        if (!Files.exists(log)) {
            return List.of();
        }
        List<Event> events = new ArrayList<>();
        try {
            List<String> lines = Files.readAllLines(log, StandardCharsets.UTF_8);
            for (String line : lines) {
                if (!line.isBlank()) {
                    events.add(codec.decode(line));
                }
            }
        } catch (IOException e) {
            throw new PersistenceException("failed to read session log: " + log, e);
        }
        LOGGER.debug("read {} events from {}", events.size(), log);
        return events;
    }

    /**
     * Lists a repository's sessions, most-recent-first (AC-7.1 / AC-15.2). Each entry
     * carries the session id, the session log's last-modified time (the ordering key),
     * and the lineage edge read from the session's {@code .meta.json} summary when one is
     * present (so a continuation can be recognized for the latest-continuation-default
     * walk, AC-7.4).
     *
     * <p><b>Ordering.</b> Sessions are ordered by log modification time, newest first —
     * the directly observable most-recent-activity signal, which is robust whether or not
     * a session id is timestamp-prefixed (the timestamp-prefixed ids of 03-data-model §
     * 2.1 sort the same way, but the M0 one-shot lineage id {@code "one-shot"} is not
     * timestamp-prefixed, so modification time is the defensible total order). Ties (two
     * logs with the same modification time) break by session id descending, so the order
     * is total and deterministic.
     *
     * @param repoKey the repository key; non-blank.
     * @return the sessions under {@code repoKey}, most-recent-first; an empty list when
     *         the repository has no sessions directory or no session logs. Never
     *         {@code null}.
     * @throws IllegalArgumentException if {@code repoKey} is blank.
     * @throws PersistenceException     if the sessions directory cannot be listed.
     */
    public List<SessionListing> listSessions(String repoKey) {
        Path dir = sessionsDir(repoKey);
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        List<SessionListing> listings = new ArrayList<>();
        try (Stream<Path> entries = Files.list(dir)) {
            entries.filter(SessionStore::isSessionLog)
                    .forEach(log -> listings.add(toListing(repoKey, log)));
        } catch (IOException e) {
            throw new PersistenceException("failed to list sessions directory: " + dir, e);
        }
        listings.sort(MOST_RECENT_FIRST);
        LOGGER.debug("listed {} session(s) under {}", listings.size(), dir);
        return listings;
    }

    /**
     * Reads a session's derived {@code .meta.json} summary, if one has been written.
     *
     * @param repoKey   the repository key; non-blank.
     * @param sessionId the session id; non-blank.
     * @return the persisted summary, or {@link Optional#empty()} when no meta file exists
     *         for the session.
     * @throws IllegalArgumentException if {@code repoKey} or {@code sessionId} is blank.
     * @throws PersistenceException     if the meta file exists but cannot be read or
     *                                  parsed (surfaced, not a crash).
     */
    public Optional<SessionMeta> readMeta(String repoKey, String sessionId) {
        Path path = metaPath(repoKey, sessionId);
        if (!Files.exists(path)) {
            return Optional.empty();
        }
        try {
            String json = Files.readString(path, StandardCharsets.UTF_8);
            return Optional.of(metaMapper.readValue(json, SessionMeta.class));
        } catch (IOException e) {
            throw new PersistenceException("failed to read session meta: " + path, e);
        }
    }

    /**
     * Writes the session's {@code .meta.json} summary, overwriting any existing one.
     * The meta file is a derived cache; this method does not touch the JSONL log.
     *
     * @param meta the summary to write; must not be {@code null}.
     * @throws NullPointerException if {@code meta} is {@code null}.
     * @throws PersistenceException if the summary cannot be written.
     */
    public void writeMeta(SessionMeta meta) {
        Objects.requireNonNull(meta, "meta");
        Path path = metaPath(meta.repoKey(), meta.sessionId());
        createSessionsDir(meta.repoKey());
        try {
            String json = metaMapper.writeValueAsString(meta);
            Files.writeString(path, json, StandardCharsets.UTF_8);
            LOGGER.info("wrote session meta: {}", path);
        } catch (JsonProcessingException e) {
            throw new EventSerializationException("failed to serialize session meta: " + path, e);
        } catch (IOException e) {
            throw new PersistenceException("failed to write session meta: " + path, e);
        }
    }

    /**
     * Derives a {@link SessionMeta} summary from a session's event log by reading it
     * in order and aggregating the envelope facts: event count, summed usage tokens,
     * and the last recorded outcome's success flag. Lineage edges
     * ({@code parentSessionId}/{@code edgeType}) are not derivable from a single
     * session's own events, so they are left {@code null} here; the lineage task sets
     * them when it records an edge.
     *
     * @param repoKey   the repository key; non-blank.
     * @param sessionId the session id; non-blank.
     * @param status    the lifecycle status to record.
     * @return a summary derived from the log.
     * @throws NullPointerException     if {@code status} is {@code null}.
     * @throws IllegalArgumentException if {@code repoKey} or {@code sessionId} is
     *                                  blank.
     * @throws PersistenceException     if the log cannot be read.
     */
    public SessionMeta deriveMeta(String repoKey, String sessionId, SessionStatus status) {
        Objects.requireNonNull(status, "status");
        List<Event> events = readEvents(repoKey, sessionId);
        int inputTokens = 0;
        int outputTokens = 0;
        Boolean outcomeSuccess = null;
        for (Event event : events) {
            if (event.payload() instanceof ModelUsagePayload usage) {
                inputTokens += usage.inputTokens();
                outputTokens += usage.outputTokens();
            } else if (event.payload() instanceof OutcomePayload outcome) {
                outcomeSuccess = outcome.success();
            }
        }
        return new SessionMeta(sessionId, repoKey, status, events.size(),
                inputTokens, outputTokens, null, null, outcomeSuccess);
    }

    private Path sessionsDir(String repoKey) {
        Payloads.requireNonBlank(repoKey, "repoKey");
        return storeRoot.resolve(PROJECTS_DIR_NAME).resolve(repoKey).resolve(SESSIONS_DIR_NAME);
    }

    private void createSessionsDir(String repoKey) {
        Path dir = sessionsDir(repoKey);
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new PersistenceException("failed to create sessions directory: " + dir, e);
        }
    }

    private int countEvents(Path log) {
        if (!Files.exists(log)) {
            return 0;
        }
        try (var lines = Files.lines(log, StandardCharsets.UTF_8)) {
            return (int) lines.filter(line -> !line.isBlank()).count();
        } catch (IOException e) {
            throw new PersistenceException("failed to count events in session log: " + log, e);
        }
    }

    private static boolean isSessionLog(Path path) {
        String name = path.getFileName().toString();
        // A session log ends in .jsonl but is not the .meta.json sibling (which does not).
        return name.endsWith(LOG_SUFFIX) && Files.isRegularFile(path);
    }

    /** Builds a listing for one session log: id from the file name, mtime, meta lineage. */
    private SessionListing toListing(String repoKey, Path log) {
        String name = log.getFileName().toString();
        String sessionId = name.substring(0, name.length() - LOG_SUFFIX.length());
        Instant lastModified = lastModifiedOf(log);
        Optional<SessionMeta> meta = readMeta(repoKey, sessionId);
        String parent = meta.map(SessionMeta::parentSessionId).orElse(null);
        EdgeType edge = meta.map(SessionMeta::edgeType).orElse(null);
        return new SessionListing(sessionId, lastModified, parent, edge);
    }

    private static Instant lastModifiedOf(Path log) {
        try {
            return Files.getLastModifiedTime(log).toInstant();
        } catch (IOException e) {
            throw new PersistenceException("failed to stat session log: " + log, e);
        }
    }
}
