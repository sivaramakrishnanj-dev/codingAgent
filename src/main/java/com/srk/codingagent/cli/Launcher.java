package com.srk.codingagent.cli;

/**
 * The process entry point ({@code Main-Class} in the shaded jar manifest). It exists solely to
 * apply the {@code --debug} operational-log-level decision <em>before</em> any logger is created
 * (04-apis § 1.3, 05-operations § 3), then delegate to {@link Main#run(String[])} and map the
 * returned exit code onto the process exit status.
 *
 * <p><b>Why a separate entry point (load-bearing).</b> The {@code slf4j-simple} binding reads its
 * default-level system property once, when the provider binds on the first
 * {@code LoggerFactory.getLogger(...)} call (see {@link DebugLogging}). {@link Main} holds a
 * static logger, so loading {@code Main} binds the provider at the baseline level. This launcher
 * therefore deliberately holds <em>no</em> logger of its own: its first action is
 * {@link DebugLogging#applyFrom(String[])}, which sets the level property, and only then does it
 * touch {@link Main}. Reordering these — or adding a logger here — would let the provider bind at
 * {@code INFO} before {@code --debug} could raise it, silently dropping the {@code --debug}
 * internals the flag promises.
 *
 * <p>This class is the bootstrap shell counterpart to {@link Main}: it is a thin
 * {@link System#exit(int)} wrapper with no business logic, so the coverage gate excludes it while
 * the testable units — the level mapping in {@link DebugLogging} and the launch logic in
 * {@link Main#run(String[])} — are exercised directly.
 */
public final class Launcher {

    private Launcher() {
        // Process entry point; not instantiable.
    }

    /**
     * Process entry point: applies the {@code --debug} log-level decision, runs the CLI, and
     * terminates the JVM with the returned exit code.
     *
     * @param args command-line arguments; forwarded to {@link Main#run(String[])} after the
     *             {@code --debug} level decision is applied.
     */
    public static void main(String[] args) {
        DebugLogging.applyFrom(args);
        System.exit(Main.run(args));
    }
}
