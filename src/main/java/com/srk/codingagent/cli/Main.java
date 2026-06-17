package com.srk.codingagent.cli;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Command-line entry point for the coding agent.
 *
 * <p>Walking-skeleton scaffold (T-0.1): launches and exits cleanly with the
 * success code. The full exit-code contract (internal-error, usage, aborted,
 * model-backend, context-exhausted, interrupted) and the interactive REPL are
 * implemented by later tasks (the exit-code contract lands in T-0.9); this class
 * provides only the launchable seam those tasks extend.
 *
 * <p>The launch logic lives in {@link #run(String[])}, which returns a process
 * exit code instead of calling {@link System#exit(int)} directly, so it is
 * unit-testable without terminating the test JVM. {@link #main(String[])} is the
 * thin shell that maps the returned code onto the process exit status.
 */
public final class Main {

    private static final Logger LOGGER = LoggerFactory.getLogger(Main.class);

    /**
     * Process exit code for a successful, clean launch.
     *
     * <p>Pinned by the CLI exit contract ({@code 0} = success) and by the T-0.1
     * acceptance criterion that the empty CLI launches and exits {@code 0}. The
     * remaining exit codes are introduced when the full contract is built (T-0.9).
     */
    static final int SUCCESS_EXIT_CODE = 0;

    private Main() {
        // Utility entry point; not instantiable.
    }

    /**
     * Launches the agent CLI.
     *
     * <p>At this milestone the CLI has no behavior beyond a clean launch: it logs
     * a startup line and returns the success exit code. Argument parsing, the
     * REPL, and the full exit-code dispatch are added by later tasks.
     *
     * @param args command-line arguments; currently unused (accepted for the
     *             stable {@code main}-style signature later tasks build on).
     *             Must not be {@code null}.
     * @return the process exit code; {@link #SUCCESS_EXIT_CODE} for a clean launch.
     */
    public static int run(String[] args) {
        LOGGER.info("codingagent CLI starting (skeleton; {} argument(s))",
                args == null ? 0 : args.length);
        return SUCCESS_EXIT_CODE;
    }

    /**
     * Process entry point. Delegates to {@link #run(String[])} and terminates the
     * JVM with the returned exit code.
     *
     * @param args command-line arguments forwarded to {@link #run(String[])}.
     */
    public static void main(String[] args) {
        System.exit(run(args));
    }
}
