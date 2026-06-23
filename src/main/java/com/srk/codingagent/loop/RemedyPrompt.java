package com.srk.codingagent.loop;

import com.srk.codingagent.tool.CommandResult;
import java.util.Objects;

/**
 * Builds the remedy prompt fed back to the model when a verification attempt fails (AC-20.3): the
 * failing command, its exit code, and its captured {@code stdout}/{@code stderr}, with an instruction
 * to fix the cause so the verify loop's next attempt re-runs the command. This is the one shared
 * prompt builder both workflow-driver remedies use &mdash; the brownfield understand&rarr;change
 * remedy (T-1.6) and the greenfield implement-loop per-task remedy (T-3.3) &mdash; so the
 * failure-feedback wording lives in one place rather than being duplicated per driver.
 *
 * <p><b>Why a shared seam.</b> The {@link VerifyLoop} bounds the run&rarr;check&rarr;remedy&rarr;retry
 * cycle and invokes the injected {@link RemedyAttempt} between failing attempts; the model-driven
 * remedy (the turn that reads the failure and edits code) belongs to the workflow driver, but the
 * <em>prompt</em> that carries the failure output back is identical regardless of which driver drives
 * the turn &mdash; the failure's command, exit code, and output are what the model must reason over
 * (AC-20.3/AC-20.5). Keeping it here (alongside {@link RemedyAttempt} and {@link VerifyLoop}) keeps a
 * single source of truth for the failure-feedback prompt and is exercised under the coverage gate.
 *
 * <p>Kept as a small tested artifact so the suite can assert the failure output is actually carried
 * into the remedy turn (rather than a fixed canned string that ignores the failure).
 */
public final class RemedyPrompt {

    private RemedyPrompt() {
        // Holder for the prompt builder; not instantiable.
    }

    /**
     * Builds the remedy prompt for a failing verification attempt: an instruction to fix the cause,
     * then the failing command, its exit code, and its captured {@code stdout}/{@code stderr} (each
     * included only when non-blank), so the model reasons over the real failure (AC-20.3/AC-20.5).
     *
     * @param failure the failing attempt's result (non-zero exit), whose
     *                {@link CommandResult#stdout()}/{@link CommandResult#stderr()} carry the output to
     *                reason over; must not be {@code null}.
     * @return the remedy prompt carrying the failure's command, exit code, and output; never
     *         {@code null}.
     * @throws NullPointerException if {@code failure} is {@code null}.
     */
    public static String forFailure(CommandResult failure) {
        Objects.requireNonNull(failure, "failure");
        StringBuilder prompt = new StringBuilder()
                .append("The verification command failed. Read the output below, fix the cause in "
                        + "the code, and the command will be run again.\n\n")
                .append("Command: ").append(failure.command()).append('\n')
                .append("Exit code: ").append(failure.exitCode()).append('\n');
        appendLabelled(prompt, "stdout", failure.stdout());
        appendLabelled(prompt, "stderr", failure.stderr());
        return prompt.toString();
    }

    private static void appendLabelled(StringBuilder prompt, String label, String output) {
        if (output != null && !output.isBlank()) {
            prompt.append('\n').append(label).append(":\n").append(output).append('\n');
        }
    }
}
