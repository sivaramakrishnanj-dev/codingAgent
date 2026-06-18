package com.srk.codingagent.config;

import java.nio.file.Path;

/**
 * Resolves the on-disk locations of the configuration files defined by ADR-0009.
 *
 * <p>The global config lives at {@code ~/.codingagent/config.yaml}; the project
 * config lives at {@code ~/.codingagent/projects/<repo-key>/project.yaml} under the
 * same user-global store, keyed by repository. Deriving {@code <repo-key>} from a
 * git remote belongs to the session-store / repo-key task and is out of scope for
 * T-0.2, so {@link #projectConfig(String)} takes the repo key as an argument and
 * {@link #projectConfigForUnkeyedRepo()} returns the path the project layer would
 * occupy before a key is known (it will not exist, so that layer resolves as
 * absent). This keeps the wiring honest without pulling repo-key derivation
 * forward.
 *
 * <p>The store root defaults to {@code ~/.codingagent} but is injectable so the
 * resolver and its callers stay unit-testable against a temporary directory.
 */
public final class ConfigLocations {

    private static final String STORE_DIR_NAME = ".codingagent";
    private static final String GLOBAL_CONFIG_FILE = "config.yaml";
    private static final String PROJECTS_DIR_NAME = "projects";
    private static final String PROJECT_CONFIG_FILE = "project.yaml";

    private final Path storeRoot;

    /**
     * Creates locations rooted at the given store directory.
     *
     * @param storeRoot the store root directory ({@code ~/.codingagent} in
     *                  production); must not be {@code null}.
     */
    public ConfigLocations(Path storeRoot) {
        this.storeRoot = storeRoot;
    }

    /**
     * Creates locations rooted at the default store directory
     * ({@code ~/.codingagent}), derived from the {@code user.home} system property.
     *
     * @return a {@code ConfigLocations} for the current user's home directory.
     */
    public static ConfigLocations forUserHome() {
        return new ConfigLocations(Path.of(System.getProperty("user.home"), STORE_DIR_NAME));
    }

    /**
     * Returns the global config file path ({@code <store>/config.yaml}).
     *
     * @return the global config path; never {@code null}.
     */
    public Path globalConfig() {
        return storeRoot.resolve(GLOBAL_CONFIG_FILE);
    }

    /**
     * Returns the project config file path for a known repository key
     * ({@code <store>/projects/<repoKey>/project.yaml}).
     *
     * @param repoKey the repository key; must not be {@code null} or blank.
     * @return the project config path; never {@code null}.
     */
    public Path projectConfig(String repoKey) {
        return storeRoot.resolve(PROJECTS_DIR_NAME).resolve(repoKey).resolve(PROJECT_CONFIG_FILE);
    }

    /**
     * Returns the path the project layer occupies before a repository key has been
     * derived. The directory has no key segment, so the file will not exist and the
     * project layer resolves as absent. Used by the T-0.2 {@code Main} wiring until
     * repo-key derivation lands in a later task.
     *
     * @return a project-config path with no repo key; never {@code null}.
     */
    public Path projectConfigForUnkeyedRepo() {
        return storeRoot.resolve(PROJECTS_DIR_NAME).resolve(PROJECT_CONFIG_FILE);
    }
}
