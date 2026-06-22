package com.srk.codingagent.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the {@link MemoryEntry} record's self-validation (component C16, ADR-0007,
 * {@code 06-formal/memory-entry.schema.json}). The record is the SUT — the constructor's
 * validation is real behaviour, never mocked.
 *
 * <p>Oracle: the schema's field constraints — {@code slug} matches
 * {@code ^[a-z0-9]+(-[a-z0-9]+)*$}, {@code why} has {@code minLength 1}, the enum + required
 * fields are present. An invalid entry must fail fast so the store never serializes invalid
 * front-matter (CT-SCH-11).
 */
class MemoryEntryTest {

    private MemoryEntry valid() {
        return new MemoryEntry("ok-slug", MemoryTier.GLOBAL, "2026-06-22T10:00:00Z",
                "sess", "why", MemoryStatus.ACTIVE, "body");
    }

    @Test
    @DisplayName("schema: a kebab-case slug, present fields, and non-blank why build a valid entry")
    void buildsValidEntry() {
        MemoryEntry entry = valid();
        assertEquals("ok-slug", entry.slug());
        assertEquals(MemoryTier.GLOBAL, entry.tier());
        assertEquals(MemoryStatus.ACTIVE, entry.status());
    }

    @Test
    @DisplayName("schema slug pattern: a non-kebab slug is rejected")
    void rejectsNonKebabSlug() {
        // Oracle: schema slug pattern ^[a-z0-9]+(-[a-z0-9]+)*$ — uppercase, spaces, and
        // leading/trailing hyphens do not match.
        assertThrows(IllegalArgumentException.class,
                () -> new MemoryEntry("Not Kebab", MemoryTier.GLOBAL, "2026-06-22T10:00:00Z",
                        "s", "w", MemoryStatus.ACTIVE, "b"),
                "a slug with spaces/uppercase is rejected");
        assertThrows(IllegalArgumentException.class,
                () -> new MemoryEntry("-leading", MemoryTier.GLOBAL, "2026-06-22T10:00:00Z",
                        "s", "w", MemoryStatus.ACTIVE, "b"),
                "a slug with a leading hyphen is rejected");
    }

    @Test
    @DisplayName("schema minLength 1: a blank why is rejected")
    void rejectsBlankWhy() {
        // Oracle: schema why has minLength 1 (one-line provenance is required).
        assertThrows(IllegalArgumentException.class,
                () -> new MemoryEntry("ok-slug", MemoryTier.GLOBAL, "2026-06-22T10:00:00Z",
                        "s", " ", MemoryStatus.ACTIVE, "b"));
    }

    @Test
    @DisplayName("schema required: a blank created or originSession is rejected")
    void rejectsBlankProvenance() {
        assertThrows(IllegalArgumentException.class,
                () -> new MemoryEntry("ok-slug", MemoryTier.GLOBAL, " ",
                        "s", "w", MemoryStatus.ACTIVE, "b"),
                "a blank created is rejected");
        assertThrows(IllegalArgumentException.class,
                () -> new MemoryEntry("ok-slug", MemoryTier.GLOBAL, "2026-06-22T10:00:00Z",
                        " ", "w", MemoryStatus.ACTIVE, "b"),
                "a blank originSession is rejected");
    }

    @Test
    @DisplayName("schema enum fields and body are non-null")
    void rejectsNullEnumOrBody() {
        assertThrows(NullPointerException.class,
                () -> new MemoryEntry("ok-slug", null, "2026-06-22T10:00:00Z",
                        "s", "w", MemoryStatus.ACTIVE, "b"));
        assertThrows(NullPointerException.class,
                () -> new MemoryEntry("ok-slug", MemoryTier.GLOBAL, "2026-06-22T10:00:00Z",
                        "s", "w", null, "b"));
        assertThrows(NullPointerException.class,
                () -> new MemoryEntry("ok-slug", MemoryTier.GLOBAL, "2026-06-22T10:00:00Z",
                        "s", "w", MemoryStatus.ACTIVE, null));
    }
}
