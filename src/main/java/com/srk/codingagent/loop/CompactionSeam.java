package com.srk.codingagent.loop;

import com.srk.codingagent.model.converse.ConverseMessage;
import com.srk.codingagent.persistence.StopReason;
import java.util.List;
import java.util.Objects;

/**
 * The compaction seam the {@link AgentLoop} invokes when the {@link BudgetGuard} signals
 * {@link BudgetGuard.Decision#COMPACT} (state machine A, T13 &rarr; machine B). It exists so the
 * loop has one explicit hook where compaction-with-derivation (ADR-0006, the {@code Compactor}
 * of component C6) plugs in, without the loop itself owning the summary call, the derive, the
 * lineage write, or the learning harvest.
 *
 * <p><b>Why a seam (and not an inline {@code if}).</b> Mirrors {@link BudgetGuard} and the
 * {@code ToolRegistryComposer}: the loop holds a single collaborator with a default no-op
 * ({@link #NONE}), so the no-compaction wiring is just {@code NONE} and the compaction wiring is
 * a gate-covered seam a unit test drives directly. The loop's single responsibility — drive the
 * Converse tool-use cycle and log before it acts — is unchanged; the compaction orchestration
 * lives behind this seam where it is independently testable.
 *
 * <p><b>The contract (state machine A T14/T15).</b> Given the conversation transcript driven so
 * far and the turn's stop reason, the seam runs one compaction-with-derivation and returns a
 * {@link CompactionResult} telling the loop how to proceed:
 * <ul>
 *   <li><b>{@link CompactionResult.Kind#CONTINUED} (machine B LT3, machine A T14).</b> The
 *       summary&rarr;derive succeeded; the result carries the <em>derived</em> session's replayed
 *       {@code messages[]} (the summary context block + the recent-tail verbatim turns, INV-7
 *       reasoning signatures intact) so the loop continues driving in the derived conversation
 *       (T14 &rarr; S1) rather than surfacing-and-stopping.</li>
 *   <li><b>{@link CompactionResult.Kind#SURFACED} (machine B LT4 &rarr; LT7 &rarr; machine A
 *       T15).</b> The compaction could not recover context (summary failed / derive could not
 *       persist), or no compaction is wired ({@link #NONE}); the result carries the
 *       {@link StopReason} the loop surfaces so the caller's exit-code mapper decides. A failed
 *       compaction surfaces {@link StopReason#MODEL_CONTEXT_WINDOW_EXCEEDED} so the one-shot
 *       boundary maps it to the context-exhausted exit code 5 (cli-exit-codes 5, CT-SM-7).</li>
 * </ul>
 *
 * <p>The seam never mutates the loop's transcript in place; it returns a fresh derived transcript
 * to continue with (INV-4 — derive, don't mutate).
 */
@FunctionalInterface
public interface CompactionSeam {

    /**
     * The no-compaction wiring: it never compacts, surfacing the turn's stop reason so the loop
     * stops exactly as it did before a compaction seam was wired (this is the analogue of
     * {@link BudgetGuard#NONE}, kept for the {@code BudgetGuard.NONE} default-wiring case and the
     * sub-agent child loops which do not compact at v1).
     */
    CompactionSeam NONE = (transcript, stopReason) -> CompactionResult.surfaced(stopReason);

    /**
     * Runs one compaction-with-derivation for the live conversation and returns how the loop
     * should proceed.
     *
     * @param transcript the conversation transcript driven so far (the live session's
     *                   accumulated {@code messages[]}); never {@code null}.
     * @param stopReason the stop reason of the turn that triggered compaction, surfaced as-is by
     *                   {@link #NONE}; never {@code null}.
     * @return {@link CompactionResult#continued(List)} to continue in the derived session, or
     *         {@link CompactionResult#surfaced(StopReason)} to stop; never {@code null}.
     */
    CompactionResult compact(List<ConverseMessage> transcript, StopReason stopReason);

    /**
     * The result of consulting the {@link CompactionSeam}: either the derived conversation to
     * continue in (T14) or a stop reason to surface (T15 / no-compaction).
     *
     * @param kind             whether the loop continues in a derived session or surfaces; never
     *                         {@code null}.
     * @param derivedTranscript the derived session's replayed {@code messages[]} for a
     *                         {@link Kind#CONTINUED} result, or {@code null} for
     *                         {@link Kind#SURFACED}.
     * @param surfacedStopReason the stop reason to surface for a {@link Kind#SURFACED} result, or
     *                         {@code null} for {@link Kind#CONTINUED}.
     */
    record CompactionResult(
            Kind kind, List<ConverseMessage> derivedTranscript, StopReason surfacedStopReason) {

        /** Which way the loop proceeds after a compaction attempt. */
        enum Kind {

            /** Machine B LT3 / machine A T14: continue driving in the derived session. */
            CONTINUED,

            /** Machine B LT4 / machine A T15, or no compaction wired: surface and stop. */
            SURFACED
        }

        /**
         * Validates the result's field invariants.
         *
         * @throws NullPointerException     if {@code kind} is {@code null}.
         * @throws IllegalArgumentException if the field set does not match the kind.
         */
        public CompactionResult {
            Objects.requireNonNull(kind, "kind");
            if (kind == Kind.CONTINUED && derivedTranscript == null) {
                throw new IllegalArgumentException(
                        "a CONTINUED result must carry the derived transcript");
            }
            if (kind == Kind.SURFACED && surfacedStopReason == null) {
                throw new IllegalArgumentException(
                        "a SURFACED result must carry the stop reason to surface");
            }
            derivedTranscript = derivedTranscript == null ? null : List.copyOf(derivedTranscript);
        }

        /**
         * Builds a result that continues the loop in the derived session (machine A T14).
         *
         * @param derivedTranscript the derived session's replayed {@code messages[]}; must not be
         *                          {@code null} (use the empty list only if the derive seeded no
         *                          turns, which the {@code Compactor} never does).
         * @return a {@link Kind#CONTINUED} result.
         * @throws NullPointerException if {@code derivedTranscript} is {@code null}.
         */
        public static CompactionResult continued(List<ConverseMessage> derivedTranscript) {
            return new CompactionResult(Kind.CONTINUED,
                    Objects.requireNonNull(derivedTranscript, "derivedTranscript"), null);
        }

        /**
         * Builds a result that surfaces a stop reason without continuing (machine A T15 / the
         * no-compaction case).
         *
         * @param stopReason the stop reason the loop surfaces; must not be {@code null}.
         * @return a {@link Kind#SURFACED} result.
         * @throws NullPointerException if {@code stopReason} is {@code null}.
         */
        public static CompactionResult surfaced(StopReason stopReason) {
            return new CompactionResult(Kind.SURFACED, null,
                    Objects.requireNonNull(stopReason, "stopReason"));
        }

        /**
         * Whether the loop should continue driving in a derived session.
         *
         * @return {@code true} for a {@link Kind#CONTINUED} result.
         */
        public boolean continued() {
            return kind == Kind.CONTINUED;
        }
    }
}
