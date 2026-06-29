package com.srk.codingagent.cli;

import com.srk.codingagent.config.ResolvedConfig;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The testable orchestration behind the {@code config [show|path]} subcommand (04-apis § 1.2,
 * US-8): it makes the resolved configuration and the config file locations inspectable. It is the
 * seam {@link Main} delegates the {@code config} subcommand to.
 *
 * <p><b>Actions.</b> {@link #show()} prints the resolved configuration — the single immutable
 * {@link ResolvedConfig} produced by the layered merge (flags &gt; project &gt; global &gt;
 * defaults, ADR-0009), so the developer sees the effective values the agent runs against (US-8 —
 * "show resolved config"). {@link #path()} prints the on-disk config file locations (US-8 — "file
 * locations") so the developer knows which files to edit. The resolution itself is reused from
 * {@link Main}'s existing {@code ConfigResolver}/{@code ConfigLocations} wiring; this command only
 * renders the result, keeping the layered-merge logic in one place.
 *
 * <p><b>Library/CLI split (NFR-LOG, 04-apis § 1.6).</b> This CLI-layer command owns its
 * user-facing output (it writes to the injected {@link PrintStream}); the config library it
 * renders never writes to stdout/stderr.
 */
public final class ConfigCommand {

    private static final Logger LOGGER = LoggerFactory.getLogger(ConfigCommand.class);

    private static final String SECRET_AWS_PROFILE_NONE = "(default credential chain)";

    private final ResolvedConfig config;
    private final Path globalConfigPath;
    private final Path projectConfigPath;
    private final PrintStream out;

    /**
     * Creates a command over the already-resolved configuration and the config file locations.
     *
     * @param config            the resolved configuration to render for {@code show}; must not be
     *                          {@code null}.
     * @param globalConfigPath  the global config file location for {@code path}; must not be
     *                          {@code null}.
     * @param projectConfigPath the project config file location for {@code path}; must not be
     *                          {@code null}.
     * @param out               the stream the command output is written to; must not be
     *                          {@code null}.
     * @throws NullPointerException if any argument is {@code null}.
     */
    public ConfigCommand(ResolvedConfig config, Path globalConfigPath, Path projectConfigPath,
            PrintStream out) {
        this.config = Objects.requireNonNull(config, "config");
        this.globalConfigPath = Objects.requireNonNull(globalConfigPath, "globalConfigPath");
        this.projectConfigPath = Objects.requireNonNull(projectConfigPath, "projectConfigPath");
        this.out = Objects.requireNonNull(out, "out");
    }

    /**
     * Dispatches a parsed {@code config} invocation to its action.
     *
     * @param action the parsed action (04-apis § 1.2); must not be {@code null}.
     * @return the process exit code the action returns.
     * @throws NullPointerException if {@code action} is {@code null}.
     */
    public int run(CliArguments.ConfigAction action) {
        Objects.requireNonNull(action, "action");
        return switch (action) {
            case SHOW -> show();
            case PATH -> path();
        };
    }

    /**
     * Prints the resolved configuration — the effective values the agent runs against after the
     * layered merge (US-8). Always succeeds.
     *
     * @return {@link ExitCode#OK} ({@code 0}).
     */
    public int show() {
        out.println("Resolved config:");
        out.println("  modelId: " + config.modelId());
        out.println("  permissionMode: " + config.permissionMode());
        out.println("  region: " + config.region());
        out.println("  awsProfile: " + (config.awsProfile() == null
                ? SECRET_AWS_PROFILE_NONE : config.awsProfile()));
        out.println("  subAgentMax: " + config.subAgentMax());
        out.println("  summarizerModelId: " + (config.summarizerModelId() == null
                ? "(none)" : config.summarizerModelId()));
        out.println("  contextCompactThreshold: " + config.contextCompactThreshold());
        out.println("  outputMaxInlineBytes: " + config.outputMaxInlineBytes());
        out.println("  verifyMaxIterations: " + config.verifyMaxIterations());
        out.println("  commandTimeoutSeconds: " + config.commandTimeoutSeconds());
        out.println("  bedrockCallConnectTimeoutSeconds: " + config.bedrockCallConnectTimeoutSeconds());
        out.println("  bedrockCallResponseTimeoutSeconds: " + config.bedrockCallResponseTimeoutSeconds());
        ResolvedConfig.Commands commands = config.commands();
        out.println("  commands.build: " + nullable(commands.build()));
        out.println("  commands.test: " + nullable(commands.test()));
        out.println("  commands.lint: " + nullable(commands.lint()));
        LOGGER.info("config show: printed resolved config (model={})", config.modelId());
        return ExitCode.OK.code();
    }

    /**
     * Prints the on-disk config file locations (US-8) so the developer knows which files supply
     * the global and project layers. Always succeeds.
     *
     * @return {@link ExitCode#OK} ({@code 0}).
     */
    public int path() {
        out.println("Config file locations:");
        out.println("  global:  " + globalConfigPath);
        out.println("  project: " + projectConfigPath);
        LOGGER.info("config path: printed config file locations");
        return ExitCode.OK.code();
    }

    private static String nullable(String value) {
        return value == null ? "(none)" : value;
    }
}
