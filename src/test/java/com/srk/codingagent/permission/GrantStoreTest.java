package com.srk.codingagent.permission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for the {@link GrantStore} — the in-memory, session-lineage-scoped store of
 * remembered grants that backs {@code ASK_ONCE_THEN_REMEMBER} (ADR-0004 grant lifecycle).
 *
 * <p>Oracle: INV-10 (a grant is scoped to its session lineage; not read by a separate root
 * session, not inherited by a sub-agent), RD-5 (remembered-grant scope; not persisted
 * across separate sessions; not inherited by sub-agents), AC-10.3 (record on approve),
 * AC-10.6 (a sub-agent runs the configured mode independently and does not inherit grants),
 * and CT-INV-9 (a grant is not read by a separate root session, nor by a sub-agent). These
 * are satisfied at the gate-decision/store level here; the loop wiring is T-0.8.
 */
class GrantStoreTest {

    @Test
    @DisplayName("CT-INV-9 / RD-5: a fresh store for a root session starts empty")
    void freshStoreStartsEmpty() {
        GrantStore store = GrantStore.forSession("root-1");
        assertEquals(0, store.size(), "a new session lineage remembers no grants");
        assertEquals("root-1", store.sessionLineage());
    }

    @Test
    @DisplayName("AC-10.3: recording a grant lets a later matching key auto-approve")
    void recordedGrantMatchesByExactKey() {
        // Oracle: AC-10.3 — approving under ASK_ONCE_THEN_REMEMBER records the grant so
        // matching later operations auto-approve.
        GrantStore store = GrantStore.forSession("root-1");
        store.remember("run_command:mvn test");

        assertNotNull(store.findExact("run_command:mvn test"),
                "the remembered key matches the same key later");
        assertEquals("root-1", store.findExact("run_command:mvn test").sessionLineage(),
                "the grant is scoped to the recording lineage");
    }

    @Test
    @DisplayName("RD-1: an unmatched key returns no grant")
    void unmatchedKeyReturnsNull() {
        GrantStore store = GrantStore.forSession("root-1");
        store.remember("run_command:mvn test");
        assertNull(store.findExact("run_command:mvn deploy"),
                "a different key is not remembered (re-prompts)");
    }

    @Test
    @DisplayName("AC-10.3: recording the same key twice is idempotent")
    void rememberIsIdempotent() {
        GrantStore store = GrantStore.forSession("root-1");
        store.remember("run_command:ls");
        store.remember("run_command:ls");
        assertEquals(1, store.size(), "the same match key is held once");
    }

    @Test
    @DisplayName("ADR-0004: a write grant covers a later write under its subtree")
    void writeGrantCoversSubtree() {
        GrantStore store = GrantStore.forSession("root-1");
        store.remember(MatchKey.forWrite(Path.of("/ws/src/Foo.java"))); // write:/ws/src

        assertNotNull(store.findWriteCovering(Path.of("/ws/src/deep/Bar.java")),
                "a write under the granted subtree auto-approves");
        assertNull(store.findWriteCovering(Path.of("/ws/other/Baz.java")),
                "a write outside the granted subtree re-prompts");
    }

    @Test
    @DisplayName("CT-INV-9 / INV-10: a sub-agent store starts empty and shares no grants with the parent")
    void subAgentStoreStartsEmpty() {
        // Oracle: AC-10.6 / RD-5 — a sub-agent runs the configured mode fresh and does NOT
        // inherit the parent's remembered grants.
        GrantStore parent = GrantStore.forSession("root-1");
        parent.remember("run_command:mvn test");

        GrantStore child = parent.forSubAgent("child-1");

        assertEquals(0, child.size(), "the sub-agent store inherits no grants from the parent");
        assertNull(child.findExact("run_command:mvn test"),
                "the parent's grant is not visible to the sub-agent");
        assertEquals("child-1", child.sessionLineage(), "the child runs under its own lineage");
        assertEquals(1, parent.size(), "the parent retains its own grant");
    }

    @Test
    @DisplayName("INV-10: a grant carries its recording lineage so a separate root session does not share it")
    void grantCarriesItsLineage() {
        // Oracle: INV-10 — a grant is scoped to its lineage; a separate root session has a
        // separate (empty) store and cannot read this one's grants.
        GrantStore sessionA = GrantStore.forSession("root-A");
        sessionA.remember("run_command:mvn test");
        GrantStore sessionB = GrantStore.forSession("root-B");

        assertNull(sessionB.findExact("run_command:mvn test"),
                "a separate root session does not read another session's grant");
        assertEquals("root-A", sessionA.findExact("run_command:mvn test").sessionLineage());
    }

    @Test
    @DisplayName("Grant store rejects a blank lineage and a blank match key")
    void rejectsBlankInputs() {
        assertThrows(IllegalArgumentException.class, () -> GrantStore.forSession(" "),
                "a blank lineage is rejected");
        assertThrows(NullPointerException.class, () -> GrantStore.forSession(null),
                "a null lineage is rejected");
        GrantStore store = GrantStore.forSession("root-1");
        assertThrows(IllegalArgumentException.class, () -> store.remember(" "),
                "a blank match key is rejected");
    }

    @Test
    @DisplayName("Grant validates its own fields")
    void grantValidatesFields() {
        assertThrows(IllegalArgumentException.class, () -> new Grant("", "lineage"));
        assertThrows(IllegalArgumentException.class, () -> new Grant("key", ""));
        assertThrows(NullPointerException.class, () -> new Grant(null, "lineage"));
        Grant grant = new Grant("run_command:ls", "root-1");
        assertTrue(grant.matchKey().startsWith("run_command:"));
    }
}
