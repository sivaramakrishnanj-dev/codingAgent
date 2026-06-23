package com.srk.codingagent.cli;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.srk.codingagent.tool.GreenfieldArtifactStore;
import com.srk.codingagent.workflow.GreenfieldArtifact;
import com.srk.codingagent.workflow.GreenfieldPhase;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for {@link InteractiveGreenfieldApproval} — the interactive, stdin-backed per-phase
 * greenfield approval decision the REPL path wires (T-3.1-RD-D6; AC-1.5, AC-2.3, AC-1.4, with the
 * AC-10.1 present-before-decide precedent).
 *
 * <p><b>SUT and collaborators.</b> The SUT is a real {@link InteractiveGreenfieldApproval} over a
 * real {@link GreenfieldArtifactStore} rooted at a {@link TempDir} (the target repo, the same store
 * the model authored the deliverable through). The only doubles are the terminal boundary: the
 * answer source (a {@link Supplier} of typed lines, the developer's stdin) and a capturing output
 * stream. Neither is the SUT; both are real, controllable stand-ins for the REPL terminal so the
 * present-then-decide round-trip is exercised without a live console.
 *
 * <p><b>Oracles trace to the spec, never to the decision's code:</b>
 * <ul>
 *   <li><b>AC-1.5 / AC-10.1:</b> the completed phase's deliverable (the artifact the phase authored)
 *       is presented on the output stream <em>before</em> the y/N is read.</li>
 *   <li><b>AC-1.5 / AC-2.3:</b> an affirmative answer ({@code y} / {@code yes}, case-insensitive,
 *       trimmed) confirms the phase (returns {@code true}).</li>
 *   <li><b>AC-1.4 (fail-closed):</b> a non-affirmative answer — blank, unrecognized, or end-of-input
 *       — declines (returns {@code false}), so the session pauses awaiting approval and no source is
 *       written.</li>
 * </ul>
 */
class InteractiveGreenfieldApprovalTest {

    private final ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
    private final PrintStream out = new PrintStream(outBytes, true, StandardCharsets.UTF_8);

    private String outText() {
        return outBytes.toString(StandardCharsets.UTF_8);
    }

    /** An answer source that replays a fixed sequence of typed lines, then end-of-input. */
    private static Supplier<String> answers(String... lines) {
        Deque<String> queue = new ArrayDeque<>(List.of(lines));
        return () -> queue.isEmpty() ? null : queue.removeFirst();
    }

    /** Writes the deliverable the model authors for a phase into the target-repo artifact store. */
    private static GreenfieldArtifactStore storeWith(
            Path targetRepo, GreenfieldArtifact artifact, String deliverable) {
        GreenfieldArtifactStore store = new GreenfieldArtifactStore(targetRepo);
        store.write(artifact.relativePath(), deliverable);
        return store;
    }

    @Test
    @DisplayName("AC-1.5/AC-10.1: the completed phase's deliverable is presented before the decision is read")
    void presentsDeliverableBeforeReadingDecision(@TempDir Path targetRepo) {
        // Oracle: AC-1.5 is the confirm-then-record contract; AC-10.1 establishes present-before-decide
        // ("present the exact operation ... before executing"). The answer source asserts, at the
        // moment it is consulted, that the requirements deliverable the phase authored is ALREADY on
        // the output stream — so the developer sees what they confirm before the y/N is taken.
        String deliverable = "# Requirements\n\nUS-1: as a developer I want a URL shortener.\n";
        GreenfieldArtifactStore store =
                storeWith(targetRepo, GreenfieldArtifact.REQUIREMENTS, deliverable);
        Supplier<String> answerWhenPromptShown = () -> {
            assertTrue(outText().contains("URL shortener"),
                    "AC-1.5/AC-10.1: the completed phase's deliverable must be presented BEFORE the "
                            + "decision is read; output was: " + outText());
            return "y";
        };
        InteractiveGreenfieldApproval decision =
                new InteractiveGreenfieldApproval(answerWhenPromptShown, out, store);

        boolean approved = decision.approve(GreenfieldPhase.REQUIREMENTS);

        assertTrue(approved, "AC-1.5/AC-2.3: an affirmative answer confirms the phase");
        assertTrue(outText().contains("US-1"),
                "AC-1.5: the authored requirements deliverable is presented; was: " + outText());
    }

    @Test
    @DisplayName("AC-1.5/AC-2.3: an affirmative 'y' confirms the phase advance")
    void affirmativeYConfirmsAdvance(@TempDir Path targetRepo) {
        // Oracle: AC-2.3 — "the agent shall request developer approval before implementation begins";
        // AC-1.5 — confirming records the approval. An explicit yes confirms the phase, so the gate
        // records the timestamp and the driver advances.
        GreenfieldArtifactStore store =
                storeWith(targetRepo, GreenfieldArtifact.DESIGN, "# Design\n");
        InteractiveGreenfieldApproval decision =
                new InteractiveGreenfieldApproval(answers("y"), out, store);

        assertTrue(decision.approve(GreenfieldPhase.DESIGN),
                "AC-1.5/AC-2.3: an affirmative answer confirms advancing out of the phase");
    }

    @Test
    @DisplayName("an affirmative answer is case-insensitive and ignores surrounding whitespace")
    void affirmativeIsCaseInsensitiveAndTrimmed(@TempDir Path targetRepo) {
        // Oracle: AC-1.5/AC-2.3 (inverse) — a clearly-intended yes is honoured regardless of casing or
        // stray whitespace, the same affirmative semantics the InteractiveApprover honours (the shared
        // AffirmativeAnswer parser). A padded, upper-case YES confirms the phase.
        GreenfieldArtifactStore store =
                storeWith(targetRepo, GreenfieldArtifact.REQUIREMENTS, "# Requirements\n");
        InteractiveGreenfieldApproval decision =
                new InteractiveGreenfieldApproval(answers("  YES  "), out, store);

        assertTrue(decision.approve(GreenfieldPhase.REQUIREMENTS),
                "a padded, upper-case YES still confirms the phase");
    }

    @Test
    @DisplayName("AC-1.4: a 'no' answer declines — the session pauses awaiting approval (no source)")
    void negativeAnswerDeclines(@TempDir Path targetRepo) {
        // Oracle: AC-1.4 — no Class X operation against source files while unapproved. A non-affirmative
        // answer declines, so the driver stops at the gate and no source is written.
        GreenfieldArtifactStore store =
                storeWith(targetRepo, GreenfieldArtifact.REQUIREMENTS, "# Requirements\n");
        InteractiveGreenfieldApproval decision =
                new InteractiveGreenfieldApproval(answers("n"), out, store);

        assertFalse(decision.approve(GreenfieldPhase.REQUIREMENTS),
                "AC-1.4/AC-2.3: a 'no' declines the advance; no source is written");
    }

    @Test
    @DisplayName("AC-1.4: end-of-input (Ctrl-D at the prompt) is a fail-closed decline")
    void endOfInputDeclines(@TempDir Path targetRepo) {
        // Oracle: AC-1.4 — a closed input must NEVER be a silent advance; the safe default is to
        // decline (the session pauses awaiting approval, no source). The answer source returns null
        // (end-of-input), mirroring NonInteractiveApprover / InteractiveApprover's fail-closed stance.
        GreenfieldArtifactStore store =
                storeWith(targetRepo, GreenfieldArtifact.TASKS, "# Tasks\n");
        InteractiveGreenfieldApproval decision =
                new InteractiveGreenfieldApproval(() -> null, out, store);

        assertFalse(decision.approve(GreenfieldPhase.TASKS),
                "AC-1.4: end-of-input declines (fail-closed), never silently advances");
    }

    @Test
    @DisplayName("AC-1.4: a blank answer declines (fail-closed)")
    void blankAnswerDeclines(@TempDir Path targetRepo) {
        // Oracle: AC-1.4 — only an explicit affirmative confirms; a blank line is not a confirmation,
        // so it declines and the session pauses awaiting approval.
        GreenfieldArtifactStore store =
                storeWith(targetRepo, GreenfieldArtifact.DESIGN, "# Design\n");
        InteractiveGreenfieldApproval decision =
                new InteractiveGreenfieldApproval(answers("   "), out, store);

        assertFalse(decision.approve(GreenfieldPhase.DESIGN),
                "AC-1.4: a blank answer is not a confirmation");
    }

    @Test
    @DisplayName("AC-1.4: an unrecognized answer declines (only y/yes confirm)")
    void unrecognizedAnswerDeclines(@TempDir Path targetRepo) {
        // Oracle: AC-1.4 — anything that is not an explicit affirmative declines, so a fat-finger never
        // accidentally advances into the source-writing implementation phase.
        GreenfieldArtifactStore store =
                storeWith(targetRepo, GreenfieldArtifact.TASKS, "# Tasks\n");
        InteractiveGreenfieldApproval decision =
                new InteractiveGreenfieldApproval(answers("maybe"), out, store);

        assertFalse(decision.approve(GreenfieldPhase.TASKS),
                "AC-1.4: an unrecognized answer declines");
    }

    @Test
    @DisplayName("AC-1.5: the prompt still names the phase even when no deliverable artifact is present")
    void presentsNoticeWhenNoDeliverableArtifact(@TempDir Path targetRepo) {
        // Oracle: AC-1.5/AC-10.1 present-before-decide — the developer must always see what they are
        // confirming. When the phase authored no artifact yet (none on disk), the decision still
        // presents a notice naming the phase before reading the y/N, and a non-affirmative still
        // declines fail-closed (AC-1.4). The store has no artifact written for this phase.
        GreenfieldArtifactStore store = new GreenfieldArtifactStore(targetRepo);
        InteractiveGreenfieldApproval decision =
                new InteractiveGreenfieldApproval(answers("n"), out, store);

        boolean approved = decision.approve(GreenfieldPhase.REQUIREMENTS);

        assertFalse(approved, "AC-1.4: with no deliverable and a 'no', the phase is not confirmed");
        assertTrue(outText().toLowerCase(java.util.Locale.ROOT).contains("requirements"),
                "AC-1.5/AC-10.1: the prompt names the phase being confirmed even with no artifact; was: "
                        + outText());
    }

    @Test
    @DisplayName("the decision requires its answer source, output stream, and artifact store")
    void constructorRejectsNull(@TempDir Path targetRepo) {
        GreenfieldArtifactStore store = new GreenfieldArtifactStore(targetRepo);
        assertThrows(NullPointerException.class,
                () -> new InteractiveGreenfieldApproval(null, out, store));
        assertThrows(NullPointerException.class,
                () -> new InteractiveGreenfieldApproval(answers("y"), null, store));
        assertThrows(NullPointerException.class,
                () -> new InteractiveGreenfieldApproval(answers("y"), out, null));
        InteractiveGreenfieldApproval decision =
                new InteractiveGreenfieldApproval(answers("y"), out, store);
        assertThrows(NullPointerException.class, () -> decision.approve(null));
    }
}
