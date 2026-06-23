package com.srk.codingagent.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.srk.codingagent.config.PermissionMode;
import com.srk.codingagent.config.ResolvedConfig;
import com.srk.codingagent.memory.MemoryEntry;
import com.srk.codingagent.memory.MemoryStatus;
import com.srk.codingagent.memory.MemoryStore;
import com.srk.codingagent.memory.MemoryTier;
import com.srk.codingagent.model.converse.ModelClient;
import com.srk.codingagent.permission.Approver;
import com.srk.codingagent.permission.GrantStore;
import com.srk.codingagent.persistence.EventLog;
import com.srk.codingagent.persistence.PermissionDecisionOutcome;
import com.srk.codingagent.persistence.SessionStore;
import com.srk.codingagent.tool.ToolRegistry;
import com.srk.codingagent.workflow.GreenfieldPhase;
import com.srk.codingagent.workflow.GreenfieldPlaybook;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseRequest;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseResponse;

/**
 * The greenfield <b>no-source-write-before-approval (AC-1.4)</b> contract test, pinned at the
 * gate-covered composition seam (the analogue of {@link LiveToolRegistryCompositionTest} /
 * {@link LiveSystemPromptCompositionTest} for the greenfield path, T-3.1). It is the test that
 * would catch a live-only regression where a pre-approval greenfield phase silently offered the
 * model a source-write tool: the phase-scoped tool registry the production composition root
 * ({@link AgentLoopFactory} via {@link ToolRegistryComposer}) builds for a pre-approval phase must
 * <em>structurally withhold</em> the Class-X source tools — {@code write_file}, {@code edit_file},
 * {@code run_command} — so a source write is <em>impossible</em> (the tool is absent from the
 * {@code toolConfig} the model sees), not merely denied at the gate.
 *
 * <p><b>Why the gate-covered seam (the T-2.7/D5 lesson).</b> The phase-scoped registry assembly and
 * the per-phase greenfield system-prompt assembly are the load-bearing AC-1.4 / AC-2.3 enforcement.
 * They live in {@link ToolRegistryComposer} (NOT JaCoCo-excluded), not in the excluded
 * {@link AgentLoopFactory} or {@link Main}, precisely so a unit test pins them under the coverage
 * gate — the same gate-covered-seam discipline the M2 D5 fix established after the memory index was
 * lost in the excluded factory. This test drives the SAME composer the factory drives, over a
 * {@link MemoryStore}/{@link SessionStore} rooted at a {@link TempDir} and a never-called Bedrock
 * client (no model turn is exercised when assembling a registry/prompt).
 *
 * <p><b>SUT.</b> The real {@link ToolRegistryComposer} and the real {@link ToolRegistry}/
 * {@link MemoryStore} it composes. The only double is a {@link BedrockRuntimeClient} that expects no
 * call (assembling the registry / reading the index makes no Converse call).
 *
 * <p><b>Oracles trace to the spec, not to the composer's code:</b>
 * <ul>
 *   <li><b>AC-1.4 / RD-4:</b> a pre-approval phase's registry contains NO Class-X source tool
 *       (write_file/edit_file/run_command), so a source write is structurally impossible.</li>
 *   <li><b>RD-4 / AC-9.6:</b> the Class-R read/search tools (read_file/grep/glob/list) stay
 *       available in every phase (reads are non-gated in all modes).</li>
 *   <li><b>AC-2.3:</b> the implementation phase (reached only after approval) gets the full live
 *       toolset — the same tools a brownfield run is offered.</li>
 *   <li><b>02-architecture § 2 / AC-1.1:</b> the per-phase greenfield system prompt the composition
 *       root assembles reaches the model on a real Converse call (greenfield path reachable from the
 *       composition root).</li>
 * </ul>
 */
class LiveGreenfieldRegistryCompositionTest {

    private static final String LINEAGE = "one-shot";
    private static final String MODEL = "anthropic.claude-opus-4-8";
    private static final String CHILD_ID = "child-session-1";
    private static final String TS = "2026-06-23T09:00:00Z";

    /** The three Class-X source-write tools AC-1.4 forbids during the pre-approval dialogue. */
    private static final List<String> SOURCE_WRITE_TOOLS =
            List.of("write_file", "edit_file", "run_command");

    /** The four Class-R read/search tools that stay available in every phase (RD-4 / AC-9.6). */
    private static final List<String> EXPLORE_TOOLS = List.of("read_file", "grep", "glob", "list");

    /** A {@link BedrockRuntimeClient} that never expects a call (no model turn is exercised). */
    private static final class UnusedBedrockClient implements BedrockRuntimeClient {
        private final Deque<ConverseResponse> script = new ArrayDeque<>();

        @Override
        public ConverseResponse converse(ConverseRequest request) {
            if (script.isEmpty()) {
                throw new IllegalStateException("no model call is expected when assembling the seam");
            }
            return script.removeFirst();
        }

        @Override
        public String serviceName() {
            return "bedrock-runtime";
        }

        @Override
        public void close() {
            // no-op
        }
    }

    private static Approver alwaysApprove() {
        return req -> PermissionDecisionOutcome.APPROVE;
    }

    private static ResolvedConfig config() {
        return new ResolvedConfig(MODEL, PermissionMode.ASK_EVERY_TIME, "us-east-1", null,
                1, null, ResolvedConfig.Commands.empty(), 0.85, 16384, 5, 300);
    }

    /**
     * Builds the composer EXACTLY as {@link AgentLoopFactory#createGreenfieldPhaseLoopFactory} builds
     * it — same collaborator wiring, with {@code sessionLineage} threaded as both repoKey and
     * originSession — but over stores rooted at the temp dir and a never-called Bedrock client. This
     * is the production composition path; only the store roots (and the unused Bedrock) are
     * test-controlled.
     */
    private static ToolRegistryComposer composer(Path workspace, Path storeRoot) {
        Supplier<String> childIds = () -> CHILD_ID;
        return new ToolRegistryComposer(
                new ModelClient(new UnusedBedrockClient()), config(), workspace,
                EventLog.over(new StringWriter(), "parent"), new MemoryStore(storeRoot),
                new SessionStore(storeRoot), GrantStore.forSession(LINEAGE), alwaysApprove(),
                LINEAGE, LINEAGE, () -> TS, childIds);
    }

    // --- AC-1.4 : the pre-approval phases structurally withhold the source-write tools -----------

    @Test
    @DisplayName("AC-1.4: a pre-approval phase registry (requirements/design/tasks) contains NO source-write tool")
    void preApprovalPhasesWithholdSourceWriteTools(@TempDir Path workspace, @TempDir Path storeRoot) {
        // Oracle: AC-1.4 — "While in the requirements dialogue, the agent shall not execute any Class
        // X operation against source files"; ADR-0012 extends this to the design/tasks dialogue. The
        // structural enforcement is that the phase-scoped registry the live run offers the model
        // SIMPLY DOES NOT CONTAIN the source-write tools — the model cannot call a tool it is never
        // shown. Assert the registry for each pre-approval phase names none of write_file/edit_file/
        // run_command. Expected tool names trace to RD-4 (the Class-X taxonomy), not the composer.
        ToolRegistryComposer composer = composer(workspace, storeRoot);

        for (GreenfieldPhase phase : List.of(
                GreenfieldPhase.REQUIREMENTS, GreenfieldPhase.DESIGN, GreenfieldPhase.TASKS)) {
            List<String> names = composer.greenfieldRegistry(phase).toolNames();
            for (String forbidden : SOURCE_WRITE_TOOLS) {
                assertFalse(names.contains(forbidden),
                        "AC-1.4: the " + phase + " phase registry must NOT offer " + forbidden
                                + " (a source write is structurally impossible); was: " + names);
            }
        }
    }

    @Test
    @DisplayName("RD-4/AC-9.6: the Class-R explore tools stay available in every pre-approval phase (reads are non-gated)")
    void preApprovalPhasesKeepTheExploreTools(@TempDir Path workspace, @TempDir Path storeRoot) {
        // Oracle: RD-4 / AC-9.6 — Class R (read_file/grep/glob/list) is auto-approved in all modes
        // and non-gated; reads are always safe. So withholding the source-write tools must NOT also
        // strip the read/search tools — the agent still needs to explore and read during the
        // requirements/design dialogue. Each pre-approval phase registry must contain all four
        // explore tools.
        ToolRegistryComposer composer = composer(workspace, storeRoot);

        for (GreenfieldPhase phase : List.of(
                GreenfieldPhase.REQUIREMENTS, GreenfieldPhase.DESIGN, GreenfieldPhase.TASKS)) {
            List<String> names = composer.greenfieldRegistry(phase).toolNames();
            assertTrue(names.containsAll(EXPLORE_TOOLS),
                    "RD-4/AC-9.6: the " + phase + " phase keeps the Class-R explore tools; was: " + names);
        }
    }

    // --- AC-2.3 : the implementation phase (post-approval) gets the full live toolset ------------

    @Test
    @DisplayName("AC-2.3: the implementation phase registry offers the full live toolset (source-write tools included)")
    void implementationPhaseGetsTheFullToolset(@TempDir Path workspace, @TempDir Path storeRoot) {
        // Oracle: AC-2.3 — implementation begins only after the design + task breakdown are approved;
        // the implementation phase is where source writing is permitted. So the implementation
        // phase's registry must be the FULL live toolset — the same registry a brownfield run is
        // offered (parentRegistry) — including the source-write tools the pre-approval phases withheld.
        ToolRegistryComposer composer = composer(workspace, storeRoot);

        List<String> implementNames = composer.greenfieldRegistry(GreenfieldPhase.IMPLEMENT).toolNames();
        for (String tool : SOURCE_WRITE_TOOLS) {
            assertTrue(implementNames.contains(tool),
                    "AC-2.3: the implementation phase offers " + tool + " (source writing is now permitted)");
        }
        assertEquals(composer.parentRegistry().toolNames(), implementNames,
                "AC-2.3: the implementation phase gets the full live toolset, identical to a brownfield run");
    }

    @Test
    @DisplayName("AC-1.4/AC-2.3: source-write tools are withheld pre-approval and present only in implementation")
    void sourceWriteToolsAppearOnlyInImplementation(@TempDir Path workspace, @TempDir Path storeRoot) {
        // Oracle: AC-1.4 (no source write pre-approval) + AC-2.3 (source writing begins after
        // approval, in implementation). The boundary the approval guards is exactly the appearance of
        // the source-write tools. Assert that across the four phases, every source-write tool is
        // absent in all three pre-approval phases and present in the implementation phase — the
        // structural realization of "the gate that lets source writing begin".
        ToolRegistryComposer composer = composer(workspace, storeRoot);

        for (String tool : SOURCE_WRITE_TOOLS) {
            long phasesOffering = List.of(GreenfieldPhase.values()).stream()
                    .filter(phase -> composer.greenfieldRegistry(phase).toolNames().contains(tool))
                    .count();
            assertEquals(1, phasesOffering,
                    "AC-1.4/AC-2.3: " + tool + " is offered in EXACTLY one phase (implementation), "
                            + "withheld in the three pre-approval phases");
            assertTrue(composer.greenfieldRegistry(GreenfieldPhase.IMPLEMENT).toolNames().contains(tool),
                    "AC-2.3: the one phase offering " + tool + " is the implementation phase");
        }
    }

    @Test
    @DisplayName("greenfieldRegistry rejects a null phase")
    void greenfieldRegistryRejectsNullPhase(@TempDir Path workspace, @TempDir Path storeRoot) {
        ToolRegistryComposer composer = composer(workspace, storeRoot);
        org.junit.jupiter.api.Assertions.assertThrows(NullPointerException.class,
                () -> composer.greenfieldRegistry(null));
    }

    // --- 02-architecture § 2 / AC-1.1 : the greenfield prompt is reachable from the composition root

    @Test
    @DisplayName("AC-1.1: the composition root assembles the requirements-phase greenfield prompt (greenfield path reachable from the composer)")
    void compositionRootAssemblesRequirementsPhasePrompt(@TempDir Path workspace, @TempDir Path storeRoot) {
        // Oracle: 02-architecture § 2 (the loop is given the system prompt) + AC-1.1 — the per-phase
        // greenfield prompt the composition root assembles for the requirements phase must carry the
        // requirements-before-source priming. This is the gate-covered seam the factory delegates to
        // (greenfieldSystemPrompt), proving the greenfield prompt is assembled in a coverage-counted
        // seam, not lost in the JaCoCo-excluded factory (the D5 class of defect). The oracle is the
        // playbook's own blocks (which trace to ADR-0012/AC-1.1), not the composer's wording.
        List<String> blocks = composer(workspace, storeRoot)
                .greenfieldSystemPrompt(GreenfieldPhase.REQUIREMENTS);

        // No memory written, so the assembled prompt is exactly the per-phase playbook blocks.
        assertEquals(GreenfieldPlaybook.systemPrompt(GreenfieldPhase.REQUIREMENTS), blocks,
                "AC-1.1: the composition root assembles the per-phase greenfield playbook (empty index "
                        + "adds no memory section)");
        String joined = String.join("\n", blocks).toLowerCase(Locale.ROOT);
        assertTrue(joined.contains("requirements") && joined.contains("source"),
                "AC-1.1: the assembled requirements-phase prompt carries the requirements-before-source priming");
    }

    @Test
    @DisplayName("ADR-0007/AC-14.3: a written index entry reaches the assembled greenfield system prompt (memory loaded for greenfield too)")
    void writtenIndexEntryReachesGreenfieldPrompt(@TempDir Path workspace, @TempDir Path storeRoot) {
        // Oracle: ADR-0007 "Index + selective load" — on session start both tiers' indexes load into
        // the system prompt; AC-14.3 — the index lists entries (slug + description) for review. The
        // greenfield prompt assembly must inject the same always-loaded memory index the brownfield
        // assembly does (greenfield inherits memory for free, ADR-0012 Notes). A written entry's slug
        // and description must appear in the assembled greenfield prompt. Expected values trace to the
        // seeded entry and the ADR, not the composer.
        MemoryStore store = new MemoryStore(storeRoot);
        store.write(new MemoryEntry("repo-uses-maven", MemoryTier.PROJECT, TS, LINEAGE,
                "this repo builds with maven not gradle", MemoryStatus.ACTIVE, "mvn clean verify."),
                LINEAGE);

        // Build a composer over the SAME store the entry was written to.
        ToolRegistryComposer composer = new ToolRegistryComposer(
                new ModelClient(new UnusedBedrockClient()), config(), workspace,
                EventLog.over(new StringWriter(), "parent"), store, new SessionStore(storeRoot),
                GrantStore.forSession(LINEAGE), alwaysApprove(), LINEAGE, LINEAGE, () -> TS,
                () -> CHILD_ID);

        String prompt = String.join("\n", composer.greenfieldSystemPrompt(GreenfieldPhase.DESIGN));

        assertTrue(prompt.contains("repo-uses-maven"),
                "ADR-0007: the entry's slug reaches the greenfield system prompt");
        assertTrue(prompt.contains("this repo builds with maven not gradle"),
                "AC-14.3: the entry's one-line description reaches the greenfield system prompt");
    }

    @Test
    @DisplayName("greenfieldSystemPrompt rejects a null phase")
    void greenfieldSystemPromptRejectsNullPhase(@TempDir Path workspace, @TempDir Path storeRoot) {
        ToolRegistryComposer composer = composer(workspace, storeRoot);
        org.junit.jupiter.api.Assertions.assertThrows(NullPointerException.class,
                () -> composer.greenfieldSystemPrompt(null));
    }

    @Test
    @DisplayName("DCR-2/D1: the composer exposes the distinct greenfield-budget model client the greenfield phase loops use (AgentLoopFactory wires it as ModelClient.forGreenfield)")
    void composerExposesDistinctGreenfieldBudgetClient(@TempDir Path workspace,
            @TempDir Path storeRoot) {
        // Oracle: ADR-0012 § 2.1 — "The greenfield phases (C3) set an explicit budget" via
        // inferenceConfig.maxTokens; AgentLoopFactory assembles each greenfield phase loop over a
        // distinct ModelClient.forGreenfield (the budget-carrying wire path), separate from the
        // uncapped one the brownfield/one-shot + sub-agent loops use. Built via the 13-arg
        // constructor (the production path's shape), the composer must expose the greenfield-budget
        // client distinctly from the uncapped one. The greenfield client carrying the actual 16384
        // budget on the wire is pinned by GreenfieldWiringTest; here the wiring contract is that the
        // composer keeps the two clients distinct.
        ModelClient uncapped = new ModelClient(new UnusedBedrockClient());
        ModelClient greenfield = ModelClient.forGreenfield(new UnusedBedrockClient());
        ToolRegistryComposer composer = new ToolRegistryComposer(
                uncapped, greenfield, config(), workspace,
                EventLog.over(new StringWriter(), "parent"), new MemoryStore(storeRoot),
                new SessionStore(storeRoot), GrantStore.forSession(LINEAGE), alwaysApprove(),
                LINEAGE, LINEAGE, () -> TS, () -> CHILD_ID);

        assertSame(greenfield, composer.greenfieldModelClient(),
                "DCR-2: the greenfield phase loops are assembled over the greenfield-budget client");
        assertSame(uncapped, composer.modelClient(),
                "the brownfield/one-shot + sub-agent loops keep the uncapped client");
        assertNotSame(composer.modelClient(), composer.greenfieldModelClient(),
                "ADR-0012 § 2.1: the greenfield budget is greenfield-scoped — a distinct client from "
                        + "the uncapped one");
    }

    @Test
    @DisplayName("the convenience constructor (no separate greenfield client) falls back to the uncapped client for the greenfield path")
    void convenienceConstructorFallsBackToUncappedGreenfieldClient(@TempDir Path workspace,
            @TempDir Path storeRoot) {
        // The 12-arg convenience constructor (used by tests that do not exercise the output-budget
        // wiring) supplies no separate greenfield client, so the greenfield path falls back to the
        // same uncapped client — backend default cap, the prior behaviour. Documents the fallback the
        // overload's Javadoc names.
        ToolRegistryComposer composer = composer(workspace, storeRoot);

        assertSame(composer.modelClient(), composer.greenfieldModelClient(),
                "the convenience constructor uses one client for both paths (no separate budget client)");
    }
}
