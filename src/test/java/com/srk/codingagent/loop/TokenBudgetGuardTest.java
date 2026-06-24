package com.srk.codingagent.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.srk.codingagent.config.PermissionMode;
import com.srk.codingagent.config.ResolvedConfig;
import com.srk.codingagent.loop.BudgetGuard.Decision;
import com.srk.codingagent.model.ModelCapabilityProfile;
import com.srk.codingagent.persistence.ModelUsagePayload;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link TokenBudgetGuard} — the real {@link BudgetGuard} that realizes the
 * auto-compaction trigger (state machine A T13 / machine B LT2; the T-2.1 half of CT-SM-6).
 * The SUT is a real guard; nothing is mocked.
 *
 * <p><b>Oracles (each expectation is derived from the spec formula, never from the guard's
 * code):</b>
 * <ul>
 *   <li><b>NFR-CONTEXT-COMPACT-THRESHOLD / AC-18.1 / ADR-0006</b> — the absolute trigger is
 *       {@code 0.85 x NFR-MODEL-CONTEXT-WINDOW}; compaction fires when the <em>measured</em>
 *       {@code usage.inputTokens} reaches it. The boundary numbers below are computed from
 *       {@code 0.85 x window} (e.g. {@code 0.85 x 200000 = 170000}), not copied from the impl.</li>
 *   <li><b>ADR-0006</b> — the math uses the measured {@code usage.inputTokens}, NOT the output
 *       tokens or the cache fields, so a turn whose <em>output</em> is large but whose
 *       <em>input</em> is under threshold must not trigger.</li>
 *   <li><b>CT-SM-6 (T-2.1 half)</b> — fires exactly at/above {@code 0.85 x window}; does not
 *       fire below.</li>
 *   <li><b>ADR-0002</b> — {@link TokenBudgetGuard#forConfig} sources the window from the
 *       capability profile and the fraction from config.</li>
 * </ul>
 */
class TokenBudgetGuardTest {

    private static final double THRESHOLD = 0.85; // NFR-CONTEXT-COMPACT-THRESHOLD

    @Nested
    @DisplayName("CT-SM-6 / AC-18.1: COMPACT iff measured inputTokens >= 0.85 x window")
    class ThresholdDetection {

        // Oracle: NFR-CONTEXT-COMPACT-THRESHOLD "Absolute token count = 0.85 x window".
        // window 200000 -> threshold = 0.85 * 200000 = 170000 (an exact integer boundary).
        private static final int WINDOW = 200_000;
        private static final int THRESHOLD_TOKENS = 170_000; // = 0.85 * 200000, computed, not copied

        private final TokenBudgetGuard guard = new TokenBudgetGuard(WINDOW, THRESHOLD);

        @Test
        @DisplayName("usage exactly at 0.85 x window triggers COMPACT (AC-18.1 'reaches')")
        void exactlyAtThreshold_compacts() {
            // Oracle: AC-18.1 — "reaches NFR-CONTEXT-COMPACT-THRESHOLD" is inclusive; at exactly
            // 0.85 x window the threshold is reached.
            Decision decision = guard.evaluate(ModelUsagePayload.of(THRESHOLD_TOKENS, 1));

            assertEquals(Decision.COMPACT, decision,
                    "inputTokens == 0.85 x window must trigger compaction (AC-18.1 reaches)");
        }

        @Test
        @DisplayName("usage one token above the threshold triggers COMPACT (CT-SM-6 above)")
        void aboveThreshold_compacts() {
            // Oracle: ADR-0006 trigger usage.inputTokens >= 0.85 x window; above the boundary
            // is above the threshold.
            Decision decision = guard.evaluate(ModelUsagePayload.of(THRESHOLD_TOKENS + 1, 1));

            assertEquals(Decision.COMPACT, decision,
                    "inputTokens above 0.85 x window must trigger compaction (ADR-0006 >=)");
        }

        @Test
        @DisplayName("usage one token below the threshold does NOT trigger (CT-SM-6 below)")
        void belowThreshold_continues() {
            // Oracle: CT-SM-6 — the guard does not fire below 0.85 x window.
            Decision decision = guard.evaluate(ModelUsagePayload.of(THRESHOLD_TOKENS - 1, 1));

            assertEquals(Decision.CONTINUE, decision,
                    "inputTokens below 0.85 x window must NOT trigger compaction (CT-SM-6)");
        }

        @Test
        @DisplayName("usage far below the threshold continues (happy path within budget)")
        void wellWithinBudget_continues() {
            // Oracle: ADR-0006 — within budget, the loop continues.
            Decision decision = guard.evaluate(ModelUsagePayload.of(1_000, 50));

            assertEquals(Decision.CONTINUE, decision,
                    "usage well within budget stays in the loop");
        }

        @Test
        @DisplayName("a full-window turn (usage == window) triggers COMPACT (CT-SM-6 above)")
        void fullWindow_compacts() {
            // Oracle: window is above 0.85 x window, so a turn filling the window triggers.
            Decision decision = guard.evaluate(ModelUsagePayload.of(WINDOW, 1));

            assertEquals(Decision.COMPACT, decision,
                    "usage at the full window is above threshold -> COMPACT");
        }
    }

    @Nested
    @DisplayName("ADR-0006: the trigger uses MEASURED inputTokens, not output or cache fields")
    class MeasuredInputOnly {

        // Oracle: 0.85 x 100000 = 85000 (exact integer boundary).
        private static final int WINDOW = 100_000;
        private static final int THRESHOLD_TOKENS = 85_000; // = 0.85 * 100000, computed

        private final TokenBudgetGuard guard = new TokenBudgetGuard(WINDOW, THRESHOLD);

        @Test
        @DisplayName("a large OUTPUT token count with sub-threshold input does NOT trigger "
                + "(ADR-0006 uses input)")
        void largeOutputSmallInput_continues() {
            // Oracle: ADR-0006 — "Threshold math uses the measured usage.inputTokens". The
            // output tokens are irrelevant to the trigger, however large.
            ModelUsagePayload usage = ModelUsagePayload.of(THRESHOLD_TOKENS - 1, 9_999_999);

            assertEquals(Decision.CONTINUE, guard.evaluate(usage),
                    "output tokens must not affect the input-window trigger (ADR-0006)");
        }

        @Test
        @DisplayName("cache-read/write fields do not affect the trigger; only inputTokens does "
                + "(ADR-0006)")
        void cacheFieldsIgnored_inputDrivesDecision() {
            // Oracle: ADR-0006 — the trigger is on usage.inputTokens only. A payload carrying
            // cache fields but sub-threshold inputTokens must continue.
            ModelUsagePayload usage = new ModelUsagePayload(
                    THRESHOLD_TOKENS - 1, 100, 500_000, 500_000);

            assertEquals(Decision.CONTINUE, guard.evaluate(usage),
                    "cache-read/write fields must not affect the input-window trigger (ADR-0006)");
        }
    }

    @Nested
    @DisplayName("NFR-CONTEXT-COMPACT-THRESHOLD: the trigger scales with the window, not a "
            + "hardcoded count")
    class WindowDrivenThreshold {

        @Test
        @DisplayName("a different window moves the absolute trigger to 0.85 x that window "
                + "(NFR-CONTEXT-COMPACT-THRESHOLD)")
        void differentWindow_movesAbsoluteTrigger() {
            // Oracle: NFR-CONTEXT-COMPACT-THRESHOLD — the absolute trigger is 0.85 x window, so
            // halving the window halves the trigger. window 40000 -> threshold 34000.
            TokenBudgetGuard small = new TokenBudgetGuard(40_000, THRESHOLD);

            assertEquals(Decision.COMPACT, small.evaluate(ModelUsagePayload.of(34_000, 1)),
                    "0.85 x 40000 = 34000 must trigger");
            assertEquals(Decision.CONTINUE, small.evaluate(ModelUsagePayload.of(33_999, 1)),
                    "one below 0.85 x 40000 must not trigger");
        }

        @Test
        @DisplayName("a non-0.85 configured fraction moves the trigger accordingly "
                + "(threshold is the config fraction)")
        void differentFraction_movesTrigger() {
            // Oracle: NFR-CONTEXT-COMPACT-THRESHOLD default is 0.85 but the fraction is the
            // configured contextCompactThreshold; a 0.5 fraction over a 100000 window triggers
            // at 50000.
            TokenBudgetGuard half = new TokenBudgetGuard(100_000, 0.5);

            assertEquals(Decision.COMPACT, half.evaluate(ModelUsagePayload.of(50_000, 1)),
                    "0.5 x 100000 = 50000 must trigger at the configured fraction");
            assertEquals(Decision.CONTINUE, half.evaluate(ModelUsagePayload.of(49_999, 1)),
                    "one below 0.5 x 100000 must not trigger");
        }

        @Test
        @DisplayName("a non-integer 0.85 x window boundary fires at the smallest integer count "
                + ">= the real value (ADR-0006 >=)")
        void nonIntegerBoundary_firesAtCeil() {
            // Oracle: ADR-0006 trigger is the boolean inputTokens >= 0.85 x window over a
            // real-valued threshold. window 150001 -> 0.85 x 150001 = 127500.85. The smallest
            // integer input count satisfying ">= 127500.85" is 127501; 127500 is below.
            TokenBudgetGuard guard = new TokenBudgetGuard(150_001, THRESHOLD);

            assertEquals(Decision.CONTINUE, guard.evaluate(ModelUsagePayload.of(127_500, 1)),
                    "127500 < 127500.85 (= 0.85 x 150001) must NOT trigger (ADR-0006 >=)");
            assertEquals(Decision.COMPACT, guard.evaluate(ModelUsagePayload.of(127_501, 1)),
                    "127501 >= 127500.85 (= 0.85 x 150001) must trigger (ADR-0006 >=)");
        }
    }

    @Nested
    @DisplayName("ADR-0002: forConfig wires the window from the profile + fraction from config")
    class ForConfigWiring {

        @Test
        @DisplayName("forConfig fires when measured input reaches 0.85 x the profile window "
                + "(ADR-0002 + NFR-CONTEXT-COMPACT-THRESHOLD)")
        void forConfig_usesProfileWindowAndConfigFraction() {
            // Oracle: ADR-0002 — compaction reads contextWindowTokens from the profile, the
            // fraction from config. Profile window 200000, config 0.85 -> trigger 170000.
            ResolvedConfig config = configWithThreshold(THRESHOLD);
            ModelCapabilityProfile profile = new ModelCapabilityProfile(200_000);

            TokenBudgetGuard guard = TokenBudgetGuard.forConfig(config, profile);

            assertEquals(Decision.COMPACT, guard.evaluate(ModelUsagePayload.of(170_000, 1)),
                    "forConfig must trigger at 0.85 x the profile window (ADR-0002)");
            assertEquals(Decision.CONTINUE, guard.evaluate(ModelUsagePayload.of(169_999, 1)),
                    "forConfig must not trigger below 0.85 x the profile window");
        }

        @Test
        @DisplayName("forConfig honours a non-default configured threshold fraction (AC-8.1)")
        void forConfig_honoursConfiguredFraction() {
            // Oracle: AC-8.1 contextCompactThreshold is configurable; forConfig must use the
            // config value, not a hardcoded 0.85. 0.7 x 100000 = 70000.
            ResolvedConfig config = configWithThreshold(0.70);
            ModelCapabilityProfile profile = new ModelCapabilityProfile(100_000);

            TokenBudgetGuard guard = TokenBudgetGuard.forConfig(config, profile);

            assertEquals(Decision.COMPACT, guard.evaluate(ModelUsagePayload.of(70_000, 1)),
                    "forConfig must use the configured fraction (0.7 x 100000 = 70000)");
            assertEquals(Decision.CONTINUE, guard.evaluate(ModelUsagePayload.of(69_999, 1)),
                    "below the configured-fraction trigger must continue");
        }

        @Test
        @DisplayName("forConfig rejects null config or null profile")
        void forConfig_rejectsNulls() {
            ResolvedConfig config = configWithThreshold(THRESHOLD);
            ModelCapabilityProfile profile = new ModelCapabilityProfile(200_000);

            assertThrows(NullPointerException.class,
                    () -> TokenBudgetGuard.forConfig(null, profile));
            assertThrows(NullPointerException.class,
                    () -> TokenBudgetGuard.forConfig(config, null));
        }
    }

    @Nested
    @DisplayName("construction invariants")
    class Construction {

        @Test
        @DisplayName("a non-positive window is rejected (window is the threshold divisor)")
        void nonPositiveWindow_rejected() {
            // Oracle: NFR-MODEL-CONTEXT-WINDOW — the window is the basis of the threshold; a
            // zero/negative window has no meaningful threshold.
            assertThrows(IllegalArgumentException.class,
                    () -> new TokenBudgetGuard(0, THRESHOLD));
            assertThrows(IllegalArgumentException.class,
                    () -> new TokenBudgetGuard(-1, THRESHOLD));
        }

        @Test
        @DisplayName("a fraction outside [0,1] is rejected (matches the config schema range)")
        void outOfRangeFraction_rejected() {
            // Oracle: the resolved-config schema pins contextCompactThreshold to [0,1]; the
            // guard's fraction must honour the same range.
            assertThrows(IllegalArgumentException.class,
                    () -> new TokenBudgetGuard(200_000, -0.01));
            assertThrows(IllegalArgumentException.class,
                    () -> new TokenBudgetGuard(200_000, 1.01));
        }

        @Test
        @DisplayName("a null usage is rejected by evaluate")
        void nullUsage_rejected() {
            TokenBudgetGuard guard = new TokenBudgetGuard(200_000, THRESHOLD);

            assertThrows(NullPointerException.class, () -> guard.evaluate(null));
        }
    }

    private static ResolvedConfig configWithThreshold(double threshold) {
        return new ResolvedConfig(
                "us.anthropic.claude-opus-4-8",
                PermissionMode.ASK_EVERY_TIME,
                "us-east-1",
                null,
                1,
                null,
                ResolvedConfig.Commands.empty(),
                threshold,
                16384,
                5,
                300,
                10,
                300);
    }
}
