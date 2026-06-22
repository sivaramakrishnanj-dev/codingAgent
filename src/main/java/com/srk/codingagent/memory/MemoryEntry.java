package com.srk.codingagent.memory;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * One curated memory entry (component C16, ADR-0007, RD-9; {@code 03-data-model.md} § 2.5):
 * a single learning persisted as a human-readable markdown file {@code <slug>.md} — the
 * schema-pinned front-matter ({@code slug}, {@code tier}, {@code created},
 * {@code originSession}, {@code why}, {@code status}) plus the learning prose as the
 * markdown {@code body} (AC-12.2, AC-14.1).
 *
 * <p>The record is immutable and self-validating in its canonical constructor against the
 * formal schema ({@code 06-formal/memory-entry.schema.json}): {@code slug} matches the
 * kebab-case pattern the schema pins, {@code why} is non-blank ({@code minLength 1}), and
 * the enum fields are non-{@code null}. {@code created} is the boundary-captured ISO
 * timestamp string (ADR-0007 / ADR-0005 — captured by the caller's clock, never an
 * in-process {@code Instant.now()}). Building an invalid entry fails fast, so the store
 * never serializes a front-matter block that would not validate (CT-SCH-11/12).
 *
 * @param slug          the kebab-case filename id; must match {@code ^[a-z0-9]+(-[a-z0-9]+)*$}.
 * @param tier          the storage tier ({@code GLOBAL} / {@code PROJECT}, AC-12.3); non-{@code null}.
 * @param created       the ISO-8601 timestamp captured at the boundary; non-blank.
 * @param originSession the provenance session id (AC-12.2); non-blank.
 * @param why           the one-line provenance (AC-12.2); non-blank.
 * @param status        the lifecycle status ({@code ACTIVE} / {@code RETIRED}); non-{@code null}.
 * @param body          the learning prose (the markdown below the front-matter, AC-14.1);
 *                      non-{@code null} (may be empty).
 */
public record MemoryEntry(
        String slug,
        MemoryTier tier,
        String created,
        String originSession,
        String why,
        MemoryStatus status,
        String body) {

    /** The kebab-case slug pattern the schema pins ({@code memory-entry.schema.json}). */
    private static final Pattern SLUG_PATTERN = Pattern.compile("^[a-z0-9]+(-[a-z0-9]+)*$");

    /**
     * Validates the entry against the schema-pinned constraints.
     *
     * @throws NullPointerException     if {@code tier}, {@code status}, or {@code body} is
     *                                  {@code null}.
     * @throws IllegalArgumentException if {@code slug} is not kebab-case, or {@code created},
     *                                  {@code originSession}, or {@code why} is blank.
     */
    public MemoryEntry {
        Objects.requireNonNull(tier, "tier");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(body, "body");
        requireSlug(slug);
        requireNonBlank(created, "created");
        requireNonBlank(originSession, "originSession");
        requireNonBlank(why, "why");
    }

    private static void requireSlug(String slug) {
        Objects.requireNonNull(slug, "slug");
        if (!SLUG_PATTERN.matcher(slug).matches()) {
            throw new IllegalArgumentException(
                    "slug must be kebab-case (matching " + SLUG_PATTERN.pattern() + ") but was '" + slug + "'");
        }
    }

    private static void requireNonBlank(String value, String field) {
        if (Objects.requireNonNull(value, field).isBlank()) {
            throw new IllegalArgumentException(field + " must be non-blank");
        }
    }
}
