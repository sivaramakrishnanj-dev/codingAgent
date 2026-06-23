package com.srk.codingagent.permission;

import com.srk.codingagent.config.PermissionMode;
import com.srk.codingagent.persistence.OperationClass;
import com.srk.codingagent.persistence.PermissionDecisionOutcome;
import com.srk.codingagent.tool.RunCommandTool;
import com.srk.codingagent.tool.WriteArtifactTool;
import com.srk.codingagent.tool.WriteFileTool;
import java.nio.file.Path;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The Permission Gate (component C8, ADR-0004): the single authorization chokepoint that
 * sits between the model's tool-use and the {@link com.srk.codingagent.tool.ToolRegistry}
 * dispatch. For every tool call the agent loop (T-0.8) routes a {@link GateRequest} here;
 * the gate classifies it (Class R/X), applies the active {@link PermissionMode}, enforces
 * the RD-2 destructive denylist, and — under {@code ASK_ONCE_THEN_REMEMBER} — consults and
 * records {@link Grant}s in the lineage's {@link GrantStore}. It returns a
 * {@link GateDecision} the loop turns into the {@code PERMISSION_DECISION} event and the
 * {@code S3 → S4} (approve) / {@code S3 → S2} (deny) transition.
 *
 * <p><b>The four modes (AC-9.1, the ADR-0004 decision table):</b>
 * <table border="1">
 *   <caption>Gate outcomes by mode and class</caption>
 *   <tr><th>Mode</th><th>Class R</th><th>Class X</th><th>Denylisted command</th></tr>
 *   <tr><td>{@code READ_ONLY}</td><td>auto</td><td>deny</td><td>deny</td></tr>
 *   <tr><td>{@code ASK_EVERY_TIME}</td><td>auto</td><td>prompt</td><td>prompt</td></tr>
 *   <tr><td>{@code ASK_ONCE_THEN_REMEMBER}</td><td>auto</td><td>prompt then remember</td>
 *       <td>always prompt (never remembered)</td></tr>
 *   <tr><td>{@code UNRESTRICTED}</td><td>auto</td><td>auto</td><td>always prompt</td></tr>
 * </table>
 *
 * <p><b>Order of evaluation (load-bearing, security).</b> For a {@code run_command}, the
 * denylist test runs <em>first</em> (AC-10.4, INV-9): a destructive command always prompts
 * (denied outright in {@code READ_ONLY}) and never auto-approves from a remembered grant —
 * even in {@code UNRESTRICTED} (AC-9.3 carve-out). Class R is auto-approved in every mode
 * before any mode-specific logic (AC-9.6). A denylisted command never produces or matches
 * a grant.
 *
 * <p><b>Sanctioned greenfield design-markdown write (ADR-0012, RD-7, AC-1.2, AC-2.1).</b> After
 * the denylist test, a {@code write_artifact} Class-X call is auto-approved in every mode without a
 * separate per-operation prompt. ADR-0012 makes the design-markdown write the one write the agent
 * is allowed before the task breakdown is approved ("the agent writes only design markdown … until
 * the breakdown is approved"), and RD-7 / AC-1.2 / AC-2.1 require the requirements/design/tasks
 * <em>content</em> to be persisted into the target project. The carve-out is narrow and safe by
 * construction: {@code write_artifact} is offered only by the greenfield pre-approval registry (it
 * is absent from the brownfield parent registry and the sub-agent child registry) and its
 * {@link com.srk.codingagent.tool.GreenfieldArtifactStore} confines every write to the target repo's
 * {@code design/} directory, so it can never reach a source file. AC-1.4 stays structurally enforced
 * by the withheld source-write tools, and the general RD-4 source-write Class-X operations
 * ({@code write_file}/{@code edit_file}/{@code run_command}, the web-lookup delegate, the sub-agent
 * spawn) keep their full per-mode gating — AC-9.4's "prompt before every Class X" is unchanged for
 * them. Without this, on a live {@code ASK_EVERY_TIME} run the per-op {@code write_artifact} prompt
 * and the phase-approval gate contend for the developer's single stdin line, starving one of them so
 * the deliverable content is never persisted (the T-3.2-RD-D8 regression).
 *
 * <p><b>Out of scope here (T-0.8 / T-1.1).</b> The gate decides; it does not run the tool,
 * append events, or emit the {@code TOOL_RESULT(denied)} — the loop does that with the
 * {@link GateDecision} and its {@link GateDecision#toPayload(String)}. The prompt UI is the
 * injected {@link Approver} (T-1.1). The sub-agent orchestrator (T-2.3) gives a child a
 * fresh {@link GrantStore} via {@link GrantStore#forSubAgent(String)}.
 */
public final class PermissionGate {

    private static final Logger LOGGER = LoggerFactory.getLogger(PermissionGate.class);

    private final PermissionMode mode;
    private final GrantStore grants;
    private final Approver approver;

    /**
     * Creates a gate for one session lineage.
     *
     * @param mode     the active permission mode (from
     *                 {@link com.srk.codingagent.config.ResolvedConfig#permissionMode()});
     *                 must not be {@code null}.
     * @param grants   the lineage's grant store (used only under
     *                 {@code ASK_ONCE_THEN_REMEMBER}); must not be {@code null}.
     * @param approver the seam that collects approve/deny answers (T-1.1); must not be
     *                 {@code null}.
     * @throws NullPointerException if any argument is {@code null}.
     */
    public PermissionGate(PermissionMode mode, GrantStore grants, Approver approver) {
        this.mode = Objects.requireNonNull(mode, "mode");
        this.grants = Objects.requireNonNull(grants, "grants");
        this.approver = Objects.requireNonNull(approver, "approver");
    }

    /**
     * Evaluates one tool call against the active mode, the denylist, and the grant store,
     * returning the decision the loop consumes.
     *
     * @param request the operation to authorize; must not be {@code null}.
     * @return the gate decision (approve/deny, class, mode, matched grant, prompted,
     *         denylisted).
     * @throws NullPointerException if {@code request} is {@code null}.
     */
    public GateDecision evaluate(GateRequest request) {
        Objects.requireNonNull(request, "request");

        // Class R is non-gated in every mode (AC-9.6, RD-4): auto-approve before any
        // mode-specific or denylist logic.
        if (request.operationClass() == OperationClass.READ) {
            return autoApprove(request, null, false);
        }

        // Class X: a run_command's command string is tested against the denylist FIRST
        // (AC-10.4, INV-9). A denylisted command never auto-approves and never remembers.
        boolean denylisted = isDenylistedCommand(request);
        if (denylisted) {
            return evaluateDenylisted(request);
        }

        // The sanctioned greenfield pre-approval design-markdown write (ADR-0012, RD-7, AC-1.2,
        // AC-2.1): write_artifact is the ONE write the agent is allowed before the breakdown is
        // approved ("the agent writes only design markdown … until the breakdown is approved").
        // It is auto-approved here WITHOUT a separate per-operation prompt so the deliverable
        // content actually reaches the phase artifact on a live run, where the gate's approver and
        // the phase-approval gate share one stdin and a single per-op prompt for write_artifact
        // would otherwise consume the developer's one 'y' and starve the phase gate (the D8
        // regression). This carve-out is narrow and safe by construction: write_artifact is offered
        // ONLY by the greenfield pre-approval registry (never the brownfield/child registries) and
        // its GreenfieldArtifactStore confines every write to the target repo's design/ directory,
        // so it can never reach a source file — AC-1.4 stays structurally enforced by the withheld
        // source-write tools (write_file/edit_file/run_command). It runs AFTER the denylist test so
        // a destructive command never benefits from it, and the general RD-4 source-write Class X
        // operations keep their full per-mode gating (AC-9.4 unchanged for them).
        if (isSanctionedArtifactWrite(request)) {
            LOGGER.debug("Auto-approving the sanctioned greenfield design-markdown write {} "
                    + "(ADR-0012; design/-confined, source unreachable)", request.toolUseId());
            return autoApprove(request, null, false);
        }

        return switch (mode) {
            case READ_ONLY -> deny(request, false); // Class X denied (AC-9.2; RD-6 web lookup too)
            case UNRESTRICTED -> autoApprove(request, null, false); // auto except denylist (AC-9.3)
            case ASK_EVERY_TIME -> prompt(request, false); // prompt every time (AC-9.4)
            case ASK_ONCE_THEN_REMEMBER -> evaluateRememberMode(request);
        };
    }

    /**
     * {@code ASK_ONCE_THEN_REMEMBER}: auto-approve when a remembered grant matches,
     * otherwise prompt and (on approval) record the grant (AC-9.5, AC-10.3, RD-1).
     */
    private GateDecision evaluateRememberMode(GateRequest request) {
        Grant matched = findMatchingGrant(request);
        if (matched != null) {
            LOGGER.debug("Auto-approving {} via remembered grant {}",
                    request.toolUseId(), matched.matchKey());
            return autoApprove(request, matched.matchKey(), false);
        }
        PermissionDecisionOutcome outcome = approver.confirm(request);
        if (outcome == PermissionDecisionOutcome.APPROVE) {
            String key = matchKeyFor(request);
            if (key != null) {
                grants.remember(key);
            }
            return new GateDecision(PermissionDecisionOutcome.APPROVE, request.operationClass(),
                    mode, null, true, false);
        }
        return deny(request, true);
    }

    /**
     * A denylisted command: always prompt (never auto-approve, never remember), and deny
     * outright in {@code READ_ONLY} (AC-9.2, AC-10.4, INV-9). The decision carries no
     * matched grant in any mode.
     */
    private GateDecision evaluateDenylisted(GateRequest request) {
        if (mode == PermissionMode.READ_ONLY) {
            LOGGER.info("Denying denylisted command in READ_ONLY: {}", request.command());
            return new GateDecision(PermissionDecisionOutcome.DENY, request.operationClass(),
                    mode, null, false, true);
        }
        LOGGER.info("Prompting for denylisted command (mode {}): {}", mode, request.command());
        PermissionDecisionOutcome outcome = approver.confirm(request);
        return new GateDecision(outcome, request.operationClass(), mode, null, true, true);
    }

    /** The RD-1 grant lookup: exact key for commands/coarse tools, subtree for writes. */
    private Grant findMatchingGrant(GateRequest request) {
        if (isWrite(request) && request.filePath() != null) {
            return grants.findWriteCovering(Path.of(request.filePath()));
        }
        String key = matchKeyFor(request);
        return key == null ? null : grants.findExact(key);
    }

    /** The RD-1 match key for a request, by tool kind. */
    private String matchKeyFor(GateRequest request) {
        if (isCommand(request) && request.command() != null) {
            return MatchKey.forCommand(request.command());
        }
        if (isWrite(request) && request.filePath() != null) {
            return MatchKey.forWrite(Path.of(request.filePath()));
        }
        return MatchKey.forTool(request.toolName());
    }

    private boolean isDenylistedCommand(GateRequest request) {
        return isCommand(request) && request.command() != null
                && DestructiveCommandDenylist.isDestructive(request.command());
    }

    private static boolean isCommand(GateRequest request) {
        return RunCommandTool.NAME.equals(request.toolName());
    }

    private static boolean isWrite(GateRequest request) {
        return WriteFileTool.NAME.equals(request.toolName());
    }

    /**
     * Whether this request is the sanctioned greenfield pre-approval design-markdown write
     * ({@code write_artifact}) ADR-0012 makes the one write allowed before the breakdown is
     * approved (RD-7, AC-1.2, AC-2.1). It is identified by tool name alone: {@code write_artifact}
     * is offered only by the greenfield pre-approval registry and is confined to the target repo's
     * {@code design/} directory by its {@link com.srk.codingagent.tool.GreenfieldArtifactStore}, so
     * it can never target a source file. The general source-write Class X tools
     * ({@code write_file}/{@code edit_file}/{@code run_command}) are NOT covered and keep their full
     * per-mode gating (AC-9.4).
     */
    private static boolean isSanctionedArtifactWrite(GateRequest request) {
        return WriteArtifactTool.NAME.equals(request.toolName());
    }

    private GateDecision autoApprove(GateRequest request, String matchedGrant, boolean denylisted) {
        return new GateDecision(PermissionDecisionOutcome.APPROVE, request.operationClass(),
                mode, matchedGrant, false, denylisted);
    }

    private GateDecision prompt(GateRequest request, boolean denylisted) {
        PermissionDecisionOutcome outcome = approver.confirm(request);
        return new GateDecision(outcome, request.operationClass(), mode, null, true, denylisted);
    }

    private GateDecision deny(GateRequest request, boolean prompted) {
        return new GateDecision(PermissionDecisionOutcome.DENY, request.operationClass(),
                mode, null, prompted, false);
    }
}
