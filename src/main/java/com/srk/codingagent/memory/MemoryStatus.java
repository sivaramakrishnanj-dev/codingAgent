package com.srk.codingagent.memory;

/**
 * The lifecycle status of a curated {@link MemoryEntry} (component C16, ADR-0007;
 * {@code 03-data-model.md} § 2.5). An active entry is part of the live memory surface; a
 * retired one is kept for audit but no longer surfaced (retirement is a human edit on the
 * markdown file — the store honours whatever the file says on the next re-read-fresh load,
 * INV-14).
 *
 * <p><b>Wire vocabulary (load-bearing).</b> The formal schema
 * ({@code 06-formal/memory-entry.schema.json}, {@code status} enum) pins the front-matter
 * value as <em>lowercase</em> {@code active} / {@code retired}, while these Java constants
 * follow the conventional uppercase naming. {@link #wireValue()} bridges the two so the
 * emitted front-matter validates against the schema (CT-SCH-11) and {@link #fromWire(String)}
 * parses it back. (The schema + validated fixture are the formal contract;
 * {@code 03-data-model.md} § 2.5 names the enum {@code ACTIVE}/{@code RETIRED} in
 * documentation prose — the lowercase wire values are what the schema validates against.)
 */
public enum MemoryStatus {

    /** The entry is part of the live memory surface. */
    ACTIVE,

    /** The entry is kept for audit but no longer surfaced. */
    RETIRED;

    /**
     * The schema's lowercase wire value for this status, emitted into the markdown
     * front-matter ({@code 06-formal/memory-entry.schema.json}).
     *
     * @return the lowercase status token (e.g. {@code "active"}).
     */
    public String wireValue() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * Parses a front-matter status token (the schema's lowercase form) back into a
     * {@code MemoryStatus}.
     *
     * @param wire the status token read from front-matter; must not be {@code null}.
     * @return the matching status.
     * @throws NullPointerException     if {@code wire} is {@code null}.
     * @throws IllegalArgumentException if {@code wire} is not a recognized status value.
     */
    public static MemoryStatus fromWire(String wire) {
        java.util.Objects.requireNonNull(wire, "wire");
        return valueOf(wire.toUpperCase(java.util.Locale.ROOT));
    }
}
