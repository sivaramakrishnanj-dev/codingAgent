package com.srk.codingagent.persistence;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Derives the repository key a session is stored and scoped under (component C15, ADR-0005; AC-7.3):
 * the <b>git remote URL when the target project has one, else the normalized absolute path</b> of the
 * target project's working directory (03-data-model &sect; 2.1 {@code repoKey}).
 *
 * <p><b>Why this exists (DCR-3, brought forward).</b> Until now every run shared the fixed
 * {@code Main.ONE_SHOT_LINEAGE = "one-shot"} M0 placeholder as its session lineage / repo key, so
 * distinct runs over different target projects collided under one key (the run-collision root cause
 * the greenfield mid-flow resume needs gone). This resolver brings the real AC-7.3 keying forward so
 * distinct target projects get distinct keys &mdash; the scope a resumable greenfield session
 * (AC-7.6) is re-derived within: a fresh greenfield run resolves the target project's repo key, then
 * reconstructs that project's phase-state from <em>its</em> {@code design/} artifacts.
 *
 * <p><b>An injectable seam (testability).</b> Discovering a git remote URL is an external lookup (a
 * {@code git} subprocess over the target working directory); it is injected as a
 * {@link GitRemoteSource} so the keying <em>logic</em> (remote-present &rarr; remote URL;
 * remote-absent &rarr; normalized abs path) is a coverage-counted unit, distinct from the
 * production-only subprocess wiring in the JaCoCo-excluded composition root. The path normalization
 * is {@link Path#toAbsolutePath() absolute} + {@link Path#normalize() normalized}, so two spellings
 * of the same directory ({@code foo/../foo}, a relative {@code .}) resolve to one stable key.
 *
 * <p>Stateless: one resolver can key any number of target directories.
 */
public final class RepoKeyResolver {

    private static final Logger LOGGER = LoggerFactory.getLogger(RepoKeyResolver.class);

    private final GitRemoteSource gitRemoteSource;

    /**
     * Creates a resolver over the git-remote lookup seam.
     *
     * @param gitRemoteSource discovers a target directory's git remote URL when it has one (the
     *                        production lookup is a {@code git} subprocess; a test double in tests);
     *                        must not be {@code null}.
     * @throws NullPointerException if {@code gitRemoteSource} is {@code null}.
     */
    public RepoKeyResolver(GitRemoteSource gitRemoteSource) {
        this.gitRemoteSource = Objects.requireNonNull(gitRemoteSource, "gitRemoteSource");
    }

    /**
     * Resolves the repo key for a target project's working directory (AC-7.3, ADR-0005): the git
     * remote URL when {@link GitRemoteSource} reports one for the directory, else the directory's
     * normalized absolute path.
     *
     * @param workspaceRoot the target project's working directory (AC-6.2); must not be {@code null}.
     * @return the repo key (a git remote URL, or a normalized absolute path string); never
     *         {@code null} or blank.
     * @throws NullPointerException if {@code workspaceRoot} is {@code null}.
     */
    public String resolve(Path workspaceRoot) {
        Objects.requireNonNull(workspaceRoot, "workspaceRoot");
        Optional<String> remote = gitRemoteSource.remoteUrl(workspaceRoot)
                .map(String::strip)
                .filter(url -> !url.isEmpty());
        if (remote.isPresent()) {
            LOGGER.info("Resolved repo key from git remote URL for {} (AC-7.3)", workspaceRoot);
            return remote.get();
        }
        String pathKey = workspaceRoot.toAbsolutePath().normalize().toString();
        LOGGER.info("Resolved repo key from normalized absolute path for {} "
                + "(no git remote; AC-7.3)", workspaceRoot);
        return pathKey;
    }

    /**
     * The git-remote-URL lookup seam (the external dependency AC-7.3 keys on when present). In
     * production it is a {@code git} subprocess (e.g. {@code git -C <dir> remote get-url origin}) run
     * in the target working directory; in tests it is a scripted double. Returning
     * {@link Optional#empty()} means the target project has no usable git remote, so the resolver
     * falls back to the normalized absolute path.
     */
    @FunctionalInterface
    public interface GitRemoteSource {

        /**
         * The git remote URL for the target {@code workspaceRoot}, if it has one.
         *
         * @param workspaceRoot the target project's working directory; never {@code null}.
         * @return the remote URL, or {@link Optional#empty()} when the directory is not a git repo or
         *         has no remote configured.
         */
        Optional<String> remoteUrl(Path workspaceRoot);
    }
}
