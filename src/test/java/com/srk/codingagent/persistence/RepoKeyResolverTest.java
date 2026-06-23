package com.srk.codingagent.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests {@link RepoKeyResolver}: the AC-7.3 repo-key derivation brought forward by DCR-3 (replacing
 * the fixed {@code Main.ONE_SHOT_LINEAGE} placeholder) — git remote URL when present, else the
 * normalized absolute path (03-data-model § 2.1; ADR-0005).
 *
 * <p><b>Oracles trace to the spec, never to the resolver's code:</b>
 * <ul>
 *   <li><b>AC-7.3:</b> "key stored sessions to the repository (git remote URL when present, else
 *       normalized absolute path)" — the two derivation branches.</li>
 *   <li><b>AC-7.3 / DCR-3 root-cause:</b> distinct target projects must get distinct keys (the
 *       run-collision the fixed placeholder caused must be gone).</li>
 *   <li><b>03-data-model § 2.1:</b> the path-branch key is the <em>normalized absolute</em> path
 *       (two spellings of one directory resolve to one key).</li>
 * </ul>
 *
 * <p>The SUT is the real {@link RepoKeyResolver}; the only collaborator is a scripted
 * {@link RepoKeyResolver.GitRemoteSource} standing in for the {@code git} subprocess (the external
 * boundary), never a mock of the SUT.
 */
class RepoKeyResolverTest {

    private static final String REMOTE_URL = "ssh://git.example.com/my-project.git";

    /** A git-remote source reporting a fixed remote for every directory. */
    private static RepoKeyResolver.GitRemoteSource remotePresent(String url) {
        return workspaceRoot -> Optional.of(url);
    }

    /** A git-remote source reporting NO remote (not a git repo / no origin). */
    private static RepoKeyResolver.GitRemoteSource noRemote() {
        return workspaceRoot -> Optional.empty();
    }

    // --- AC-7.3 : git remote URL when present -----------------------------------------------------

    @Test
    @DisplayName("AC-7.3: when the target project has a git remote, the repo key is the git remote URL")
    void usesGitRemoteUrlWhenPresent(@TempDir Path workspaceRoot) {
        // Oracle: AC-7.3 — "key stored sessions to the repository (git remote URL when present, ...)".
        // With a git remote reported for the directory, the resolved key is that remote URL (not the
        // path). Expected key traces to AC-7.3's remote-present branch.
        RepoKeyResolver resolver = new RepoKeyResolver(remotePresent(REMOTE_URL));

        String key = resolver.resolve(workspaceRoot);

        assertEquals(REMOTE_URL, key,
                "AC-7.3: the repo key is the git remote URL when the project has one");
    }

    @Test
    @DisplayName("AC-7.3: a blank/whitespace remote URL falls back to the normalized absolute path (not a blank key)")
    void blankRemoteFallsBackToPath(@TempDir Path workspaceRoot) {
        // Oracle: AC-7.3 — the remote URL is used only "when present". A whitespace-only remote string
        // is not a usable repo identity, so the resolver falls back to the normalized absolute path
        // rather than keying every session under a blank key (which would re-introduce the collision).
        RepoKeyResolver resolver = new RepoKeyResolver(workspaceRoot1 -> Optional.of("   "));

        String key = resolver.resolve(workspaceRoot);

        assertEquals(workspaceRoot.toAbsolutePath().normalize().toString(), key,
                "AC-7.3: a blank remote URL falls back to the normalized absolute path");
    }

    // --- AC-7.3 : normalized absolute path when no remote -----------------------------------------

    @Test
    @DisplayName("AC-7.3: when the target project has no git remote, the repo key is the normalized absolute path")
    void usesNormalizedAbsolutePathWhenNoRemote(@TempDir Path workspaceRoot) {
        // Oracle: AC-7.3 — "... else normalized absolute path"; 03-data-model § 2.1. With no git
        // remote, the resolved key is the directory's normalized absolute path. Expected key is
        // DERIVED from the input path via the spec's normalize rule (toAbsolutePath + normalize), not
        // copied from the resolver.
        RepoKeyResolver resolver = new RepoKeyResolver(noRemote());

        String key = resolver.resolve(workspaceRoot);

        assertEquals(workspaceRoot.toAbsolutePath().normalize().toString(), key,
                "AC-7.3: with no git remote, the repo key is the normalized absolute path");
    }

    @Test
    @DisplayName("03-data-model § 2.1: two spellings of the same directory normalize to the SAME path key")
    void redundantPathSpellingsNormalizeToOneKey(@TempDir Path workspaceRoot) {
        // Oracle: 03-data-model § 2.1 — the path key is the NORMALIZED absolute path, so two spellings
        // of one directory (the plain path, and a path containing a redundant `child/..` round-trip)
        // must resolve to ONE key. Otherwise two runs over the same project would still collide-then-
        // diverge. Expected: both keys equal the normalized absolute path.
        RepoKeyResolver resolver = new RepoKeyResolver(noRemote());
        Path redundant = workspaceRoot.resolve("child").resolve("..");

        String plainKey = resolver.resolve(workspaceRoot);
        String redundantKey = resolver.resolve(redundant);

        assertEquals(plainKey, redundantKey,
                "03-data-model § 2.1: redundant spellings of the same directory normalize to one key");
        assertEquals(workspaceRoot.toAbsolutePath().normalize().toString(), redundantKey,
                "the path key is the NORMALIZED absolute path");
    }

    // --- AC-7.3 / DCR-3 root cause : distinct projects get distinct keys --------------------------

    @Test
    @DisplayName("AC-7.3 (DCR-3 root cause): distinct target projects (no remote) get DISTINCT keys — no run collision")
    void distinctProjectsGetDistinctKeys(@TempDir Path projectA, @TempDir Path projectB) {
        // Oracle: AC-7.3 + DCR-3 — the fixed "one-shot" placeholder made distinct runs over different
        // target projects collide under ONE key (the run-collision root cause). Real repo-keying must
        // give two DISTINCT projects two DISTINCT keys, so a resumable greenfield session is scoped to
        // its own project. Expected: keys differ. Traces to AC-7.3 + the DCR-3 collision fix, not impl.
        RepoKeyResolver resolver = new RepoKeyResolver(noRemote());

        String keyA = resolver.resolve(projectA);
        String keyB = resolver.resolve(projectB);

        assertNotEquals(keyA, keyB,
                "AC-7.3/DCR-3: distinct target projects get distinct keys — no run collision under a "
                        + "fixed placeholder");
    }

    @Test
    @DisplayName("AC-7.3: two projects sharing one git remote URL get the SAME key (keyed to the repository, not the checkout path)")
    void sameRemoteSameKey(@TempDir Path checkoutA, @TempDir Path checkoutB) {
        // Oracle: AC-7.3 — sessions are keyed to "the repository (git remote URL ...)". Two checkouts
        // of the SAME repository (same remote URL, different local paths) are the same repository, so
        // they share one key — the remote URL takes precedence over the path. Expected: both keys
        // equal the shared remote URL.
        RepoKeyResolver resolver = new RepoKeyResolver(remotePresent(REMOTE_URL));

        assertEquals(resolver.resolve(checkoutA), resolver.resolve(checkoutB),
                "AC-7.3: two checkouts of one repository (same remote) share one key");
        assertEquals(REMOTE_URL, resolver.resolve(checkoutA),
                "AC-7.3: the shared key is the git remote URL");
    }

    @Test
    @DisplayName("the resolved key is never blank (a usable session-store directory name)")
    void keyIsNeverBlank(@TempDir Path workspaceRoot) {
        // The repo key names a session-store directory (SessionStore requires a non-blank repoKey), so
        // both branches must yield a non-blank key.
        assertTrue(!new RepoKeyResolver(remotePresent(REMOTE_URL)).resolve(workspaceRoot).isBlank(),
                "the remote-branch key is non-blank");
        assertTrue(!new RepoKeyResolver(noRemote()).resolve(workspaceRoot).isBlank(),
                "the path-branch key is non-blank");
    }

    @Test
    @DisplayName("constructor and resolve reject null arguments")
    void rejectsNulls(@TempDir Path workspaceRoot) {
        assertThrows(NullPointerException.class, () -> new RepoKeyResolver(null));
        assertThrows(NullPointerException.class,
                () -> new RepoKeyResolver(noRemote()).resolve(null));
    }
}
