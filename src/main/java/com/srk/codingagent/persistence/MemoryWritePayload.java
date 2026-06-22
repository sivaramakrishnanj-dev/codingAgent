package com.srk.codingagent.persistence;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The body of a {@code MEMORY_WRITE} event: the provenance marker appended to a session's
 * log whenever a curated memory entry is written (ADR-0007 — "every write logs a
 * {@code memory_write} event"; AC-12.4 — "the agent shall record memory writes as events
 * in the session log"). Recording the write as an event gives a memory entry an audit /
 * rollback trail in the append-only session log: who wrote it, into which tier, and why
 * ({@code 03-data-model.md} § 2.5 provenance fields).
 *
 * <p>The event schema ({@code 06-formal/event.schema.json}) enumerates {@code MEMORY_WRITE}
 * in its {@code type} set but pins its {@code payload} only as a generic object (it has no
 * dedicated {@code $defs.memoryWrite}, and no {@code allOf} branch narrows it), so this
 * shape is the code-level realization of the documented memory-write fact — the same
 * arrangement T-2.2 used for {@code ERROR} and T-2.3 for the sub-agent edges. The payload
 * mirrors the memory-entry's identifying + provenance fields (slug, tier, originating
 * session, why) rather than duplicating the whole entry: the entry's prose body lives in
 * the markdown file (the human-editable source of truth), not in the event.
 *
 * <p>The {@code tier} is the schema's uppercase wire vocabulary ({@code GLOBAL} /
 * {@code PROJECT}); a memory-write event is never auto-emitted — it is appended only on an
 * explicit (T-2.4) or approved (T-2.5) write (INV-13).
 *
 * @param slug          the written entry's kebab-case slug (its filename id); non-blank.
 * @param tier          the tier the entry was classified into ({@code GLOBAL} /
 *                      {@code PROJECT}, AC-12.3); non-blank.
 * @param originSession the session that produced the write (provenance, AC-12.2); non-blank.
 * @param why           the one-line provenance recorded with the entry (AC-12.2); non-blank.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MemoryWritePayload(
        String slug,
        String tier,
        String originSession,
        String why) implements EventPayload {

    /**
     * Validates the payload.
     *
     * @throws NullPointerException     if any field is {@code null}.
     * @throws IllegalArgumentException if {@code slug}, {@code tier}, {@code originSession},
     *                                  or {@code why} is blank.
     */
    public MemoryWritePayload {
        Payloads.requireNonBlank(slug, "slug");
        Payloads.requireNonBlank(tier, "tier");
        Payloads.requireNonBlank(originSession, "originSession");
        Payloads.requireNonBlank(why, "why");
    }

    @Override
    public EventType type() {
        return EventType.MEMORY_WRITE;
    }
}
