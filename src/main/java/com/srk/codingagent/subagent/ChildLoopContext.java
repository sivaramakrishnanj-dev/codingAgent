package com.srk.codingagent.subagent;

import com.srk.codingagent.permission.PermissionGate;
import com.srk.codingagent.persistence.EventLog;
import java.util.Objects;

/**
 * The resolved, isolated context the {@link SubAgentOrchestrator} hands to a
 * {@link ChildAgentLoopFactory} to build one child {@link com.srk.codingagent.loop.AgentLoop}
 * (ADR-0010). It bundles the three things the orchestrator's isolation policy decides and the
 * factory must build the child loop with — and nothing the parent could leak into the child:
 *
 * <ul>
 *   <li><b>{@link #childSessionId()}</b> — the child's own session id (boundary-captured,
 *       ADR-0005), so the child writes to its own JSONL and the parent's
 *       {@code SUBAGENT_SPAWN}/{@code SUBAGENT_RESULT} events can name it (AC-17.5).</li>
 *   <li><b>{@link #childLog()}</b> — the child's OWN {@link EventLog} (its own session log,
 *       distinct from the parent's), so with N=1 there is no concurrent append to the same
 *       log and the shared-writer hazard never arises (ADR-0010 Notes; the N&gt;1
 *       synchronization constraint is recorded as a future seam, not built here).</li>
 *   <li><b>{@link #childGate()}</b> — a {@link PermissionGate} backed by a fresh, empty
 *       {@code GrantStore} (via {@code GrantStore.forSubAgent}). The child runs the configured
 *       permission mode FRESH and inherits NONE of the parent's remembered grants (AC-10.6,
 *       RD-5, INV-10, CT-INV-9).</li>
 *   <li><b>{@link #modelId()}</b> — the resolved child model (the parent's unless the spec
 *       overrode it, AC-17.2). The child resolves its own capability profile independently
 *       from this id (ADR-0002).</li>
 *   <li><b>{@link #prompt()}</b> — the scoped prompt that seeds the child's fresh
 *       {@code messages[]} (AC-17.1). The child cannot see the parent's messages[] and
 *       vice-versa (AC-17.2).</li>
 * </ul>
 *
 * <p>This carries no reference to the parent's {@code messages[]}, the parent's grant store,
 * or the parent's log — the isolation is structural: the factory is handed only the child's
 * own collaborators, so it cannot accidentally wire parent state into the child.
 *
 * @param childSessionId the child's session id; non-blank.
 * @param childLog       the child's own session event log; must not be {@code null}.
 * @param childGate      the child's permission gate (fresh empty grant store); must not be
 *                       {@code null}.
 * @param modelId        the resolved child model id; non-blank.
 * @param prompt         the scoped prompt seeding the child's context; non-blank.
 */
public record ChildLoopContext(
        String childSessionId,
        EventLog childLog,
        PermissionGate childGate,
        String modelId,
        String prompt) {

    /**
     * Validates the context.
     *
     * @throws NullPointerException     if {@code childLog} or {@code childGate} is
     *                                  {@code null}.
     * @throws IllegalArgumentException if {@code childSessionId}, {@code modelId}, or
     *                                  {@code prompt} is blank.
     */
    public ChildLoopContext {
        if (Objects.requireNonNull(childSessionId, "childSessionId").isBlank()) {
            throw new IllegalArgumentException("childSessionId must be non-blank");
        }
        Objects.requireNonNull(childLog, "childLog");
        Objects.requireNonNull(childGate, "childGate");
        if (Objects.requireNonNull(modelId, "modelId").isBlank()) {
            throw new IllegalArgumentException("modelId must be non-blank");
        }
        if (Objects.requireNonNull(prompt, "prompt").isBlank()) {
            throw new IllegalArgumentException("prompt must be non-blank");
        }
    }
}
