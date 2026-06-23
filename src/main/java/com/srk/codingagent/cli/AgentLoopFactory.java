package com.srk.codingagent.cli;

import com.srk.codingagent.config.ResolvedConfig;
import com.srk.codingagent.context.OutputDisposer;
import com.srk.codingagent.loop.AgentLoop;
import com.srk.codingagent.loop.TokenBudgetGuard;
import com.srk.codingagent.memory.MemoryStore;
import com.srk.codingagent.model.ModelCapabilityProfile;
import com.srk.codingagent.model.converse.ModelClient;
import com.srk.codingagent.model.credentials.BedrockClientFactory;
import com.srk.codingagent.model.credentials.BedrockCredentials;
import com.srk.codingagent.model.credentials.CredentialResolver;
import com.srk.codingagent.permission.Approver;
import com.srk.codingagent.permission.GrantStore;
import com.srk.codingagent.permission.PermissionGate;
import com.srk.codingagent.persistence.EventLog;
import com.srk.codingagent.persistence.SessionStore;
import com.srk.codingagent.tool.ToolRegistry;
import com.srk.codingagent.workflow.BrownfieldPlaybook;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;

/**
 * Assembles the production {@link AgentLoop} for a one-shot run from a resolved
 * configuration: it resolves SigV4 credentials (ADR-0011), builds the Bedrock client
 * (C4), composes the full live tool set (file/search/run C7/C9/C10, the memory tools C12,
 * and the sub-agent tool C13) into a registry via {@link ToolRegistryComposer}, wires the
 * permission gate (C8) over the configured mode with the supplied one-shot {@link Approver},
 * opens the session event log (C14/C15), and hands the collaborators to the {@link AgentLoop}
 * (C2).
 *
 * <p>This is the production-only composition root the one-shot launcher uses; it makes the
 * single un-unit-testable step — constructing a real Bedrock client from real credentials —
 * a thin, isolated seam, so the testable run-and-map logic lives entirely in
 * {@link OneShotRunner} (driven by a scripted Bedrock double). The factory makes no
 * Converse call itself; the loop does, only when run.
 *
 * <p><b>Tool composition lives in a gate-covered seam.</b> The set of tools the live run
 * offers the model — and the sub-agent {@link ToolRegistryComposer} child-loop wiring — is
 * assembled by {@link ToolRegistryComposer}, which (unlike this factory) is NOT coverage-
 * excluded: it is constructible without a live AWS call (the already-built {@link ModelClient}
 * is passed in), so a unit test pins the wiring contract (which tools the model sees, with
 * which operation classes) under the coverage gate. This factory's job narrows to the one live
 * step plus handing the composer its collaborators.
 *
 * <p><b>Credential failure surfaces (AC-8.9 → exit 4).</b> {@link #create} lets a
 * {@link com.srk.codingagent.model.credentials.CredentialResolutionException} propagate so
 * the launcher's {@link OneShotRunner} maps it to exit {@code 4}; the factory does not
 * swallow it (no credentials means no usable Bedrock, a model-backend problem).
 */
public final class AgentLoopFactory {

    /**
     * The conservative safe-minimum input-token window used for a model id with no known
     * capability profile (ADR-0002 "a safe minimum context window"). v1 ships only Claude
     * profiles populated, so the live default model ({@code us.anthropic.claude-opus-4-8})
     * resolves to its real window through the registry; this fallback only applies to an
     * unrecognized id and is set low so the compaction threshold fires conservatively rather
     * than risking a context overflow on an unvalidated model. T-4.3 (the full capability
     * registry + schema validation) sources this from config {@code NFR-MODEL-CONTEXT-WINDOW}
     * instead; until then it is a compiled-in default in this composition root.
     */
    private static final int CONSERVATIVE_DEFAULT_CONTEXT_WINDOW_TOKENS = 100_000;

    private final CredentialResolver credentialResolver;
    private final BedrockClientFactory clientFactory;

    /**
     * Creates a factory with the production credential resolver and client factory.
     */
    public AgentLoopFactory() {
        this(new CredentialResolver(), new BedrockClientFactory());
    }

    /**
     * Creates a factory with injected collaborators (used to exercise the wiring without
     * touching {@code ~/.aws} or constructing a real client).
     *
     * @param credentialResolver resolves SigV4 credentials from the configured profile /
     *                           default chain (ADR-0011); must not be {@code null}.
     * @param clientFactory      builds the Bedrock client from the resolved credentials and
     *                           region; must not be {@code null}.
     * @throws NullPointerException if either argument is {@code null}.
     */
    AgentLoopFactory(CredentialResolver credentialResolver, BedrockClientFactory clientFactory) {
        this.credentialResolver = Objects.requireNonNull(credentialResolver, "credentialResolver");
        this.clientFactory = Objects.requireNonNull(clientFactory, "clientFactory");
    }

    /**
     * Builds the agent loop for a one-shot run in the given workspace, writing events to
     * the supplied session log.
     *
     * @param config         the resolved configuration (model id, region, profile,
     *                       permission mode, command timeout); must not be {@code null}.
     * @param workspaceRoot  the target repository directory (AC-6.2 — the working
     *                       directory commands and file tools are rooted at); must not be
     *                       {@code null}.
     * @param sessionLineage the session lineage the permission gate's grant store is scoped
     *                       to (INV-10); it is also the repo key + origin session the memory
     *                       tools and the sub-agent orchestrator are scoped to at v1; non-blank.
     * @param log            the open session event log the loop appends to (and the parent log
     *                       the memory-write provenance + sub-agent events are recorded in);
     *                       must not be {@code null}.
     * @param approver       the approval seam (the one-shot {@link NonInteractiveApprover}
     *                       in production); must not be {@code null}.
     * @param sessions       the session store the sub-agent orchestrator opens each child's OWN
     *                       log from (ADR-0010 — the child never shares the parent's log);
     *                       must not be {@code null}.
     * @return a composed {@link AgentLoop} ready to run a prompt; never {@code null}.
     * @throws NullPointerException if a required argument is {@code null}.
     * @throws com.srk.codingagent.model.credentials.CredentialResolutionException if no
     *         usable SigV4 credentials can be resolved (AC-8.9 → exit 4).
     */
    public AgentLoop create(ResolvedConfig config, Path workspaceRoot, String sessionLineage,
            EventLog log, Approver approver, SessionStore sessions) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(workspaceRoot, "workspaceRoot");
        Objects.requireNonNull(log, "log");
        Objects.requireNonNull(approver, "approver");
        Objects.requireNonNull(sessions, "sessions");

        BedrockCredentials credentials = credentialResolver.resolve(config.awsProfile());
        BedrockRuntimeClient bedrock = clientFactory.create(credentials, config.region());
        ModelClient modelClient = new ModelClient(bedrock);

        // INV-10: lift the parent grant store to a local so BOTH the gate and the sub-agent
        // orchestrator (via the composer) share the same lineage-scoped store — the orchestrator
        // mints each child's fresh empty store from it (forSubAgent), and the gate consults it for
        // the parent's remembered grants. ADR-0005: the boundary clock + child-session-id supplier
        // are captured here at the boundary (the orchestrator never calls UUID.randomUUID itself).
        GrantStore parentGrants = GrantStore.forSession(sessionLineage);
        Supplier<String> clock = () -> Instant.now().toString();
        Supplier<String> childSessionIds = () -> UUID.randomUUID().toString();
        ToolRegistry tools = new ToolRegistryComposer(
                modelClient, config, workspaceRoot, log, MemoryStore.forUserHome(), sessions,
                parentGrants, approver, sessionLineage, sessionLineage, clock, childSessionIds)
                .parentRegistry();
        PermissionGate gate = new PermissionGate(config.permissionMode(), parentGrants, approver);

        // ADR-0005: the loop draws every event's timestamp from this boundary clock.
        // US-19/ADR-0006: the disposer reduces oversized tool/command output for context at
        // the configured inline cap (NFR-OUTPUT-MAX-INLINE) while the log keeps the full copy.
        // ADR-0002/0006 (T-2.1): the real budget guard replaces BudgetGuard.NONE. The active
        // model's context window comes from its capability profile (C5 — the live default
        // us.anthropic.claude-opus-4-8 resolves to the Claude window; an unknown id degrades to
        // the conservative safe-minimum), and the 0.85 trigger fraction from config; the guard
        // then fires COMPACT when measured usage.inputTokens >= 0.85 x window (AC-18.1). The
        // guard arithmetic + profile resolution are tested units (TokenBudgetGuard /
        // ModelCapabilityProfile); this composition root only wires them.
        ModelCapabilityProfile profile = ModelCapabilityProfile.forModelId(
                config.modelId(), CONSERVATIVE_DEFAULT_CONTEXT_WINDOW_TOKENS);
        // ADR-0012 (T-1.6): v1 is brownfield-only (02-architecture § 7 "the brownfield loop...
        // Enables now"), so the loop carries the brownfield playbook system prompt — the lever
        // that primes the model to explore-before-edit (AC-4.1/AC-5.1) and verify-after-change
        // (AC-5.3). The playbook content + the verify-loop wiring live in the tested workflow
        // unit; the factory only carries the prompt to the loop's `system` arg.
        return new AgentLoop(modelClient, tools, gate, log,
                clock, TokenBudgetGuard.forConfig(config, profile),
                OutputDisposer.forConfig(config), config.modelId(),
                BrownfieldPlaybook.systemPrompt());
    }
}
