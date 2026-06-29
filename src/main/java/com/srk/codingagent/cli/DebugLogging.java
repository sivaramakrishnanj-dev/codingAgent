package com.srk.codingagent.cli;

/**
 * Maps the {@code --debug} flag onto the operational log level (04-apis § 1.3, 05-operations
 * § 3): {@code --debug} raises the effective SLF4J level to {@code DEBUG} so the
 * {@code --debug}-visible internals are emitted to stderr; without it the level stays at the
 * default {@code INFO} (the level the external-boundary / WARN / ERROR lines sit at). This is
 * the operational logging level, distinct from the JSONL event log (05-operations § 3).
 *
 * <p><b>Why the property is set before the first logger binds (load-bearing).</b> The runtime
 * binding is {@code slf4j-simple} (see {@code pom.xml}), which reads its
 * {@value #DEFAULT_LOG_LEVEL_PROPERTY} system property <em>once</em>, when the configuration is
 * initialized on the first {@code LoggerFactory.getLogger(...)} call that binds the provider —
 * a later change to the property does not re-level already-initialized loggers. So the toggle
 * must be applied <em>before</em> any logger is created. {@link Launcher} (the manifest entry
 * point, which deliberately holds no logger of its own) calls {@link #applyFrom(String[])} as
 * its first action, before it loads {@link Main} (whose static logger would otherwise bind the
 * provider at {@code INFO}).
 *
 * <p>The flag scan here is intentionally a plain membership check rather than the full
 * {@link CliArguments} parse: the level must be decided before the provider binds and before any
 * usage error is surfaced (a malformed invocation still fails fast later in {@link Main#run}, at
 * which point a {@code --debug} session simply gets the more verbose diagnostics). The
 * {@code --debug} flag is also recognized by {@link CliArguments} (orthogonal to the invocation
 * kind, like {@code --mode}) so {@link Main#run} does not reject it as an unknown flag.
 */
public final class DebugLogging {

    /**
     * The {@code slf4j-simple} system property that sets the default level for all loggers
     * ({@code org.slf4j.simpleLogger.defaultLogLevel}). Setting it before the first logger is
     * created selects the effective level for the run.
     */
    public static final String DEFAULT_LOG_LEVEL_PROPERTY = "org.slf4j.simpleLogger.defaultLogLevel";

    /** The level {@code --debug} raises logging to (05-operations § 3: DEBUG is --debug-visible). */
    public static final String DEBUG_LEVEL = "debug";

    /** The default level when {@code --debug} is absent (05-operations § 3: INFO baseline). */
    public static final String DEFAULT_LEVEL = "info";

    /** The CLI flag that raises operational logging to DEBUG (04-apis § 1.3). */
    static final String DEBUG = "--debug";

    private DebugLogging() {
        // Level-mapping holder; not instantiable.
    }

    /**
     * Whether the arguments request {@code --debug}.
     *
     * @param args the raw command-line arguments; {@code null} is treated as no arguments.
     * @return {@code true} when {@code --debug} appears among the arguments.
     */
    public static boolean isDebugRequested(String[] args) {
        if (args == null) {
            return false;
        }
        for (String arg : args) {
            if (DEBUG.equals(arg)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The effective operational log level for the given {@code --debug} state: {@code DEBUG}
     * when requested (05-operations § 3 — {@code --debug}-visible internals), else the default
     * {@code INFO} (05-operations § 3 — the external-boundary/WARN/ERROR baseline).
     *
     * @param debug whether {@code --debug} was requested.
     * @return the {@code slf4j-simple} level token ({@value #DEBUG_LEVEL} or
     *         {@value #DEFAULT_LEVEL}).
     */
    public static String levelFor(boolean debug) {
        return debug ? DEBUG_LEVEL : DEFAULT_LEVEL;
    }

    /**
     * Applies the {@code --debug} log-level decision by setting the {@code slf4j-simple}
     * default-level system property ({@value #DEFAULT_LOG_LEVEL_PROPERTY}) to the level
     * {@link #levelFor(boolean)} selects, and returns the level applied. Call this before any
     * logger is created so the level takes effect for the whole run.
     *
     * @param args the raw command-line arguments; {@code null} is treated as no arguments.
     * @return the level token applied ({@value #DEBUG_LEVEL} when {@code --debug} was present,
     *         else {@value #DEFAULT_LEVEL}).
     */
    public static String applyFrom(String[] args) {
        String level = levelFor(isDebugRequested(args));
        System.setProperty(DEFAULT_LOG_LEVEL_PROPERTY, level);
        return level;
    }
}
