package com.srk.codingagent.workflow;

import java.util.List;

/**
 * The brownfield "understand &rarr; change" playbook (component C3, ADR-0012 brownfield side):
 * the system-prompt artifact that primes the model to drive the explore&rarr;change&rarr;verify
 * arc itself through the {@link com.srk.codingagent.loop.AgentLoop}'s tool-use cycle. This is
 * the driver's load-bearing lever &mdash; the brownfield workflow is largely emergent model
 * behaviour, and the playbook is what makes the model explore before editing (AC-4.1/AC-5.1),
 * report rather than fabricate a missing file (AC-4.3), ask when a change is ambiguous
 * (AC-5.4), and verify after changing (AC-5.3) rather than declaring success blind.
 *
 * <p><b>Why this is a real, tested artifact and not a string literal in the factory.</b> The
 * playbook text <em>is</em> the orchestration for the explore-before-edit / verify-after-change
 * behaviour the brownfield ACs require; the factory only carries it to the
 * {@link com.srk.codingagent.loop.AgentLoop}'s {@code system} argument. Keeping it here, behind
 * a tested accessor, lets the suite assert the playbook actually instructs each behaviour
 * (rather than merely "a prompt was set"), which is the contract the G1 explore&rarr;edit&rarr;
 * verify&rarr;resume gate depends on.
 *
 * <p><b>Not a state machine.</b> ADR-0012 pins the brownfield driver as "orchestration on top
 * of the loop", not a separate engine. The playbook steers the model with instructions; it
 * does not hard-code a rigid phase sequence that fights the model's agency. The model decides
 * which tools to call and when within a single {@link com.srk.codingagent.loop.AgentLoop#run}
 * turn; the driver only wires the verify step in after the change completes (the one explicit
 * piece of orchestration, AC-5.3).
 *
 * <p>The tool names referenced in the playbook are the registered tools the model is offered
 * (T-0.6 + T-1.3): the Class-R explore tools {@code read_file}/{@code grep}/{@code glob}/
 * {@code list}, the Class-X change tools {@code write_file}/{@code edit_file}, and the Class-X
 * {@code run_command}. Naming them keeps the prompt and the registered toolset in step so the
 * model is steered toward tools that actually exist.
 */
public final class BrownfieldPlaybook {

    /** The explore tools the playbook steers the model to use before any edit (AC-4.1/AC-5.1). */
    static final String EXPLORE_TOOLS = "read_file, grep, glob, and list";

    /** The change tools the playbook gates behind exploration (AC-5.2 gating is the gate's job). */
    static final String CHANGE_TOOLS = "edit_file and write_file";

    /** The verify tool the model may use, complementary to the driver-invoked verify loop (AC-5.3). */
    static final String VERIFY_TOOL = "run_command";

    private BrownfieldPlaybook() {
        // Holder for the immutable playbook prompt; not instantiable.
    }

    /**
     * The brownfield system-prompt blocks to hand to the
     * {@link com.srk.codingagent.loop.AgentLoop}'s {@code system} argument. Returned as the
     * {@code List<String>} shape the loop and {@link com.srk.codingagent.model.converse.ModelClient}
     * accept, so it slots directly into the existing wire path.
     *
     * <p>The blocks instruct, in order, the four brownfield behaviours the ACs require:
     * explore-before-edit (AC-4.1/AC-5.1), report-do-not-fabricate on a tool error (AC-4.3),
     * ask-when-ambiguous (AC-5.4), and verify-after-change (AC-5.3). The driver invokes the
     * verify loop after the turn completes regardless, but the prompt makes the model expect
     * and cooperate with that step.
     *
     * @return the immutable list of system-prompt blocks for a brownfield session; never
     *         {@code null} or empty.
     */
    public static List<String> systemPrompt() {
        return List.of(
                "You are a coding agent working in an existing code repository (brownfield mode). "
                        + "Your job is to understand the codebase, then make the specific change "
                        + "the developer asks for, then verify it.",
                "Explore before you edit. Before proposing or making any change, use the "
                        + "read-only tools (" + EXPLORE_TOOLS + ") to read the relevant files and "
                        + "locate the code the request concerns, so your change fits the structure "
                        + "and conventions already there. Do not edit a file you have not first "
                        + "read in context.",
                "Locate the right files by searching. When the developer asks for a specific "
                        + "change, find the relevant files with " + EXPLORE_TOOLS + " before "
                        + "editing them with " + CHANGE_TOOLS + ", rather than guessing a path.",
                "Never fabricate. If a referenced file or directory does not exist, or a tool "
                        + "reports an error, report that plainly and adjust your approach; do not "
                        + "invent file contents or pretend a missing file is present.",
                "Ask when ambiguous. If the requested change has more than one plausible target "
                        + "and you cannot disambiguate it from the code, ask the developer a "
                        + "clarifying question instead of guessing which file or symbol to change.",
                "Verify after you change. Once you have applied the change, the configured "
                        + "build/test command will be run to verify it; you may also run it "
                        + "yourself with " + VERIFY_TOOL + ". If verification fails, read the "
                        + "failure output, fix the cause, and try again.");
    }
}
