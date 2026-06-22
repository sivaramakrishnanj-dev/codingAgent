package com.srk.codingagent.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link MemoryMarkdown} render / parse (component C16, ADR-0007, AC-12.2,
 * AC-14.1). The serializer is the SUT.
 *
 * <p>Oracles: AC-12.2 / AC-14.1 (a memory entry is human-readable markdown with front-matter
 * provenance), the memory-entry schema's quoted-string {@code created} requirement (the
 * fixture quotes the ISO timestamp so YAML keeps it a string), and the parser's robustness to
 * a hand-edited / CRLF file (INV-14: external edits are honoured).
 */
class MemoryMarkdownTest {

    private MemoryEntry entry(String body) {
        return new MemoryEntry("round-trip", MemoryTier.PROJECT, "2026-06-22T10:30:00Z",
                "2026-06-22T09-00-00-sess", "the reason", MemoryStatus.ACTIVE, body);
    }

    @Test
    @DisplayName("AC-12.2 / AC-14.1: render then parse round-trips an entry unchanged")
    void roundTrips() {
        MemoryEntry original = entry("The learning body, with a `code span`.");

        MemoryEntry decoded = MemoryMarkdown.parse(MemoryMarkdown.render(original));

        assertEquals(original.slug(), decoded.slug());
        assertEquals(original.tier(), decoded.tier());
        assertEquals(original.created(), decoded.created());
        assertEquals(original.originSession(), decoded.originSession());
        assertEquals(original.why(), decoded.why());
        assertEquals(original.status(), decoded.status());
        assertEquals(original.body().strip(), decoded.body().strip());
    }

    @Test
    @DisplayName("schema: the created timestamp is emitted as a QUOTED string")
    void createdIsQuoted() {
        // Oracle: memory-entry.schema.json types `created` as a string, and the validated
        // fixture quotes it — an unquoted ISO datetime makes YAML parse it as a timestamp
        // type. The rendered front-matter must quote it.
        String text = MemoryMarkdown.render(entry("b"));

        assertTrue(text.contains("created: \"2026-06-22T10:30:00Z\""),
                "created is emitted quoted so YAML keeps it a string: " + text);
    }

    @Test
    @DisplayName("AC-14.2 / INV-14: a CRLF hand-edited file still parses")
    void parsesCrlfFile() {
        // Oracle: INV-14 — a hand-edited file (a Windows editor may save CRLF) is honoured on
        // the next load. The parser normalizes line endings.
        String crlf = MemoryMarkdown.render(entry("body line")).replace("\n", "\r\n");

        MemoryEntry decoded = MemoryMarkdown.parse(crlf);

        assertEquals("round-trip", decoded.slug());
        assertEquals("body line", decoded.body().strip());
    }

    @Test
    @DisplayName("a file with no front-matter block is rejected")
    void rejectsMissingFrontMatter() {
        assertThrows(MemoryStoreException.class,
                () -> MemoryMarkdown.parse("just some prose, no front-matter"));
    }

    @Test
    @DisplayName("a front-matter block missing a required field is rejected")
    void rejectsMissingField() {
        String missingStatus = "---\nslug: a-slug\ntier: GLOBAL\ncreated: \"2026-06-22T10:30:00Z\"\n"
                + "originSession: sess\nwhy: w\n---\n\nbody\n";

        assertThrows(MemoryStoreException.class, () -> MemoryMarkdown.parse(missingStatus));
    }

    @Test
    @DisplayName("a front-matter block with an out-of-enum tier is rejected")
    void rejectsBadTier() {
        String badTier = "---\nslug: a-slug\ntier: LOCAL\ncreated: \"2026-06-22T10:30:00Z\"\n"
                + "originSession: sess\nwhy: w\nstatus: active\n---\n\nbody\n";

        assertThrows(MemoryStoreException.class, () -> MemoryMarkdown.parse(badTier));
    }
}
