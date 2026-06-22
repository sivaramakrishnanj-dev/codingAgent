package com.srk.codingagent.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Contract test CT-INV-11 / INV-13 ({@code 06-formal/contract-tests.md} § 3.1, M2): a
 * {@link MemoryEntry} is persisted only after an explicit or approved write — never
 * auto-extracted in v1 (AC-21.4, RD-9). The store is the SUT.
 *
 * <p>The invariant is existential ("there is no write path that persists an entry without an
 * explicit/approved call"), so the test pins it two ways: (1) the only public mutating method
 * on {@link MemoryStore} is {@code write} — the read methods are query-only; (2) behaviourally,
 * reading an empty store creates nothing, and an entry appears on disk only once {@code write}
 * is explicitly invoked. T-2.4 owns the explicit path; T-2.5's approval flow is a separate
 * caller of the <em>same</em> {@code write} after the developer approves — there is no
 * auto-extraction path in either case.
 */
class NoAutoExtractContractTest {

    private static final String REPO_KEY = "github.com_srk_codingagent";

    @Test
    @DisplayName("CT-INV-11 / INV-13: write is the only public mutating method on the store")
    void writeIsTheSolePersistencePath() {
        // Oracle: INV-13 — an entry persists ONLY via an explicit/approved write. The store's
        // public surface must expose exactly one entry-persisting method (`write`); everything
        // else is a query (readEntry / loadIndexes / tierDir).
        List<String> publicInstanceMethods = Arrays.stream(MemoryStore.class.getDeclaredMethods())
                .filter(m -> Modifier.isPublic(m.getModifiers()) && !Modifier.isStatic(m.getModifiers()))
                .map(Method::getName)
                .distinct()
                .sorted()
                .toList();

        // The mutating method is `write`; the rest are read/query operations.
        assertTrue(publicInstanceMethods.contains("write"), "the explicit write path exists");
        List<String> mutators = publicInstanceMethods.stream()
                .filter(name -> !List.of("write", "readEntry", "loadIndexes").contains(name))
                .toList();
        assertTrue(mutators.isEmpty(),
                "the only public mutating store method is `write` (INV-13); unexpected methods: " + mutators);
    }

    @Test
    @DisplayName("CT-INV-11 / INV-13: reading an empty store persists nothing (no auto-extract)")
    void readingPersistsNothing(@TempDir Path store) {
        // Oracle: INV-13 — no entry is auto-extracted. Querying an empty store must not create
        // any memory file or directory tree of entries.
        MemoryStore memory = new MemoryStore(store);

        assertTrue(memory.loadIndexes(REPO_KEY).isEmpty(), "an empty store has no index lines");
        assertTrue(memory.readEntry("anything", REPO_KEY).isEmpty(), "an empty store has no entries");

        assertFalse(Files.exists(store.resolve("memory")),
                "querying an empty store creates no global memory dir (no auto-extract, INV-13)");
    }

    @Test
    @DisplayName("CT-INV-11 / INV-13: an entry appears on disk only after an explicit write")
    void entryAppearsOnlyAfterExplicitWrite(@TempDir Path store) {
        // Oracle: INV-13 — persistence is gated on the explicit write call, nothing else.
        MemoryStore memory = new MemoryStore(store);
        assertTrue(memory.readEntry("learned-thing", REPO_KEY).isEmpty(), "absent before any write");

        memory.write(new MemoryEntry("learned-thing", MemoryTier.GLOBAL, "2026-06-22T10:00:00Z",
                "sess", "why", MemoryStatus.ACTIVE, "body"), REPO_KEY);

        assertEquals("body", memory.readEntry("learned-thing", REPO_KEY).orElseThrow().body().strip(),
                "present only after the explicit write (INV-13)");
    }
}
