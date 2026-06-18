package com.srk.codingagent.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ConfigLocations}.
 *
 * <p>Oracle: ADR-0009's file layout — global config at
 * {@code ~/.codingagent/config.yaml}; project config at
 * {@code ~/.codingagent/projects/<repo-key>/project.yaml}. The store root is
 * injected so the layout can be asserted against a controlled root rather than the
 * real home directory.
 */
class ConfigLocationsTest {

    private static final Path STORE = Path.of("/tmp/store-root/.codingagent");

    @Test
    @DisplayName("globalConfig is <store>/config.yaml (ADR-0009 global file layout)")
    void globalConfig_isStoreSlashConfigYaml() {
        // Oracle: ADR-0009 "Global: ~/.codingagent/config.yaml".
        ConfigLocations locations = new ConfigLocations(STORE);

        assertEquals(STORE.resolve("config.yaml"), locations.globalConfig(),
                "global config must live at <store>/config.yaml");
    }

    @Test
    @DisplayName("projectConfig(repoKey) is <store>/projects/<repoKey>/project.yaml (ADR-0009)")
    void projectConfig_isStoreSlashProjectsSlashKeySlashProjectYaml() {
        // Oracle: ADR-0009 "Project: ~/.codingagent/projects/<repo-key>/project.yaml".
        ConfigLocations locations = new ConfigLocations(STORE);

        assertEquals(STORE.resolve("projects").resolve("acme-repo").resolve("project.yaml"),
                locations.projectConfig("acme-repo"),
                "project config must live at <store>/projects/<repo-key>/project.yaml");
    }

    @Test
    @DisplayName("projectConfigForUnkeyedRepo points at a path with no repo-key segment")
    void projectConfigForUnkeyedRepo_hasNoKeySegment() {
        // Oracle: T-0.2 scope note — repo-key derivation is a later task; the unkeyed
        // seam returns the project-layer path without a key segment.
        ConfigLocations locations = new ConfigLocations(STORE);

        assertEquals(STORE.resolve("projects").resolve("project.yaml"),
                locations.projectConfigForUnkeyedRepo(),
                "unkeyed project path omits the repo-key segment");
    }

    @Test
    @DisplayName("the unkeyed project file does not exist, so the project layer resolves as absent")
    void unkeyedProjectFile_doesNotExist() {
        // Oracle: T-0.2 scope note — "it will not exist, so that layer resolves as
        // absent". Assert the path is genuinely absent under a fresh store root.
        ConfigLocations locations = new ConfigLocations(STORE);

        assertFalse(Files.exists(locations.projectConfigForUnkeyedRepo()),
                "the unkeyed project file must not exist (project layer absent)");
    }

    @Test
    @DisplayName("forUserHome roots the store under user.home/.codingagent")
    void forUserHome_rootsUnderUserHome() {
        // Oracle: ADR-0009 store location ~/.codingagent.
        Path expectedHome = Path.of(System.getProperty("user.home"), ".codingagent");

        ConfigLocations locations = ConfigLocations.forUserHome();

        assertEquals(expectedHome.resolve("config.yaml"), locations.globalConfig(),
                "forUserHome must root the store at ~/.codingagent");
    }
}
