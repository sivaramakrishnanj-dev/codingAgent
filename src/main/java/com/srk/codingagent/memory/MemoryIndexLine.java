package com.srk.codingagent.memory;

import java.util.Objects;

/**
 * One line of a tier's {@code INDEX.md} — the always-loaded awareness surface for a memory
 * entry (ADR-0007, AC-14.3): a {@code slug} and a one-line {@code description}. The index
 * is the cacheable static-prefix surface (ADR-0006) the system prompt would carry; the full
 * entry body is pulled on demand via {@code read_memory(slug)} only when needed.
 *
 * <p>Rendered to / parsed from the {@code - [slug] description} line format ADR-0007 pins.
 *
 * @param tier        the tier this index line belongs to ({@code GLOBAL} / {@code PROJECT});
 *                    non-{@code null}.
 * @param slug        the entry's kebab-case slug; non-blank.
 * @param description the one-line description shown in the index; non-{@code null} (may be
 *                    empty if a hand-edited line omitted it).
 */
public record MemoryIndexLine(MemoryTier tier, String slug, String description) {

    /**
     * Validates the index line.
     *
     * @throws NullPointerException     if {@code tier} or {@code description} is {@code null}.
     * @throws IllegalArgumentException if {@code slug} is blank.
     */
    public MemoryIndexLine {
        Objects.requireNonNull(tier, "tier");
        Objects.requireNonNull(description, "description");
        if (Objects.requireNonNull(slug, "slug").isBlank()) {
            throw new IllegalArgumentException("slug must be non-blank");
        }
    }
}
