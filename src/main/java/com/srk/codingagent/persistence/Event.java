package com.srk.codingagent.persistence;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.util.Objects;

/**
 * One line of a session's append-only JSONL event log: the common envelope
 * (sequence number, timestamp, type) plus a type-specific {@link EventPayload}
 * (ADR-0005; {@code 03-data-model.md} § 2.2; {@code 06-formal/event.schema.json}).
 *
 * <p><b>Boundary-captured identity (ADR-0005, load-bearing).</b> Both {@code seq}
 * and {@code ts} are supplied by the caller, never derived in-process. The writer
 * assigns {@code seq} monotonically at the I/O boundary (INV-1) and the timestamp is
 * captured where the event occurs and passed in (the design avoids
 * {@code Instant.now()} so the log is reproducible and tests are deterministic).
 *
 * <p><b>Derived type (no drift).</b> The {@code type} field emitted in JSON is
 * derived from {@code payload.type()} rather than stored independently, so an event
 * cannot claim a type that disagrees with its payload. The envelope's serialized
 * field order is {@code seq, ts, type, payload}, matching the schema and the
 * contract fixture.
 *
 * @param seq     the monotonic, gap-free per-session sequence number; {@code >= 0}
 *                (INV-1). Assigned by the {@link EventLog} at append time.
 * @param ts      the ISO-8601 timestamp, captured at the I/O boundary; non-blank.
 * @param payload the type-specific body; must not be {@code null}.
 */
@JsonPropertyOrder({"seq", "ts", "type", "payload"})
public record Event(int seq, String ts, EventPayload payload) {

    /**
     * Validates the envelope.
     *
     * @throws IllegalArgumentException if {@code seq} is negative or {@code ts} is
     *                                  blank.
     * @throws NullPointerException     if {@code payload} is {@code null}.
     */
    public Event {
        Payloads.requireAtLeast(seq, 0, "seq");
        Payloads.requireNonBlank(ts, "ts");
        Objects.requireNonNull(payload, "payload");
    }

    /**
     * The event type, derived from the payload so envelope and body cannot drift.
     * Emitted as the JSONL {@code type} field.
     *
     * @return the event type; never {@code null}.
     */
    @JsonProperty("type")
    public EventType type() {
        return payload.type();
    }

    /**
     * Returns a copy of this event with a new sequence number, leaving timestamp and
     * payload unchanged. Used by the {@link EventLog} to stamp a caller-built event
     * with the next monotonic {@code seq} at append time; the event itself is never
     * mutated (append-only, INV-1).
     *
     * @param newSeq the sequence number to assign; {@code >= 0}.
     * @return a new {@code Event} with {@code seq == newSeq}.
     * @throws IllegalArgumentException if {@code newSeq} is negative.
     */
    public Event withSeq(int newSeq) {
        return new Event(newSeq, ts, payload);
    }
}
