package com.srk.codingagent.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link DebugLogging} — the {@code --debug} log-level toggle (04-apis § 1.3,
 * 05-operations § 3).
 *
 * <p>Oracles:
 * <ul>
 *   <li><b>04-apis § 1.3</b>: {@code --debug} raises the operational log level to {@code DEBUG}
 *       ("DEBUG-level internals to stderr").</li>
 *   <li><b>05-operations § 3</b>: log levels are tunable; {@code DEBUG} is the {@code --debug}-
 *       visible level, and {@code INFO} is the default baseline (the external-boundary / WARN /
 *       ERROR lines) when {@code --debug} is absent.</li>
 * </ul>
 *
 * <p>The SUT is {@link DebugLogging} itself (not mocked). The slf4j-simple default-level system
 * property the toggle drives is the genuine collaborator; each test snapshots and restores it so
 * the suite leaves no global state behind.
 */
class DebugLoggingTest {

    private String savedProperty;

    @BeforeEach
    void snapshotProperty() {
        savedProperty = System.getProperty(DebugLogging.DEFAULT_LOG_LEVEL_PROPERTY);
    }

    @AfterEach
    void restoreProperty() {
        if (savedProperty == null) {
            System.clearProperty(DebugLogging.DEFAULT_LOG_LEVEL_PROPERTY);
        } else {
            System.setProperty(DebugLogging.DEFAULT_LOG_LEVEL_PROPERTY, savedProperty);
        }
    }

    @Test
    @DisplayName("04-apis § 1.3 / 05-operations § 3: --debug present maps to the DEBUG level")
    void debugPresent_mapsToDebugLevel() {
        // Oracle: 04-apis § 1.3 "--debug → DEBUG-level internals" + 05-operations § 3 "DEBUG is the
        // --debug-visible level". A --debug invocation must select the DEBUG level.
        assertEquals(DebugLogging.DEBUG_LEVEL, DebugLogging.levelFor(true),
                "04-apis § 1.3: --debug raises the level to DEBUG");
        assertTrue(DebugLogging.isDebugRequested(new String[] {"--debug", "-p", "x"}),
                "--debug is detected among the arguments");
    }

    @Test
    @DisplayName("05-operations § 3: without --debug the level is the default INFO baseline")
    void debugAbsent_mapsToInfoDefault() {
        // Oracle: 05-operations § 3 — the baseline operational level is INFO (external-boundary /
        // WARN / ERROR); DEBUG is only --debug-visible. Without --debug the level stays at INFO.
        assertEquals(DebugLogging.DEFAULT_LEVEL, DebugLogging.levelFor(false),
                "05-operations § 3: the default level is INFO when --debug is absent");
        assertFalse(DebugLogging.isDebugRequested(new String[] {"-p", "fix it"}),
                "no --debug among the arguments");
        assertFalse(DebugLogging.isDebugRequested(null), "null args request no --debug");
    }

    @Test
    @DisplayName("05-operations § 3: the DEBUG and default levels are the slf4j tunable tokens")
    void levelTokens_areTunableLevelNames() {
        // Oracle: 05-operations § 3 "SLF4J, per-package tunable ... DEBUG ... INFO". The level
        // tokens the toggle sets must be the slf4j level vocabulary (debug / info), the values
        // the binding's tunable level property understands.
        assertEquals("debug", DebugLogging.DEBUG_LEVEL, "the --debug level token is the slf4j DEBUG name");
        assertEquals("info", DebugLogging.DEFAULT_LEVEL, "the default level token is the slf4j INFO name");
    }

    @Test
    @DisplayName("04-apis § 1.3: applyFrom(--debug) sets the slf4j default-level property to DEBUG")
    void applyFrom_debug_setsDebugProperty() {
        // Oracle: 04-apis § 1.3 / 05-operations § 3 — the toggle is applied by setting the
        // operational log level to DEBUG. applyFrom must set the slf4j-simple default-level system
        // property to the DEBUG token (so a logger created afterward emits DEBUG) and report it.
        String applied = DebugLogging.applyFrom(new String[] {"--debug"});

        assertEquals(DebugLogging.DEBUG_LEVEL, applied, "applyFrom reports the DEBUG level it applied");
        assertEquals(DebugLogging.DEBUG_LEVEL,
                System.getProperty(DebugLogging.DEFAULT_LOG_LEVEL_PROPERTY),
                "04-apis § 1.3: --debug sets the slf4j default-level property to DEBUG");
    }

    @Test
    @DisplayName("05-operations § 3: applyFrom without --debug sets the default-level property to INFO")
    void applyFrom_noDebug_setsInfoProperty() {
        // Oracle: 05-operations § 3 — the default level is INFO. applyFrom with no --debug sets the
        // slf4j default-level property to INFO (the baseline), distinct from the DEBUG case — so the
        // level is tunable by the flag's presence.
        String applied = DebugLogging.applyFrom(new String[] {"-p", "fix it"});

        assertEquals(DebugLogging.DEFAULT_LEVEL, applied, "applyFrom reports the INFO default it applied");
        assertEquals(DebugLogging.DEFAULT_LEVEL,
                System.getProperty(DebugLogging.DEFAULT_LOG_LEVEL_PROPERTY),
                "05-operations § 3: no --debug keeps the slf4j default-level property at INFO");
    }
}
