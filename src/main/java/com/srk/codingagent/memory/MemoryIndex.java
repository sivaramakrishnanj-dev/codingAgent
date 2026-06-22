package com.srk.codingagent.memory;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Renders and parses a tier's {@code INDEX.md} lines in the {@code - [slug] description}
 * format ADR-0007 pins (AC-14.3). One line per entry; the description is the entry's
 * one-line provenance ({@code why}) so the always-loaded index surface stays a quick-review
 * digest without the full prose body.
 *
 * <p>Parsing is lenient about surrounding whitespace and ignores any line that is not a
 * well-formed index line (a blank line, a heading a human added) — so a hand-edited index
 * is still readable on the next re-read-fresh load (INV-14). Package-private; the store
 * owns the index files.
 */
final class MemoryIndex {

    /** {@code - [slug] description} — slug in brackets, then the rest of the line as text. */
    private static final Pattern LINE_PATTERN =
            Pattern.compile("^\\s*-\\s*\\[(?<slug>[^\\]]+)\\]\\s?(?<desc>.*)$");

    private MemoryIndex() {
        // Non-instantiable.
    }

    /**
     * Renders an entry's index line.
     *
     * @param entry the entry; must not be {@code null}.
     * @return the {@code - [slug] description} line (no trailing newline).
     */
    static String renderLine(MemoryEntry entry) {
        return "- [" + entry.slug() + "] " + entry.why();
    }

    /**
     * Parses an index line into a {@link MemoryIndexLine}, returning empty for a line that is
     * not a well-formed index entry (blank, comment, heading).
     *
     * @param tier the tier the index belongs to; used to stamp the parsed line.
     * @param line one raw line of an {@code INDEX.md}.
     * @return the parsed index line, or {@link Optional#empty()} for a non-entry line.
     */
    static Optional<MemoryIndexLine> parseLine(MemoryTier tier, String line) {
        Matcher m = LINE_PATTERN.matcher(line);
        if (!m.matches()) {
            return Optional.empty();
        }
        String slug = m.group("slug").trim();
        if (slug.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new MemoryIndexLine(tier, slug, m.group("desc").trim()));
    }

    /**
     * Whether a raw index line refers to the given slug (used to replace an entry's line on
     * re-write without disturbing the others).
     *
     * @param line one raw line of an {@code INDEX.md}.
     * @param slug the slug to match.
     * @return {@code true} if the line is a well-formed index line for {@code slug}.
     */
    static boolean isLineFor(String line, String slug) {
        Matcher m = LINE_PATTERN.matcher(line);
        return m.matches() && m.group("slug").trim().equals(slug);
    }
}
