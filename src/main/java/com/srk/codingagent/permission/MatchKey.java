package com.srk.codingagent.permission;

import com.srk.codingagent.tool.RunCommandTool;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Builds the RD-1 normalized match key for a Class-X operation (ADR-0004, OQ-E part 1).
 * The key is what an {@code ASK_ONCE_THEN_REMEMBER} {@link Grant} stores and what a later
 * operation is matched against, so approving {@code mvn test} remembers
 * {@code run_command:mvn test} and a later {@code mvn test -X} auto-approves, while
 * {@code mvn deploy} re-prompts.
 *
 * <p>Three key shapes (ADR-0004 / 03-data-model § 2.7 Grant.matchKey):
 * <ul>
 *   <li><b>Commands</b> ({@code run_command}): {@code run_command:<exe>[ <subcmd>]} — the
 *       executable basename, plus a subcommand for the small known-subcommand set
 *       ({@code git}, {@code mvn}, {@code npm}, {@code cargo}, {@code docker},
 *       {@code gradle}, {@code aws}). Subcommand granularity for those high-blast tools;
 *       executable granularity otherwise (the conservative bias the ADR pins).</li>
 *   <li><b>File writes</b> ({@code write_file}): {@code write:<subtree>} — the file's
 *       parent directory, resolved to a real path inside the workspace, so a later write
 *       anywhere under that subtree matches.</li>
 *   <li><b>Other Class X</b> (web lookup, sub-agent spawn, memory write): {@code <tool>} —
 *       the tool name (coarse).</li>
 * </ul>
 *
 * <p>Package-private; the gate is the public seam.
 */
final class MatchKey {

    /**
     * The high-blast executables that get subcommand-granularity match keys (ADR-0004).
     * Every other executable is keyed at executable granularity.
     */
    private static final Set<String> KNOWN_SUBCOMMAND_EXECUTABLES =
            Set.of("git", "mvn", "npm", "cargo", "docker", "gradle", "aws");

    /** Prefix of a command match key. */
    static final String COMMAND_PREFIX = RunCommandTool.NAME + ":";

    /** Prefix of a file-write match key. */
    static final String WRITE_PREFIX = "write:";

    private MatchKey() {
        // Non-instantiable.
    }

    /**
     * The normalized match key for a {@code run_command} invocation:
     * {@code run_command:<exe>[ <subcommand>]}.
     *
     * @param command the command string the model wants to run; must not be {@code null}.
     * @return the match key, or {@code null} when the command tokenizes to nothing (a
     *         blank command has no executable to key on).
     */
    static String forCommand(String command) {
        List<String> tokens = ShellTokenizer.tokenize(command);
        if (tokens.isEmpty()) {
            return null;
        }
        String exe = basename(tokens.get(0));
        if (exe.isEmpty()) {
            return null;
        }
        if (!KNOWN_SUBCOMMAND_EXECUTABLES.contains(exe)) {
            return COMMAND_PREFIX + exe;
        }
        String subcommand = firstNonFlag(tokens);
        return subcommand == null ? COMMAND_PREFIX + exe : COMMAND_PREFIX + exe + " " + subcommand;
    }

    /**
     * The match key for a {@code write_file} invocation: {@code write:<subtree>}, where the
     * subtree is the resolved parent directory of the target file inside the workspace.
     *
     * @param resolvedFile the workspace-confined, normalized target path; must not be
     *                     {@code null}.
     * @return the write match key.
     */
    static String forWrite(Path resolvedFile) {
        Path parent = resolvedFile.getParent();
        Path subtree = parent != null ? parent : resolvedFile;
        return WRITE_PREFIX + subtree;
    }

    /**
     * The coarse match key for any other Class-X tool (web lookup, sub-agent spawn, memory
     * write): the tool name itself.
     *
     * @param toolName the tool name; must not be {@code null}.
     * @return the tool-name key.
     */
    static String forTool(String toolName) {
        return toolName;
    }

    /**
     * Whether a stored write-grant key covers a later write to {@code resolvedFile}: the
     * file lies in the granted subtree or any descendant of it (ADR-0004: "later writes
     * anywhere under {@code src/} match; a write to {@code /etc/…} does not").
     *
     * @param grantKey     a stored grant key (any of the three shapes); must not be
     *                     {@code null}.
     * @param resolvedFile the target file of the later write; must not be {@code null}.
     * @return {@code true} if the grant is a write-grant whose subtree contains the file.
     */
    static boolean writeGrantCovers(String grantKey, Path resolvedFile) {
        if (!grantKey.startsWith(WRITE_PREFIX)) {
            return false;
        }
        Path grantedSubtree = Path.of(grantKey.substring(WRITE_PREFIX.length()));
        Path parent = resolvedFile.getParent();
        Path target = parent != null ? parent : resolvedFile;
        return target.startsWith(grantedSubtree);
    }

    private static String firstNonFlag(List<String> tokens) {
        for (int i = 1; i < tokens.size(); i++) {
            String token = tokens.get(i);
            if (!token.startsWith("-")) {
                return token.toLowerCase(Locale.ROOT);
            }
        }
        return null;
    }

    private static String basename(String token) {
        String t = token;
        int slash = Math.max(t.lastIndexOf('/'), t.lastIndexOf('\\'));
        if (slash >= 0 && slash < t.length() - 1) {
            t = t.substring(slash + 1);
        }
        return t.toLowerCase(Locale.ROOT);
    }
}
