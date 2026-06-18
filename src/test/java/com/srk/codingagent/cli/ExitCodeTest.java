package com.srk.codingagent.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ExitCode}.
 *
 * <p>Oracles trace to the CLI exit-code contract ({@code 06-formal/cli-exit-codes.md},
 * {@code 03-data-model.md} § 4): the numeric status of each named code is pinned by
 * that contract, NOT by {@link ExitCode}'s observed values. T-0.2 only exercises
 * {@link ExitCode#OK} and {@link ExitCode#USAGE_CONFIG} behaviorally, but the full
 * numeric mapping is asserted here because {@code ExitCode} is the shared value type
 * the contract pins and later tasks consume.
 */
class ExitCodeTest {

    @Test
    @DisplayName("OK maps to 0 (exit-code contract: 0 = success)")
    void ok_mapsToZero() {
        // Oracle: cli-exit-codes contract "0 success".
        assertEquals(0, ExitCode.OK.code(), "OK must be process status 0");
    }

    @Test
    @DisplayName("INTERNAL maps to 1 (exit-code contract: 1 = internal error)")
    void internal_mapsToOne() {
        // Oracle: cli-exit-codes contract "1 internal".
        assertEquals(1, ExitCode.INTERNAL.code(), "INTERNAL must be process status 1");
    }

    @Test
    @DisplayName("USAGE_CONFIG maps to 2 (exit-code contract: 2 = usage/config; AC-8.5, AC-6.4)")
    void usageConfig_mapsToTwo() {
        // Oracle: cli-exit-codes contract "2 usage-config"; AC-8.5 / AC-6.4 both
        // pin config faults to exit 2.
        assertEquals(2, ExitCode.USAGE_CONFIG.code(),
                "USAGE_CONFIG must be process status 2 (config fault, AC-8.5)");
    }

    @Test
    @DisplayName("USER_ABORTED maps to 3 (exit-code contract: 3 = user aborted)")
    void userAborted_mapsToThree() {
        // Oracle: cli-exit-codes contract "3 user-aborted".
        assertEquals(3, ExitCode.USER_ABORTED.code(), "USER_ABORTED must be process status 3");
    }

    @Test
    @DisplayName("MODEL_BACKEND maps to 4 (exit-code contract: 4 = model backend)")
    void modelBackend_mapsToFour() {
        // Oracle: cli-exit-codes contract "4 model-backend".
        assertEquals(4, ExitCode.MODEL_BACKEND.code(), "MODEL_BACKEND must be process status 4");
    }

    @Test
    @DisplayName("CONTEXT_EXHAUSTED maps to 5 (exit-code contract: 5 = context exhausted)")
    void contextExhausted_mapsToFive() {
        // Oracle: cli-exit-codes contract "5 context-exhausted".
        assertEquals(5, ExitCode.CONTEXT_EXHAUSTED.code(),
                "CONTEXT_EXHAUSTED must be process status 5");
    }

    @Test
    @DisplayName("INTERRUPTED maps to 130 (exit-code contract: 130 = SIGINT)")
    void interrupted_mapsTo130() {
        // Oracle: cli-exit-codes contract "130 interrupted (SIGINT)".
        assertEquals(130, ExitCode.INTERRUPTED.code(),
                "INTERRUPTED must be process status 130 (128 + SIGINT 2)");
    }
}
