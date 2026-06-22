package com.srk.codingagent.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link BrownfieldPlaybook} — the brownfield system-prompt artifact that primes
 * the model to drive the explore&rarr;change&rarr;verify arc (component C3, ADR-0012 brownfield
 * side). The SUT is the playbook itself; there are no collaborators.
 *
 * <p><b>M0-lesson discipline.</b> The G1 explore&rarr;edit&rarr;verify&rarr;resume gate depends on
 * the playbook actually instructing each brownfield behaviour, not merely "a prompt exists". These
 * tests assert the prompt content carries the playbook (each AC's behaviour is named in the text),
 * so a refactor that silently drops the explore-before-edit or verify-after-change instruction
 * would fail here.
 *
 * <p><b>Oracles trace to the brownfield ACs, never to the playbook's exact wording:</b>
 * <ul>
 *   <li><b>AC-4.1 / AC-5.1:</b> explore/locate before editing — the prompt instructs using the
 *       read-only explore tools before editing.</li>
 *   <li><b>AC-4.3:</b> report a missing file rather than fabricating contents.</li>
 *   <li><b>AC-5.3:</b> verify a change via the configured build/test command.</li>
 *   <li><b>AC-5.4:</b> ask a clarifying question when a change is ambiguous.</li>
 * </ul>
 * The assertions match on the <em>behaviour keywords</em> the AC pins (explore, edit, verify,
 * fabricate, ambiguous/clarif), not a brittle full-string equality, so the wording can evolve
 * while the contract stays enforced.
 */
class BrownfieldPlaybookTest {

    private static String joinedPrompt() {
        return String.join("\n", BrownfieldPlaybook.systemPrompt()).toLowerCase(Locale.ROOT);
    }

    @Test
    @DisplayName("the playbook is a non-empty list of system-prompt blocks (the loop's `system` arg shape)")
    void systemPromptIsNonEmpty() {
        // Oracle: dep API — AgentLoop/ModelClient accept the system prompt as List<String> blocks.
        // The playbook must produce a usable, non-empty list (an empty/absent prompt would leave the
        // model un-primed and the brownfield behaviour purely accidental).
        List<String> prompt = BrownfieldPlaybook.systemPrompt();

        assertFalse(prompt.isEmpty(), "the brownfield playbook must carry at least one prompt block");
        for (String block : prompt) {
            assertFalse(block.isBlank(), "no prompt block may be blank");
        }
    }

    @Test
    @DisplayName("AC-4.1/AC-5.1: the playbook instructs exploring with read-only tools before editing")
    void instructsExploreBeforeEdit() {
        // Oracle: AC-4.1 "explore the repository via read/grep/glob before proposing changes" +
        // AC-5.1 "locate the relevant files via search before editing". The prompt must name the
        // explore tools and instruct exploring/locating before the edit.
        String prompt = joinedPrompt();

        assertTrue(prompt.contains("explore"),
                "AC-4.1: the playbook instructs exploring the repository");
        assertTrue(prompt.contains("read_file") && prompt.contains("grep")
                        && prompt.contains("glob") && prompt.contains("list"),
                "AC-4.1: the playbook names the read-only explore tools (read_file/grep/glob/list)");
        assertTrue(prompt.contains("before") && prompt.contains("edit"),
                "AC-4.1/AC-5.1: exploration/location is instructed BEFORE editing");
    }

    @Test
    @DisplayName("AC-5.1: the playbook instructs locating the right files by searching, not guessing")
    void instructsLocateBySearch() {
        // Oracle: AC-5.1 "locate the relevant files via search before editing" — the prompt must
        // steer the model to find files by searching rather than guessing a path.
        String prompt = joinedPrompt();

        assertTrue(prompt.contains("locate") || prompt.contains("find") || prompt.contains("search"),
                "AC-5.1: the playbook instructs locating/finding the relevant files");
        assertTrue(prompt.contains("edit_file") || prompt.contains("write_file"),
                "AC-5.1: the change tools are named so search precedes them");
    }

    @Test
    @DisplayName("AC-4.3: the playbook instructs reporting a missing file rather than fabricating contents")
    void instructsReportNotFabricate() {
        // Oracle: AC-4.3 "if a referenced file or directory does not exist, the agent shall report
        // it rather than fabricating contents" — the prompt must forbid fabrication and instruct
        // reporting a missing file / tool error plainly.
        String prompt = joinedPrompt();

        assertTrue(prompt.contains("fabricate") || prompt.contains("invent"),
                "AC-4.3: the playbook forbids fabricating/inventing file contents");
        assertTrue(prompt.contains("report") && prompt.contains("not exist"),
                "AC-4.3: the playbook instructs reporting a file that does not exist");
    }

    @Test
    @DisplayName("AC-5.4: the playbook instructs asking a clarifying question when a change is ambiguous")
    void instructsAskWhenAmbiguous() {
        // Oracle: AC-5.4 "if the requested change is ambiguous (multiple plausible targets), the
        // agent shall ask a clarifying question rather than guessing" — the prompt must instruct
        // asking when ambiguous rather than guessing.
        String prompt = joinedPrompt();

        assertTrue(prompt.contains("ambiguous"),
                "AC-5.4: the playbook addresses an ambiguous change");
        assertTrue(prompt.contains("ask") && (prompt.contains("clarif") || prompt.contains("question")),
                "AC-5.4: the playbook instructs asking a clarifying question");
        assertTrue(prompt.contains("guess"),
                "AC-5.4: the playbook contrasts asking against guessing");
    }

    @Test
    @DisplayName("AC-5.3: the playbook instructs verifying the change via the build/test command")
    void instructsVerifyAfterChange() {
        // Oracle: AC-5.3 "when a change is applied, the agent shall verify it via the configured
        // build/test commands" — the prompt must instruct verifying after changing (the driver
        // also invokes the verify loop, but the model is primed to expect/cooperate with it).
        String prompt = joinedPrompt();

        assertTrue(prompt.contains("verify"),
                "AC-5.3: the playbook instructs verifying the change");
        assertTrue(prompt.contains("run_command") || prompt.contains("build/test")
                        || prompt.contains("test command"),
                "AC-5.3: the playbook references the build/test verification path");
    }

    @Test
    @DisplayName("the playbook names the brownfield mode so the model knows it is editing existing code")
    void namesBrownfieldMode() {
        // Oracle: 02-architecture § 1.2 (C3) "Mode is fixed for a session" + US-4/US-5 (existing
        // codebase). The prompt must establish the session is brownfield (editing an existing repo),
        // which is what makes the explore-before-edit framing meaningful.
        String prompt = joinedPrompt();

        assertTrue(prompt.contains("existing") || prompt.contains("brownfield"),
                "the playbook establishes the session works on an existing repository");
    }

    @Test
    @DisplayName("the named explore/change/verify tools match the registered toolset (T-0.6 + T-1.3)")
    void namedToolsMatchRegisteredToolset() {
        // Oracle: dep API — the registered tools are read_file/grep/glob/list (Class R),
        // write_file/edit_file (Class X), run_command (Class X). The playbook must steer toward
        // tools that actually exist, so its tool-name constants line up with the registry.
        assertEquals("read_file, grep, glob, and list", BrownfieldPlaybook.EXPLORE_TOOLS,
                "the explore tools are the Class-R read/search tools the registry offers");
        assertEquals("edit_file and write_file", BrownfieldPlaybook.CHANGE_TOOLS,
                "the change tools are the Class-X edit/write tools the registry offers");
        assertEquals("run_command", BrownfieldPlaybook.VERIFY_TOOL,
                "the verify tool is the Class-X run_command tool the registry offers");
    }
}
