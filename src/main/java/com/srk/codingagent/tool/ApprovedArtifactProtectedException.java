package com.srk.codingagent.tool;

/**
 * Thrown by {@link GreenfieldArtifactStore#write} when a truncating write would clobber an artifact
 * that already carries a <em>prior</em> greenfield approval stamp (AC-1.5) — i.e. a deliverable a
 * previous, finalized greenfield run already approved (D13 — per-session artifact isolation).
 *
 * <p><b>Why this is its own subtype.</b> It is a refused write (so it is a
 * {@link ToolInvocationException}: the {@link ToolRegistry} maps it to an {@code error} tool result,
 * and a {@code write_artifact} the model emits over an approved artifact surfaces as a correctable
 * error rather than crashing the agent). But it is a <em>specific, safety-critical</em> refusal — the
 * one that prevents the silent loss of an approved deliverable the field report (T-3.2-RD-D13)
 * identified as destructive — so it carries its own type, letting the driver / CLI / tests match it
 * precisely and distinguish it from an ordinary path-confinement refusal (AC-1.4). Effective Java
 * Item 71/72: a meaningful, specific exception type over an overloaded message.
 *
 * <p>The distinction the store relies on (ADR-0012, AC-1.5): a greenfield phase artifact gains its
 * approval stamp only <em>after</em> the developer approved that phase and the session advanced, and
 * a single greenfield run never re-truncates an artifact it already stamped (it stamps, then advances
 * to the next phase). So a truncating write that finds an already-stamped artifact on disk is, by
 * construction, a <em>different</em> run writing over a prior run's approved deliverable — exactly the
 * destructive case to refuse.
 */
public final class ApprovedArtifactProtectedException extends ToolInvocationException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception with a model-/developer-facing message naming the protected artifact.
     *
     * @param message the diagnostic message surfaced to the model in the error tool result.
     */
    public ApprovedArtifactProtectedException(String message) {
        super(message);
    }
}
