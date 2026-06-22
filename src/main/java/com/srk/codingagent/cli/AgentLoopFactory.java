package com.srk.codingagent.cli;

import com.srk.codingagent.config.ResolvedConfig;
import com.srk.codingagent.loop.AgentLoop;
import com.srk.codingagent.loop.BudgetGuard;
import com.srk.codingagent.model.converse.ModelClient;
import com.srk.codingagent.model.credentials.BedrockClientFactory;
import com.srk.codingagent.model.credentials.BedrockCredentials;
import com.srk.codingagent.model.credentials.CredentialResolver;
import com.srk.codingagent.permission.Approver;
import com.srk.codingagent.permission.GrantStore;
import com.srk.codingagent.permission.PermissionGate;
import com.srk.codingagent.persistence.EventLog;
import com.srk.codingagent.tool.CommandExecutor;
import com.srk.codingagent.tool.ReadFileTool;
import com.srk.codingagent.tool.RunCommandTool;
import com.srk.codingagent.tool.ToolHandler;
import com.srk.codingagent.tool.ToolRegistry;
import com.srk.codingagent.tool.WriteFileTool;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;

/**
 * Assembles the production {@link AgentLoop} for a one-shot run from a resolved
 * configuration: it resolves SigV4 credentials (ADR-0011), builds the Bedrock client
 * (C4), composes the read/write/run tools (C7) into a registry, wires the permission gate
 * (C8) over the configured mode with the supplied one-shot {@link Approver}, opens the
 * session event log (C14/C15), and hands the four collaborators to the {@link AgentLoop}
 * (C2).
 *
 * <p>This is the production-only composition root the one-shot launcher uses; it makes the
 * single un-unit-testable step — constructing a real Bedrock client from real credentials —
 * a thin, isolated seam, so the testable run-and-map logic lives entirely in
 * {@link OneShotRunner} (driven by a scripted Bedrock double). The factory makes no
 * Converse call itself; the loop does, only when run.
 *
 * <p><b>Credential failure surfaces (AC-8.9 → exit 4).</b> {@link #create} lets a
 * {@link com.srk.codingagent.model.credentials.CredentialResolutionException} propagate so
 * the launcher's {@link OneShotRunner} maps it to exit {@code 4}; the factory does not
 * swallow it (no credentials means no usable Bedrock, a model-backend problem).
 */
public final class AgentLoopFactory {

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
     *                       to (INV-10); non-blank.
     * @param log            the open session event log the loop appends to; must not be
     *                       {@code null}.
     * @param approver       the approval seam (the one-shot {@link NonInteractiveApprover}
     *                       in production); must not be {@code null}.
     * @return a composed {@link AgentLoop} ready to run a prompt; never {@code null}.
     * @throws NullPointerException if a required argument is {@code null}.
     * @throws com.srk.codingagent.model.credentials.CredentialResolutionException if no
     *         usable SigV4 credentials can be resolved (AC-8.9 → exit 4).
     */
    public AgentLoop create(ResolvedConfig config, Path workspaceRoot, String sessionLineage,
            EventLog log, Approver approver) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(workspaceRoot, "workspaceRoot");
        Objects.requireNonNull(log, "log");
        Objects.requireNonNull(approver, "approver");

        BedrockCredentials credentials = credentialResolver.resolve(config.awsProfile());
        BedrockRuntimeClient bedrock = clientFactory.create(credentials, config.region());
        ModelClient modelClient = new ModelClient(bedrock);

        ToolRegistry tools = toolRegistry(config, workspaceRoot);
        PermissionGate gate = new PermissionGate(
                config.permissionMode(), GrantStore.forSession(sessionLineage), approver);

        // ADR-0005: the loop draws every event's timestamp from this boundary clock.
        return new AgentLoop(modelClient, tools, gate, log,
                () -> Instant.now().toString(), BudgetGuard.NONE, config.modelId(), null);
    }

    private static ToolRegistry toolRegistry(ResolvedConfig config, Path workspaceRoot) {
        List<ToolHandler> handlers = List.of(
                new ReadFileTool(workspaceRoot),
                new WriteFileTool(workspaceRoot),
                new RunCommandTool(new CommandExecutor(workspaceRoot), config));
        return ToolRegistry.of(handlers);
    }
}
