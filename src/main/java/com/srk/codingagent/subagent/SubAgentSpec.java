package com.srk.codingagent.subagent;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * The request to spawn one sub-agent (ADR-0010, AC-17.1): the scoped prompt the child runs,
 * plus the two budget knobs a parent may override per spawn — the model the child uses and
 * the wall-clock cap before the child is stopped (AC-17.6, NFR-SUBAGENT-BUDGET).
 *
 * <p>Both overrides are optional. When {@code modelId} is absent the child inherits the
 * parent's model (the v1 default — ADR-0010 "may run a different/cheaper model… v1 default:
 * inherit parent model unless overridden"); when {@code wallClockCap} is absent the child
 * runs under the orchestrator's configured default cap (600s, NFR-SUBAGENT-BUDGET). The
 * orchestrator resolves the effective model and cap from this spec.
 *
 * <p>The child's own context window is a property of its model's capability profile (C5,
 * ADR-0002), resolved independently by the orchestrator from the effective model id — it is
 * not carried here. "Own context window" is logical isolation (a separate {@code messages[]}),
 * not a separate token-budget pool (ADR-0010 Notes).
 *
 * @param prompt       the scoped prompt the child sub-agent is asked to perform (AC-17.1);
 *                     non-blank.
 * @param modelId      the model the child should run, or {@code null} to inherit the parent's
 *                     model (AC-17.2; v1 default is inherit); when present, non-blank.
 * @param wallClockCap the wall-clock budget before the child is stopped and a failure result
 *                     returned (AC-17.6), or {@code null} to use the orchestrator default;
 *                     when present, must be positive.
 */
public record SubAgentSpec(String prompt, String modelId, Duration wallClockCap) {

    /**
     * Validates the spec.
     *
     * @throws NullPointerException     if {@code prompt} is {@code null}.
     * @throws IllegalArgumentException if {@code prompt} is blank, {@code modelId} is present
     *                                  and blank, or {@code wallClockCap} is present and not
     *                                  positive.
     */
    public SubAgentSpec {
        if (Objects.requireNonNull(prompt, "prompt").isBlank()) {
            throw new IllegalArgumentException("prompt must be non-blank");
        }
        if (modelId != null && modelId.isBlank()) {
            throw new IllegalArgumentException("modelId, when present, must be non-blank");
        }
        if (wallClockCap != null && (wallClockCap.isZero() || wallClockCap.isNegative())) {
            throw new IllegalArgumentException(
                    "wallClockCap, when present, must be positive (was " + wallClockCap + ")");
        }
    }

    /**
     * Creates a spec that inherits the parent's model and the orchestrator's default
     * wall-clock cap, carrying only the scoped prompt.
     *
     * @param prompt the scoped prompt; non-blank.
     * @return a spec with no model or budget override.
     */
    public static SubAgentSpec of(String prompt) {
        return new SubAgentSpec(prompt, null, null);
    }

    /**
     * The model override, if any.
     *
     * @return the overriding model id, or {@link Optional#empty()} to inherit the parent's.
     */
    public Optional<String> modelIdIfPresent() {
        return Optional.ofNullable(modelId);
    }

    /**
     * The wall-clock cap override, if any.
     *
     * @return the overriding cap, or {@link Optional#empty()} to use the orchestrator default.
     */
    public Optional<Duration> wallClockCapIfPresent() {
        return Optional.ofNullable(wallClockCap);
    }
}
