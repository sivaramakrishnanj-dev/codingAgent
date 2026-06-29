package com.srk.codingagent.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.srk.codingagent.config.ConfigDefaults;
import com.srk.codingagent.config.ConfigKeys;
import com.srk.codingagent.config.ConfigLocations;
import com.srk.codingagent.config.ConfigResolver;
import com.srk.codingagent.config.ResolvedConfig;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for {@link ConfigCommand} — the CLI orchestration behind the {@code config [show|path]}
 * subcommand (04-apis § 1.2, US-8).
 *
 * <p>Oracles:
 * <ul>
 *   <li><b>04-apis § 1.2 / US-8</b>: {@code config show} prints the resolved configuration (the
 *       effective values after the layered merge).</li>
 *   <li><b>04-apis § 1.2 / US-8</b>: {@code config path} prints the config file locations.</li>
 * </ul>
 *
 * <p>The SUT (a real {@link ConfigCommand}) renders a real {@link ResolvedConfig} produced by a
 * real {@link ConfigResolver} (so the shown values trace to the resolver/defaults, not to the
 * command) and real {@link ConfigLocations} over a {@code @TempDir} — none mocked. Only the output
 * {@link PrintStream} is captured.
 */
class ConfigCommandTest {

    private final ByteArrayOutputStream sink = new ByteArrayOutputStream();
    private final PrintStream out = new PrintStream(sink, true, StandardCharsets.UTF_8);

    private String output() {
        return sink.toString(StandardCharsets.UTF_8);
    }

    /** A resolved config built from explicit flag overrides plus the spec defaults (ADR-0009). */
    private ResolvedConfig resolved(Map<String, Object> flagOverrides) {
        return new ConfigResolver().resolve(flagOverrides, Map.of(), Map.of());
    }

    private ConfigCommand command(ResolvedConfig config, Path globalPath, Path projectPath) {
        return new ConfigCommand(config, globalPath, projectPath, out);
    }

    @Test
    @DisplayName("04-apis § 1.2 / US-8: config show prints the resolved configuration values")
    void show_printsResolvedConfig(@TempDir Path store) {
        // Oracle: 04-apis § 1.2 "config [show ...] show resolved config" (US-8). The shown output
        // must carry the resolved effective values; the model id and region come from the resolver
        // (here the built-in defaults, ConfigDefaults), so the assertion traces to the resolved
        // config / NFR defaults, not to the command's own literals.
        ConfigLocations locations = new ConfigLocations(store);
        ResolvedConfig config = resolved(Map.of());

        int code = command(config, locations.globalConfig(),
                locations.projectConfigForUnkeyedRepo()).show();

        assertEquals(0, code, "config show succeeds (exit 0)");
        String report = output();
        assertTrue(report.contains(ConfigDefaults.MODEL_ID),
                "US-8: the resolved model id is shown (default " + ConfigDefaults.MODEL_ID + ");\n" + report);
        assertTrue(report.contains(ConfigDefaults.REGION),
                "US-8: the resolved region is shown (default " + ConfigDefaults.REGION + ");\n" + report);
        assertTrue(report.contains(ConfigDefaults.PERMISSION_MODE.name()),
                "US-8: the resolved permission mode is shown;\n" + report);
    }

    @Test
    @DisplayName("04-apis § 1.2 / US-8: config show reflects a flag override (the layered merge)")
    void show_reflectsOverride(@TempDir Path store) {
        // Oracle: US-8 "show resolved config" + ADR-0009 layered merge (flags > project > global >
        // defaults). An override on the highest-precedence (flag) layer must be the value shown, so
        // the command renders the *resolved* value, not the default.
        ConfigLocations locations = new ConfigLocations(store);
        ResolvedConfig config = resolved(Map.of(ConfigKeys.REGION, "eu-west-1"));

        command(config, locations.globalConfig(), locations.projectConfigForUnkeyedRepo()).show();

        assertTrue(output().contains("eu-west-1"),
                "US-8: config show reflects the resolved (overridden) region, not the default;\n" + output());
    }

    @Test
    @DisplayName("04-apis § 1.2 / US-8: config path prints the global and project config locations")
    void path_printsFileLocations(@TempDir Path store) {
        // Oracle: 04-apis § 1.2 "config [... path] ... file locations" (US-8). The output must name
        // the actual global and project config file paths the resolver reads.
        ConfigLocations locations = new ConfigLocations(store);
        Path global = locations.globalConfig();
        Path project = locations.projectConfigForUnkeyedRepo();

        int code = command(resolved(Map.of()), global, project).path();

        assertEquals(0, code, "config path succeeds (exit 0)");
        String report = output();
        assertTrue(report.contains(global.toString()),
                "US-8: the global config file location is shown;\n" + report);
        assertTrue(report.contains(project.toString()),
                "US-8: the project config file location is shown;\n" + report);
    }

    @Test
    @DisplayName("run() dispatches SHOW and PATH to the matching operation")
    void run_dispatchesByAction(@TempDir Path store) {
        // Oracle: 04-apis § 1.2 — show and path are the two config-subcommand actions; run() routes
        // each.
        ConfigLocations locations = new ConfigLocations(store);
        ConfigCommand command = command(resolved(Map.of()), locations.globalConfig(),
                locations.projectConfigForUnkeyedRepo());

        assertEquals(0, command.run(CliArguments.ConfigAction.SHOW), "SHOW dispatches to show()");
        assertTrue(output().contains("Resolved config"), "SHOW routed to the resolved-config render");

        assertEquals(0, command.run(CliArguments.ConfigAction.PATH), "PATH dispatches to path()");
        assertTrue(output().contains("Config file locations"), "PATH routed to the locations render");
    }

    @Test
    @DisplayName("the command rejects null constructor arguments")
    void rejectsNullArgs(@TempDir Path store) {
        ConfigLocations locations = new ConfigLocations(store);
        Path global = locations.globalConfig();
        Path project = locations.projectConfigForUnkeyedRepo();
        ResolvedConfig config = resolved(Map.of());
        assertThrows(NullPointerException.class,
                () -> new ConfigCommand(null, global, project, out));
        assertThrows(NullPointerException.class,
                () -> new ConfigCommand(config, global, project, null));
    }
}
