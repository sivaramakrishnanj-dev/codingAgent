package com.srk.codingagent.loop;

import com.srk.codingagent.config.ResolvedConfig;
import com.srk.codingagent.model.ModelCapabilityProfile;
import com.srk.codingagent.persistence.ModelUsagePayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The real {@link BudgetGuard} (component C6, the Context Manager's token-budget half;
 * ADR-0006): it fires the auto-compaction trigger (state machine A, T13; machine B, LT2)
 * when a model turn's <em>measured</em> input token usage reaches the compaction threshold.
 *
 * <p><b>The trigger (ADR-0006, AC-18.1, NFR-CONTEXT-COMPACT-THRESHOLD).</b> Compaction is due
 * when
 *
 * <pre>usage.inputTokens &gt;= NFR-CONTEXT-COMPACT-THRESHOLD (0.85) &times; contextWindowTokens</pre>
 *
 * The window comes from the model's {@link ModelCapabilityProfile} (component C5, ADR-0002 —
 * "Compaction reads {@code contextWindowTokens} from the profile, no model branching there");
 * the {@code 0.85} fraction comes from {@link ResolvedConfig#contextCompactThreshold()}
 * (default {@code 0.85}, NFR-CONTEXT-COMPACT-THRESHOLD). The arithmetic uses the
 * <em>measured</em> {@link ModelUsagePayload#inputTokens()} the Converse {@code usage} block
 * reports — not an estimate — so the comparison against {@code 0.85 &times; window} is exact
 * (ADR-0006: "Threshold math uses the measured {@code usage.inputTokens}, not an estimate").
 *
 * <p>This guard delivers only the LT2 <em>trigger-condition detection</em>: when usage crosses
 * the threshold it returns {@link Decision#COMPACT}, and the loop surfaces that today (the S6
 * &rarr; machine-B summary handler — the summary Converse call, the derived session, the
 * original-preserved invariant, the learning harvest — is T-2.2/T-2.5, not this task). Below
 * the threshold it returns {@link Decision#CONTINUE}.
 *
 * <p>Immutable and stateless beyond the precomputed absolute threshold; one instance is safely
 * shared across a session's turns.
 */
public final class TokenBudgetGuard implements BudgetGuard {

    private static final Logger LOGGER = LoggerFactory.getLogger(TokenBudgetGuard.class);

    private final long compactAtInputTokens;
    private final int contextWindowTokens;
    private final double thresholdFraction;

    /**
     * Creates a guard that fires when measured input usage reaches
     * {@code thresholdFraction &times; contextWindowTokens}.
     *
     * @param contextWindowTokens the active model's effective input-token window
     *                            (NFR-MODEL-CONTEXT-WINDOW), from the capability profile
     *                            (ADR-0002); {@code >= 1}.
     * @param thresholdFraction   the compaction-trigger fraction
     *                            (NFR-CONTEXT-COMPACT-THRESHOLD, default {@code 0.85}), from
     *                            {@link ResolvedConfig#contextCompactThreshold()}; in
     *                            {@code [0, 1]}.
     * @throws IllegalArgumentException if {@code contextWindowTokens < 1} or
     *                                  {@code thresholdFraction} is outside {@code [0, 1]}.
     */
    public TokenBudgetGuard(int contextWindowTokens, double thresholdFraction) {
        if (contextWindowTokens < 1) {
            throw new IllegalArgumentException(
                    "contextWindowTokens must be >= 1 (was " + contextWindowTokens + ")");
        }
        if (thresholdFraction < 0.0 || thresholdFraction > 1.0) {
            throw new IllegalArgumentException(
                    "thresholdFraction must be in [0, 1] (was " + thresholdFraction + ")");
        }
        this.contextWindowTokens = contextWindowTokens;
        this.thresholdFraction = thresholdFraction;
        // The absolute trigger is 0.85 x window per NFR-CONTEXT-COMPACT-THRESHOLD ("Absolute
        // token count = 0.85 x NFR-MODEL-CONTEXT-WINDOW"). Computed once and held; the
        // comparison below is then a single integer compare against the measured count. ceil
        // is deliberate: at exactly threshold-or-above we compact, and the smallest integer
        // input count that is >= the real-valued 0.85 x window is its ceiling, so the boolean
        // "inputTokens >= 0.85 x window" is realized exactly.
        this.compactAtInputTokens = (long) Math.ceil(thresholdFraction * contextWindowTokens);
    }

    /**
     * Builds the production guard for the active model: the window from the model's
     * capability profile (ADR-0002), the threshold fraction from config
     * (NFR-CONTEXT-COMPACT-THRESHOLD).
     *
     * @param config the resolved configuration supplying the active model id and the
     *               threshold fraction; must not be {@code null}.
     * @param profile the active model's capability profile supplying the context window
     *               (ADR-0002); must not be {@code null}.
     * @return a guard wired to this model's window and the configured threshold; never
     *         {@code null}.
     * @throws NullPointerException if either argument is {@code null}.
     */
    public static TokenBudgetGuard forConfig(ResolvedConfig config, ModelCapabilityProfile profile) {
        java.util.Objects.requireNonNull(config, "config");
        java.util.Objects.requireNonNull(profile, "profile");
        return new TokenBudgetGuard(
                profile.contextWindowTokens(), config.contextCompactThreshold());
    }

    /**
     * Returns {@link Decision#COMPACT} when the turn's measured input usage has reached the
     * {@code 0.85 &times; window} threshold (AC-18.1, ADR-0006), else {@link Decision#CONTINUE}.
     *
     * @param usage the most recent model turn's usage; never {@code null}.
     * @return the compaction decision for this turn.
     */
    @Override
    public Decision evaluate(ModelUsagePayload usage) {
        java.util.Objects.requireNonNull(usage, "usage");
        if (usage.inputTokens() >= compactAtInputTokens) {
            LOGGER.info("Compaction threshold reached: inputTokens={} >= {} (= {} x {} window)",
                    usage.inputTokens(), compactAtInputTokens, thresholdFraction,
                    contextWindowTokens);
            return Decision.COMPACT;
        }
        return Decision.CONTINUE;
    }
}
