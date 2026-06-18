package com.srk.codingagent.cli;

/**
 * The agent-process exit codes.
 *
 * <p>Each constant pairs a symbolic name with the numeric process exit status the
 * CLI returns to the shell. The full set is pinned by the data model
 * ({@code 03-data-model.md} § 4) and the exit-code contract
 * ({@code 06-formal/cli-exit-codes.md}). These are the <em>agent-process</em> exit
 * codes, distinct from the exit code of a build/test command the agent runs (that
 * verification signal lives in a {@code CommandResult}, a later task — contract
 * guard G4).
 *
 * <p>The enum is defined completely here because it is a small shared value type
 * that several later tasks consume (the permission gate's denial path, SIGINT
 * handling, the model-backend and credential paths). T-0.2 itself only exercises
 * the {@link #OK} and {@link #USAGE_CONFIG} paths: a clean launch returns
 * {@code OK}, and a malformed/unknown configuration value makes {@code Main} surface
 * {@code USAGE_CONFIG} (AC-8.5, AC-6.4). The remaining codes are wired by the tasks
 * that own their trigger conditions.
 */
public enum ExitCode {

    /** Requested work completed / clean exit. Numeric status {@code 0}. */
    OK(0),

    /** Unexpected internal error (uncaught fault). Numeric status {@code 1}. */
    INTERNAL(1),

    /**
     * Bad invocation or invalid/missing configuration, detected before doing
     * work: unknown flag, malformed/unknown config key (AC-8.5), or missing
     * required field (AC-6.4). Numeric status {@code 2}.
     */
    USAGE_CONFIG(2),

    /** The user declined a permission prompt / aborted the run. Numeric status {@code 3}. */
    USER_ABORTED(3),

    /** The model backend (Bedrock) failed unrecoverably. Numeric status {@code 4}. */
    MODEL_BACKEND(4),

    /** The context budget was exhausted and could not be recovered. Numeric status {@code 5}. */
    CONTEXT_EXHAUSTED(5),

    /** The process was interrupted (SIGINT). Numeric status {@code 130}. */
    INTERRUPTED(130);

    private final int code;

    ExitCode(int code) {
        this.code = code;
    }

    /**
     * Returns the numeric process exit status for this code.
     *
     * @return the integer exit status passed to {@link System#exit(int)}.
     */
    public int code() {
        return code;
    }
}
