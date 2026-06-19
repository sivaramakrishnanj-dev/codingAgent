package com.srk.codingagent.permission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for the RD-1 normalized match-key construction (ADR-0004, OQ-E part 1, the grant
 * matching algorithm).
 *
 * <p>Oracle: ADR-0004's grant-matching algorithm and the worked examples it gives —
 * approving {@code mvn test} remembers {@code run_command:mvn test} so {@code mvn test -X}
 * matches but {@code mvn deploy} does not; an unknown executable is keyed at executable
 * granularity ({@code ls -la} → {@code run_command:ls}); a file write is keyed by subtree
 * ({@code write:src/}). Expected keys are the literal strings the ADR prescribes.
 */
class MatchKeyTest {

    // --- command keys: known-subcommand set gets subcommand granularity (ADR-0004) ---

    @Test
    @DisplayName("ADR-0004: a known-subcommand exe keys to run_command:<exe> <subcmd>")
    void knownSubcommandExecutableKeysToExeAndSubcommand() {
        // Oracle: ADR worked example — approving "mvn test" remembers "run_command:mvn test".
        assertEquals("run_command:mvn test", MatchKey.forCommand("mvn test"));
        assertEquals("run_command:git status", MatchKey.forCommand("git status"));
    }

    @Test
    @DisplayName("ADR-0004: a later command with the same subcommand + extra flags has the same key")
    void sameSubcommandWithFlagsMatches() {
        // Oracle: ADR worked example — "mvn test -X" and "mvn test -pl core" match "mvn test".
        String approved = MatchKey.forCommand("mvn test");
        assertEquals(approved, MatchKey.forCommand("mvn test -X"));
        assertEquals(approved, MatchKey.forCommand("mvn test -pl core"));
    }

    @Test
    @DisplayName("ADR-0004: a different subcommand of a known exe has a DIFFERENT key (re-prompts)")
    void differentSubcommandHasDifferentKey() {
        // Oracle: ADR worked example — "mvn deploy" does NOT match "mvn test".
        assertEquals("run_command:mvn deploy", MatchKey.forCommand("mvn deploy"));
        assertFalse(MatchKey.forCommand("mvn deploy").equals(MatchKey.forCommand("mvn test")),
                "a different subcommand must produce a different key so it re-prompts");
    }

    @Test
    @DisplayName("ADR-0004: a known exe takes the FIRST NON-FLAG token as the subcommand")
    void knownExecutableSkipsLeadingFlags() {
        // Oracle: ADR-0004 algorithm step 3 — "take the first non-flag token as the
        // subcommand". A leading short flag is skipped: "git -p status" keys to "git status".
        assertEquals("run_command:git status", MatchKey.forCommand("git -p status"));
    }

    @Test
    @DisplayName("ADR-0004: 'first non-flag token' is literal — a flag's value can become the subcommand")
    void firstNonFlagTokenIsLiteral() {
        // Oracle: ADR-0004 step 3 pins the algorithm as the FIRST NON-FLAG TOKEN, with no
        // special handling of flags that take a separate argument (e.g. git -c <value>). Taken
        // literally, "git -c color.ui=false status" keys on "color.ui=false" (the first
        // non-flag token). This is conservative for grant matching: a narrower/odd key
        // re-prompts MORE, never less — and the denylist (which decides destructiveness) runs
        // independently of the match key, so no destructive command can be wrongly remembered.
        // Recorded as a stated assumption.
        assertEquals("run_command:git color.ui=false",
                MatchKey.forCommand("git -c color.ui=false status"));
    }

    @Test
    @DisplayName("ADR-0004: an unknown executable keys at executable granularity (run_command:<exe>)")
    void unknownExecutableKeysAtExecutableGranularity() {
        // Oracle: ADR worked example — approving "ls -la" remembers "run_command:ls", so any
        // later "ls ..." matches (conservative: executable granularity for non-high-blast tools).
        assertEquals("run_command:ls", MatchKey.forCommand("ls -la"));
        assertEquals(MatchKey.forCommand("ls -la"), MatchKey.forCommand("ls /tmp"),
                "any later ls matches the executable-granularity key");
    }

    @Test
    @DisplayName("ADR-0004: an absolute-path executable basenames before keying")
    void absolutePathExecutableBasenames() {
        // Oracle: ADR algorithm step 2 — "/usr/bin/git -> git".
        assertEquals("run_command:git status", MatchKey.forCommand("/usr/bin/git status"));
    }

    @Test
    @DisplayName("ADR-0004: a known exe with no subcommand keys to just the exe")
    void knownExecutableWithNoSubcommandKeysToExe() {
        assertEquals("run_command:git", MatchKey.forCommand("git"));
        assertEquals("run_command:git", MatchKey.forCommand("git --version"),
                "with only flags there is no subcommand token");
    }

    @Test
    @DisplayName("ADR-0004: a blank command has no executable to key on -> null")
    void blankCommandHasNoKey() {
        assertNull(MatchKey.forCommand(""), "a blank command tokenizes to nothing");
        assertNull(MatchKey.forCommand("   "), "whitespace-only command tokenizes to nothing");
    }

    // --- write keys: subtree (ADR-0004) ---

    @Test
    @DisplayName("ADR-0004: a file write keys to write:<parent-subtree>")
    void writeKeysToParentSubtree() {
        // Oracle: ADR — "key = tool + containing directory subtree", the file's parent dir.
        Path file = Path.of("/ws/src/Foo.java");
        assertEquals("write:/ws/src", MatchKey.forWrite(file));
    }

    @Test
    @DisplayName("ADR-0004: a write-grant covers a later write anywhere under the subtree")
    void writeGrantCoversDescendants() {
        // Oracle: ADR — "later writes anywhere under src/ match".
        String grantKey = MatchKey.forWrite(Path.of("/ws/src/Foo.java")); // write:/ws/src
        assertTrue(MatchKey.writeGrantCovers(grantKey, Path.of("/ws/src/Bar.java")),
                "a sibling write under src/ matches");
        assertTrue(MatchKey.writeGrantCovers(grantKey, Path.of("/ws/src/deep/Baz.java")),
                "a write in a descendant directory under src/ matches");
    }

    @Test
    @DisplayName("ADR-0004: a write-grant does NOT cover a write outside the subtree")
    void writeGrantDoesNotCoverOutside() {
        // Oracle: ADR — "a write to /etc/... does not".
        String grantKey = MatchKey.forWrite(Path.of("/ws/src/Foo.java")); // write:/ws/src
        assertFalse(MatchKey.writeGrantCovers(grantKey, Path.of("/etc/passwd")),
                "a write outside the granted subtree must re-prompt");
        assertFalse(MatchKey.writeGrantCovers(grantKey, Path.of("/ws/other/X.java")),
                "a sibling-of-subtree write is not covered");
    }

    @Test
    @DisplayName("ADR-0004: a non-write grant key never covers a write")
    void nonWriteGrantNeverCoversWrite() {
        assertFalse(MatchKey.writeGrantCovers("run_command:mvn test", Path.of("/ws/src/Foo.java")),
                "a command grant must not authorize a file write");
    }

    // --- coarse tool keys (ADR-0004) ---

    @Test
    @DisplayName("ADR-0004: other Class-X tools key coarsely to the tool name")
    void otherToolsKeyToToolName() {
        // Oracle: ADR — "For OTHER Class X (web_search, spawn_subagent, memory write): key = tool name".
        assertEquals("web_search", MatchKey.forTool("web_search"));
        assertEquals("spawn_subagent", MatchKey.forTool("spawn_subagent"));
    }
}
