package com.srk.codingagent.persistence;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The per-session append-only JSONL event log (component C14, ADR-0005). Appends
 * every interaction event to one line of a session file and flushes per event so a
 * crash loses at most one in-flight event.
 *
 * <p><b>Append-only (INV-1).</b> The only mutating operation is {@link #append};
 * there is no update or delete method by design — the absence of those methods, not
 * a runtime guard, is what enforces append-only at the type's surface (CT-INV-1).
 * Each append assigns the next {@code seq} monotonically from {@code 0}, gap-free,
 * so the caller need not (and cannot) choose a sequence number.
 *
 * <p><b>Flush-before-act (INV-2, NFR-LOG-DURABILITY).</b> {@link #append} writes the
 * line and flushes it to the underlying stream before returning, so a caller that
 * acts on an event's consequence only does so after the event is durably recorded.
 *
 * <p><b>Persist failure is surfaced (AC-13.4).</b> If the write or flush fails,
 * {@link #append} throws a {@link PersistenceException} rather than returning, so the
 * caller cannot mistake an un-persisted event for a logged one.
 *
 * <p>Boundary-captured identity (ADR-0005): the caller supplies each event's
 * timestamp; this writer never calls {@code Instant.now()}. It only owns the
 * sequence numbering, which it derives deterministically from the count of events
 * already appended.
 *
 * <p>Not thread-safe: a session log is written by a single loop. Construct one per
 * open session file; {@link #close()} closes the underlying writer.
 */
public final class EventLog implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(EventLog.class);

    private final Writer writer;
    private final String label;
    private final EventCodec codec;
    private int nextSeq;

    private EventLog(Writer writer, String label, EventCodec codec, int firstSeq) {
        this.writer = writer;
        this.label = label;
        this.codec = codec;
        this.nextSeq = firstSeq;
    }

    /**
     * Opens (creating if absent) the session log at {@code path} for appending,
     * continuing the sequence numbering after the {@code existingEventCount} events
     * already present. The parent directory must already exist (the session store
     * creates it).
     *
     * @param path               the session JSONL file; must not be {@code null}.
     * @param existingEventCount the number of events already in the file; {@code >= 0}.
     *                           The next append gets {@code seq == existingEventCount}.
     * @return an open event log positioned to append after the existing events.
     * @throws NullPointerException     if {@code path} is {@code null}.
     * @throws IllegalArgumentException if {@code existingEventCount} is negative.
     * @throws PersistenceException     if the file cannot be opened for appending.
     */
    public static EventLog openForAppend(Path path, int existingEventCount) {
        Objects.requireNonNull(path, "path");
        Payloads.requireAtLeast(existingEventCount, 0, "existingEventCount");
        try {
            Writer fileWriter = new BufferedWriter(Files.newBufferedWriter(
                    path, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND));
            LOGGER.info("opened session event log for append: {} (next seq {})", path, existingEventCount);
            return new EventLog(fileWriter, path.toString(), new EventCodec(), existingEventCount);
        } catch (IOException e) {
            throw new PersistenceException("failed to open session event log: " + path, e);
        }
    }

    /**
     * Creates an event log over an arbitrary writer, numbering from {@code seq 0}.
     * The test seam for exercising append/flush and the AC-13.4 failure path without
     * a real file; production code uses {@link #openForAppend(Path, int)}.
     *
     * @param writer the destination; must not be {@code null}. The caller's writer is
     *               flushed per event and closed by {@link #close()}.
     * @param label  a label for diagnostics (a path or synthetic name); must not be
     *               {@code null}.
     * @return an event log writing to {@code writer}, first seq {@code 0}.
     * @throws NullPointerException if {@code writer} or {@code label} is {@code null}.
     */
    public static EventLog over(Writer writer, String label) {
        return new EventLog(
                Objects.requireNonNull(writer, "writer"),
                Objects.requireNonNull(label, "label"),
                new EventCodec(),
                0);
    }

    /**
     * Appends an event, assigning it the next monotonic {@code seq}, then flushes it
     * to disk before returning (INV-1, INV-2).
     *
     * <p>The caller-built event's own {@code seq} is ignored and replaced with the
     * next sequence number this log assigns, so sequence numbering stays the log's
     * sole responsibility and cannot be made non-monotonic by a caller.
     *
     * @param event the event to append; must not be {@code null}. Its timestamp and
     *              payload are used verbatim; its sequence number is reassigned.
     * @return the event as appended, carrying the assigned {@code seq}.
     * @throws NullPointerException if {@code event} is {@code null}.
     * @throws PersistenceException if the event cannot be written and flushed
     *                              (AC-13.4 — the failure is surfaced, not swallowed).
     */
    public Event append(Event event) {
        Objects.requireNonNull(event, "event");
        Event stamped = event.withSeq(nextSeq);
        String line = codec.encode(stamped);
        try {
            writer.write(line);
            writer.write('\n');
            writer.flush();
        } catch (IOException e) {
            LOGGER.error("failed to persist event seq={} to {}", stamped.seq(), label, e);
            throw new PersistenceException(
                    "failed to persist event seq=" + stamped.seq() + " to " + label, e);
        }
        nextSeq++;
        return stamped;
    }

    /**
     * The sequence number the next {@link #append} will assign (equivalently, the
     * count of events appended through this log plus any pre-existing ones).
     *
     * @return the next sequence number; {@code >= 0}.
     */
    public int nextSeq() {
        return nextSeq;
    }

    /**
     * Closes the underlying writer.
     *
     * @throws PersistenceException if the writer cannot be closed.
     */
    @Override
    public void close() {
        try {
            writer.close();
        } catch (IOException e) {
            throw new PersistenceException("failed to close session event log: " + label, e);
        }
    }
}
