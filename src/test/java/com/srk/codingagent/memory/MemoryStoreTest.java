package com.srk.codingagent.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for the two-tier curated-memory store (component C16, ADR-0007). The {@link MemoryStore}
 * is the SUT and reads/writes real markdown files under a JUnit {@link TempDir} (the filesystem
 * is the genuine collaborator — re-read-fresh is meaningless against a mock).
 *
 * <p>Oracles: AC-12.3 (classify GLOBAL/PROJECT and store in the corresponding tier), AC-14.1
 * (human-readable markdown a human can inspect/edit/delete), AC-14.2 / INV-14 / CT-INV-12
 * (external edit/delete honoured on next load — no masking cache), AC-14.3 (a memory index
 * lists entries), and ADR-0007's two-tier {@code ~/.codingagent} layout.
 */
class MemoryStoreTest {

    private static final String REPO_KEY = "github.com_srk_codingagent";

    private MemoryEntry entry(String slug, MemoryTier tier, String why, String body) {
        return new MemoryEntry(slug, tier, "2026-06-22T10:00:00Z",
                "2026-06-22T09-00-00-sess", why, MemoryStatus.ACTIVE, body);
    }

    @Test
    @DisplayName("AC-12.3 / ADR-0007: a GLOBAL entry lands under <store>/memory/")
    void globalEntryLandsInGlobalTier(@TempDir Path store) {
        // Oracle: AC-12.3 / ADR-0007 — GLOBAL memory lives under <store>/memory/ (cross-project).
        MemoryStore memory = new MemoryStore(store);

        memory.write(entry("a-slug", MemoryTier.GLOBAL, "global why", "global body"), REPO_KEY);

        assertTrue(Files.isRegularFile(store.resolve("memory/a-slug.md")),
                "a GLOBAL entry is written under <store>/memory/ (AC-12.3, ADR-0007)");
        assertFalse(Files.exists(store.resolve("projects/" + REPO_KEY + "/memory/a-slug.md")),
                "a GLOBAL entry is NOT written into the project tier");
    }

    @Test
    @DisplayName("AC-12.3 / ADR-0007: a PROJECT entry lands under <store>/projects/<repo-key>/memory/")
    void projectEntryLandsInProjectTier(@TempDir Path store) {
        // Oracle: AC-12.3 / ADR-0007 — PROJECT memory lives under
        // <store>/projects/<repo-key>/memory/ (repository-specific), the same projects/<repo-key>
        // layout SessionStore uses.
        MemoryStore memory = new MemoryStore(store);

        memory.write(entry("b-slug", MemoryTier.PROJECT, "project why", "project body"), REPO_KEY);

        assertTrue(Files.isRegularFile(store.resolve("projects/" + REPO_KEY + "/memory/b-slug.md")),
                "a PROJECT entry is written under <store>/projects/<repo-key>/memory/ (AC-12.3)");
        assertFalse(Files.exists(store.resolve("memory/b-slug.md")),
                "a PROJECT entry is NOT written into the global tier");
    }

    @Test
    @DisplayName("AC-14.1: a written entry is human-readable markdown with provenance front-matter")
    void writtenEntryIsHumanReadableMarkdown(@TempDir Path store) throws IOException {
        // Oracle: AC-14.1 / AC-12.2 — entries are markdown a human can inspect/edit, with
        // provenance (created, why, originating session) in the front-matter and the learning
        // as prose.
        new MemoryStore(store)
                .write(entry("c-slug", MemoryTier.GLOBAL, "because reasons", "the learning prose"), REPO_KEY);

        String text = Files.readString(store.resolve("memory/c-slug.md"), StandardCharsets.UTF_8);

        assertTrue(text.startsWith("---"), "the file opens with a YAML front-matter block (AC-14.1)");
        assertTrue(text.contains("originSession:"), "provenance: the originating session (AC-12.2)");
        assertTrue(text.contains("created:"), "provenance: when (AC-12.2)");
        assertTrue(text.contains("because reasons"), "provenance: why (AC-12.2)");
        assertTrue(text.contains("the learning prose"), "the learning prose is the body (AC-14.1)");
    }

    @Test
    @DisplayName("AC-14.3: writing an entry maintains an INDEX.md listing the entry")
    void writeMaintainsIndex(@TempDir Path store) throws IOException {
        // Oracle: AC-14.3 — the agent maintains a memory index listing entries (one line per
        // entry, '- [slug] description' per ADR-0007).
        MemoryStore memory = new MemoryStore(store);

        memory.write(entry("one-slug", MemoryTier.GLOBAL, "first reason", "b1"), REPO_KEY);
        memory.write(entry("two-slug", MemoryTier.GLOBAL, "second reason", "b2"), REPO_KEY);

        String index = Files.readString(store.resolve("memory/INDEX.md"), StandardCharsets.UTF_8);
        assertTrue(index.contains("- [one-slug] first reason"), "index lists the first entry: " + index);
        assertTrue(index.contains("- [two-slug] second reason"), "index lists the second entry: " + index);
    }

    @Test
    @DisplayName("AC-14.3: re-writing an entry's slug replaces its index line, not duplicates it")
    void rewriteReplacesIndexLine(@TempDir Path store) throws IOException {
        MemoryStore memory = new MemoryStore(store);

        memory.write(entry("dup-slug", MemoryTier.GLOBAL, "old reason", "b1"), REPO_KEY);
        memory.write(entry("dup-slug", MemoryTier.GLOBAL, "new reason", "b2"), REPO_KEY);

        List<String> indexLines = Files.readAllLines(store.resolve("memory/INDEX.md")).stream()
                .filter(l -> l.contains("dup-slug")).toList();
        assertEquals(1, indexLines.size(), "the slug appears once in the index after a re-write");
        assertTrue(indexLines.get(0).contains("new reason"), "the index line carries the new description");
    }

    @Test
    @DisplayName("AC-14.3 / ADR-0007: loadIndexes reads BOTH tiers' indexes")
    void loadIndexesReadsBothTiers(@TempDir Path store) {
        // Oracle: ADR-0007 — on load read BOTH tiers' indexes. The awareness surface spans
        // global + project memory.
        MemoryStore memory = new MemoryStore(store);
        memory.write(entry("g-slug", MemoryTier.GLOBAL, "g reason", "gb"), REPO_KEY);
        memory.write(entry("p-slug", MemoryTier.PROJECT, "p reason", "pb"), REPO_KEY);

        List<MemoryIndexLine> lines = memory.loadIndexes(REPO_KEY);

        assertTrue(lines.stream().anyMatch(l -> l.tier() == MemoryTier.GLOBAL && l.slug().equals("g-slug")),
                "the GLOBAL tier index line is loaded");
        assertTrue(lines.stream().anyMatch(l -> l.tier() == MemoryTier.PROJECT && l.slug().equals("p-slug")),
                "the PROJECT tier index line is loaded");
    }

    @Test
    @DisplayName("CT-INV-12 / INV-14 / AC-14.2: an externally edited entry is honoured on next read (no cache)")
    void externalEditHonouredOnNextLoad(@TempDir Path store) throws IOException {
        // Oracle: CT-INV-12 / INV-14 / AC-14.2 — memory is re-read from disk each load; an
        // out-of-band edit is honoured on the next load (no masking cache). Write, edit the
        // file behind the store's back, read again, assert the change is reflected.
        MemoryStore memory = new MemoryStore(store);
        memory.write(entry("edit-slug", MemoryTier.GLOBAL, "why", "original body"), REPO_KEY);

        Path file = store.resolve("memory/edit-slug.md");
        String edited = Files.readString(file, StandardCharsets.UTF_8)
                .replace("original body", "hand-edited body");
        Files.writeString(file, edited, StandardCharsets.UTF_8);

        Optional<MemoryEntry> reread = memory.readEntry("edit-slug", REPO_KEY);
        assertTrue(reread.isPresent(), "the entry is still present after an external edit");
        assertEquals("hand-edited body", reread.get().body().strip(),
                "the external edit is reflected on the next read — no masking cache (INV-14, AC-14.2)");
    }

    @Test
    @DisplayName("CT-INV-12 / INV-14 / AC-14.2: an externally deleted entry is gone on next read (no cache)")
    void externalDeleteHonouredOnNextLoad(@TempDir Path store) throws IOException {
        // Oracle: CT-INV-12 / INV-14 / AC-14.2 — a hand-deleted entry is honoured on the next
        // load. Write, delete the file behind the store's back, read again, assert it is gone.
        MemoryStore memory = new MemoryStore(store);
        memory.write(entry("del-slug", MemoryTier.GLOBAL, "why", "body"), REPO_KEY);
        assertTrue(memory.readEntry("del-slug", REPO_KEY).isPresent(), "present before the delete");

        Files.delete(store.resolve("memory/del-slug.md"));

        assertTrue(memory.readEntry("del-slug", REPO_KEY).isEmpty(),
                "a hand-deleted entry reports absent on the next read — no masking cache (INV-14, AC-14.2)");
    }

    @Test
    @DisplayName("INV-14: a fresh write is immediately visible to a subsequent read (no stale cache)")
    void freshWriteImmediatelyVisible(@TempDir Path store) {
        // Oracle: INV-14 — re-read from disk each load. A write through one store is visible
        // through a second store instance over the same root, proving nothing is cached.
        new MemoryStore(store).write(entry("fresh-slug", MemoryTier.GLOBAL, "why", "fresh body"), REPO_KEY);

        Optional<MemoryEntry> viaOtherInstance = new MemoryStore(store).readEntry("fresh-slug", REPO_KEY);

        assertTrue(viaOtherInstance.isPresent(), "a second store instance reads the freshly-written entry");
        assertEquals("fresh body", viaOtherInstance.get().body().strip());
    }

    @Test
    @DisplayName("readEntry prefers GLOBAL over PROJECT for the same slug")
    void readEntryPrefersGlobal(@TempDir Path store) {
        MemoryStore memory = new MemoryStore(store);
        memory.write(entry("shared-slug", MemoryTier.GLOBAL, "g", "global body"), REPO_KEY);
        memory.write(entry("shared-slug", MemoryTier.PROJECT, "p", "project body"), REPO_KEY);

        assertEquals("global body", memory.readEntry("shared-slug", REPO_KEY).orElseThrow().body().strip(),
                "the GLOBAL tier is searched first");
    }

    @Test
    @DisplayName("loadIndexes returns empty when no memory has been written yet")
    void loadIndexesEmptyWhenNoMemory(@TempDir Path store) {
        assertTrue(new MemoryStore(store).loadIndexes(REPO_KEY).isEmpty(),
                "an empty store has no index lines");
    }

    @Test
    @DisplayName("loadIndexes ignores hand-added non-entry lines (lenient parse, INV-14)")
    void loadIndexesIgnoresNonEntryLines(@TempDir Path store) throws IOException {
        // Oracle: INV-14 — a hand-edited index is still readable on the next load. A heading or
        // blank line a human added is skipped, not treated as an entry.
        MemoryStore memory = new MemoryStore(store);
        memory.write(entry("real-slug", MemoryTier.GLOBAL, "real reason", "body"), REPO_KEY);
        Path index = store.resolve("memory/INDEX.md");
        Files.writeString(index, "# My memory notes\n\n" + Files.readString(index), StandardCharsets.UTF_8);

        List<MemoryIndexLine> lines = memory.loadIndexes(REPO_KEY);

        assertEquals(1, lines.size(), "only the real entry line is parsed; the heading is skipped");
        assertEquals("real-slug", lines.get(0).slug());
    }

    @Test
    @DisplayName("write and readEntry reject a blank repoKey")
    void rejectsBlankRepoKey(@TempDir Path store) {
        MemoryStore memory = new MemoryStore(store);
        assertThrows(IllegalArgumentException.class,
                () -> memory.write(entry("x-slug", MemoryTier.PROJECT, "w", "b"), " "));
        assertThrows(IllegalArgumentException.class, () -> memory.readEntry("x-slug", " "));
    }

    @Test
    @DisplayName("AC-14.1/14.3: delete removes the entry file and its index line")
    void deleteRemovesEntryAndIndexLine(@TempDir Path store) throws IOException {
        // Oracle: AC-14.1/14.3 — memory is curatable; removing an entry deletes its markdown file
        // and drops its line from the tier index, so a re-read no longer surfaces it (INV-14).
        MemoryStore memory = new MemoryStore(store);
        memory.write(entry("rm-slug", MemoryTier.GLOBAL, "to remove", "body"), REPO_KEY);
        assertTrue(Files.isRegularFile(store.resolve("memory/rm-slug.md")), "present before delete");

        boolean removed = memory.delete("rm-slug", REPO_KEY);

        assertTrue(removed, "delete reports it removed an existing entry");
        assertFalse(Files.exists(store.resolve("memory/rm-slug.md")), "the entry file is deleted");
        assertTrue(memory.loadIndexes(REPO_KEY).stream().noneMatch(l -> l.slug().equals("rm-slug")),
                "AC-14.3: the entry's index line is dropped");
    }

    @Test
    @DisplayName("AC-14.1: delete keeps sibling index lines intact")
    void deleteKeepsSiblings(@TempDir Path store) {
        // Oracle: AC-14.3 — removing one entry must not disturb the index lines of the others.
        MemoryStore memory = new MemoryStore(store);
        memory.write(entry("keep-slug", MemoryTier.GLOBAL, "keep me", "b1"), REPO_KEY);
        memory.write(entry("drop-slug", MemoryTier.GLOBAL, "drop me", "b2"), REPO_KEY);

        memory.delete("drop-slug", REPO_KEY);

        List<MemoryIndexLine> lines = memory.loadIndexes(REPO_KEY);
        assertTrue(lines.stream().anyMatch(l -> l.slug().equals("keep-slug")),
                "the sibling entry's index line survives the delete");
        assertTrue(lines.stream().noneMatch(l -> l.slug().equals("drop-slug")),
                "the removed entry's index line is gone");
    }

    @Test
    @DisplayName("delete of a missing slug is a no-op returning false")
    void deleteMissingSlugIsNoOp(@TempDir Path store) {
        // Oracle: AC-14.1 — curating is forgiving; deleting a non-existent slug is a no-op (no
        // crash), reported as false so the subcommand can say "no such entry".
        assertFalse(new MemoryStore(store).delete("never-existed", REPO_KEY),
                "deleting a missing entry returns false");
    }

    @Test
    @DisplayName("AC-14.1: entryPath resolves an existing entry's on-disk markdown path")
    void entryPathResolvesExistingFile(@TempDir Path store) {
        // Oracle: AC-14.1 "also hand-editable on disk" — entryPath resolves the real on-disk file
        // for the memory edit subcommand to point at. It must return the actual <slug>.md file.
        MemoryStore memory = new MemoryStore(store);
        memory.write(entry("path-slug", MemoryTier.GLOBAL, "why", "body"), REPO_KEY);

        Optional<Path> path = memory.entryPath("path-slug", REPO_KEY);

        assertTrue(path.isPresent(), "an existing entry has a resolvable on-disk path");
        assertEquals(store.resolve("memory/path-slug.md"), path.orElseThrow(),
                "entryPath resolves the entry's actual <slug>.md file (GLOBAL tier)");
    }

    @Test
    @DisplayName("entryPath returns empty for a slug with no entry on disk")
    void entryPathEmptyWhenAbsent(@TempDir Path store) {
        // Oracle: INV-14 — re-checked against disk; a slug with no file (e.g. hand-deleted) has no
        // path.
        assertTrue(new MemoryStore(store).entryPath("absent-slug", REPO_KEY).isEmpty(),
                "an absent entry has no on-disk path");
    }

    @Test
    @DisplayName("delete and entryPath reject blank slug / repoKey")
    void deleteAndEntryPathRejectBlank(@TempDir Path store) {
        MemoryStore memory = new MemoryStore(store);
        assertThrows(IllegalArgumentException.class, () -> memory.delete(" ", REPO_KEY));
        assertThrows(IllegalArgumentException.class, () -> memory.delete("s", " "));
        assertThrows(IllegalArgumentException.class, () -> memory.entryPath(" ", REPO_KEY));
        assertThrows(IllegalArgumentException.class, () -> memory.entryPath("s", " "));
    }
}
