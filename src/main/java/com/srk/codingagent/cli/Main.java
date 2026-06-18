package com.srk.codingagent.cli;

import com.srk.codingagent.config.ConfigException;
import com.srk.codingagent.config.ConfigLocations;
import com.srk.codingagent.config.ConfigResolver;
import com.srk.codingagent.config.ResolvedConfig;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Command-line entry point for the coding agent.
 *
 * <p>At this milestone the CLI resolves its configuration on startup and then
 * exits cleanly. Configuration is layered by precedence (flags &gt; project &gt;
 * global &gt; defaults) and validated <em>before</em> any model call (ADR-0009);
 * a malformed or unknown configuration value makes the process exit
 * {@link ExitCode#USAGE_CONFIG} ({@code 2}) with a stderr line naming the offending
 * key (AC-8.5, AC-6.4). The full exit-code dispatch (model-backend, abort,
 * interrupt) and the interactive REPL are implemented by later tasks; this class
 * provides only the launchable seam those tasks extend.
 *
 * <p>The launch logic lives in {@link #run(String[])}, which returns a process exit
 * code instead of calling {@link System#exit(int)} directly, so it is
 * unit-testable without terminating the test JVM. {@link #main(String[])} is the
 * thin shell that maps the returned code onto the process exit status.
 */
public final class Main {

    private static final Logger LOGGER = LoggerFactory.getLogger(Main.class);

    /**
     * Process exit code for a successful, clean launch ({@code 0}).
     *
     * <p>Pinned by the CLI exit contract ({@code 0} = success); equal to
     * {@link ExitCode#OK}. Retained as a named constant for the success path and
     * the launch tests.
     */
    static final int SUCCESS_EXIT_CODE = ExitCode.OK.code();

    private Main() {
        // Utility entry point; not instantiable.
    }

    /**
     * Launches the agent CLI: resolves configuration, then exits.
     *
     * <p>Configuration is resolved from the default file locations
     * ({@code ~/.codingagent/...}) plus an empty flag layer (CLI flag parsing is a
     * later task). On a configuration error the method logs the failure, prints a
     * stderr line naming the offending key, and returns
     * {@link ExitCode#USAGE_CONFIG}. Otherwise it returns {@link #SUCCESS_EXIT_CODE}.
     * No model call is made yet, so a successful resolution still exits {@code 0}.
     *
     * @param args command-line arguments; currently unused for behavior (accepted
     *             for the stable {@code main}-style signature later tasks build on).
     *             May be {@code null}.
     * @return the process exit code: {@link #SUCCESS_EXIT_CODE} on a clean launch,
     *         {@link ExitCode#USAGE_CONFIG} ({@code 2}) on a configuration error.
     */
    public static int run(String[] args) {
        LOGGER.info("codingagent CLI starting (resolving config; {} argument(s))",
                args == null ? 0 : args.length);
        try {
            ResolvedConfig config = resolveConfig();
            LOGGER.info("codingagent CLI started with model '{}' in mode {}",
                    config.modelId(), config.permissionMode());
            return SUCCESS_EXIT_CODE;
        } catch (ConfigException e) {
            // Fail-fast (ADR-0009): a bad/unknown config value must stop the run
            // before any model call. G2: the stderr line names the offending key.
            LOGGER.error("configuration error for key '{}': {}", e.key(), e.getMessage());
            System.err.println("codingagent: configuration error: " + e.getMessage());
            return ExitCode.USAGE_CONFIG.code();
        }
    }

    private static ResolvedConfig resolveConfig() {
        ConfigLocations locations = ConfigLocations.forUserHome();
        return new ConfigResolver().resolve(
                locations.globalConfig(),
                locations.projectConfigForUnkeyedRepo(),
                Map.of());
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
