package com.srk.codingagent.permission;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The RD-2 destructive-command denylist (ADR-0004, OQ-E part 2): a conservative pattern
 * test over the {@link ShellTokenizer tokenized} command. When any token sequence matches
 * a destructive pattern, the command is denylisted, and the permission gate (C8, T-0.7)
 * <em>always prompts</em> for it (denied outright in READ_ONLY) and never auto-approves it
 * from a remembered grant — even in UNRESTRICTED (AC-9.3, AC-10.4, INV-9).
 *
 * <p><b>Conservative bias (load-bearing, security).</b> A false positive (an unnecessary
 * confirmation prompt the user can simply approve) is safe; a false negative (a
 * destructive command sneaking past the denylist and auto-running) is a defect. Every
 * judgement call here biases toward flagging. The patterns are seeded from the ADR-0004
 * design-contract table; they are matched against the tokenized command (so quoting and
 * chaining cannot hide a keyword), case-insensitively (so {@code RM} matches {@code rm}),
 * and against the basename of an executable token (so {@code /usr/bin/rm} matches
 * {@code rm}).
 *
 * <p><b>No filesystem access.</b> Several ADR rows ("{@code mv}/{@code cp} whose
 * destination exists", "{@code rm} of a non-empty target") would need a filesystem probe
 * to decide precisely. The gate decides before execution and must not depend on
 * mutable-FS state, so this matcher takes the conservative simplification: a
 * {@code mv}/{@code cp} with two-or-more non-flag path operands (a real move/copy, which
 * <em>could</em> overwrite) is flagged, and any {@code rm} carrying a recursive/forced
 * flag is flagged. The over-flagging is intentional and recorded as a stated assumption.
 *
 * <p>Package-private; the gate is the public seam.
 */
final class DestructiveCommandDenylist {

    /**
     * Executable basenames that are destructive on their own (any invocation prompts):
     * raw-disk / privilege / directory-delete / destructive-resize / kill-by-name.
     */
    private static final List<String> ALWAYS_DESTRUCTIVE_EXECUTABLES = List.of(
            "rmdir",    // directory delete
            "dd",       // raw disk write
            "truncate", // destructive resize
            "sudo",     // privilege escalation -> always confirm
            "killall"); // killing unrelated processes by name

    /** Flags that turn an {@code rm} into a recursive/forced delete. */
    private static final List<String> RM_DESTRUCTIVE_FLAG_LETTERS = List.of("r", "f", "d");

    private DestructiveCommandDenylist() {
        // Non-instantiable.
    }

    /**
     * Whether the command matches any destructive pattern.
     *
     * @param command the raw command string; must not be {@code null}.
     * @return {@code true} if the command is denylisted (the gate must always prompt and
     *         never remember it).
     */
    static boolean isDestructive(String command) {
        List<String> tokens = ShellTokenizer.tokenize(command);
        if (tokens.isEmpty()) {
            return false;
        }
        if (containsForkBomb(command)) {
            return true;
        }
        if (containsPipeToShell(tokens)) {
            return true;
        }
        if (containsRedirectOverwrite(tokens)) {
            return true;
        }
        // A command can chain several sub-commands (rm ... ; mv ...). Evaluate each
        // simple-command segment split on chaining/redirect operators, plus the whole
        // token stream, so a destructive verb anywhere in a compound is caught.
        for (List<String> segment : splitSegments(tokens)) {
            if (segmentIsDestructive(segment)) {
                return true;
            }
        }
        return false;
    }

    private static boolean segmentIsDestructive(List<String> segment) {
        if (segment.isEmpty()) {
            return false;
        }
        String exe = basename(segment.get(0));
        if (ALWAYS_DESTRUCTIVE_EXECUTABLES.contains(exe)) {
            return true;
        }
        return switch (exe) {
            case "rm" -> rmIsDestructive(segment);
            case "mv", "cp" -> moveOrCopyOverwrites(segment);
            case "git" -> gitIsDestructive(segment);
            case "chmod", "chown" -> recursivePermissionChange(segment);
            case "kill" -> killUsesForceSignal(segment);
            default -> false;
        };
    }

    /** {@code rm} with -r/-f/-rf (recursive/forced delete) — ADR-0004 row 1. */
    private static boolean rmIsDestructive(List<String> segment) {
        for (int i = 1; i < segment.size(); i++) {
            String token = segment.get(i);
            if (isShortFlagWith(token, RM_DESTRUCTIVE_FLAG_LETTERS)
                    || isLongFlag(token, "recursive", "force")) {
                return true;
            }
        }
        // Conservative: a bare `rm <target>` (no recursive/forced flag) is NOT auto-flagged
        // — the ADR row is "-r/-f/-rf or rm of a non-empty target", and non-empty-ness needs
        // an FS probe the gate avoids. A plain single-file rm still gates normally as Class X.
        return false;
    }

    /**
     * {@code mv}/{@code cp} with two-or-more non-flag operands — a real move/copy that
     * could overwrite an existing destination (ADR-0004 "destination exists"). The gate
     * cannot probe the FS, so any genuine two-operand move/copy is flagged conservatively.
     */
    private static boolean moveOrCopyOverwrites(List<String> segment) {
        int operands = 0;
        for (int i = 1; i < segment.size(); i++) {
            if (!isFlag(segment.get(i))) {
                operands++;
            }
        }
        return operands >= 2;
    }

    /** Destructive git subcommands (ADR-0004 rows 7–10). */
    private static boolean gitIsDestructive(List<String> segment) {
        String subcommand = firstNonFlag(segment, 1);
        if (subcommand == null) {
            return false;
        }
        return switch (subcommand) {
            case "push" -> hasFlag(segment, "f", "force") || hasLongFlag(segment, "force-with-lease");
            case "reset" -> hasLongFlag(segment, "hard");
            case "clean" -> hasFlag(segment, "f", "force") || hasShortFlagLetter(segment, 'f');
            case "checkout", "restore" -> true; // broad working-tree discard -> conservative flag
            default -> false;
        };
    }

    /** {@code chmod}/{@code chown -R} (recursive permission blast radius) — ADR-0004 row 11. */
    private static boolean recursivePermissionChange(List<String> segment) {
        return hasFlag(segment, "R", "recursive") || hasShortFlagLetter(segment, 'R');
    }

    /** {@code kill -9} (forced kill of a possibly-unrelated PID) — ADR-0004 row 15. */
    private static boolean killUsesForceSignal(List<String> segment) {
        for (int i = 1; i < segment.size(); i++) {
            String token = segment.get(i);
            if (token.equals("-9") || token.equals("-KILL") || token.equals("-SIGKILL")
                    || token.equalsIgnoreCase("-s") && i + 1 < segment.size()
                            && isKillSignal(segment.get(i + 1))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isKillSignal(String value) {
        return value.equals("9") || value.equalsIgnoreCase("KILL") || value.equalsIgnoreCase("SIGKILL");
    }

    /** {@code curl}/{@code wget ... | sh} (pipe-to-shell, RCE) — ADR-0004 row 14. */
    private static boolean containsPipeToShell(List<String> tokens) {
        boolean sawFetcher = false;
        boolean sawPipe = false;
        for (String token : tokens) {
            if (isPipeOperator(token)) {
                sawPipe = true;
                continue;
            }
            String exe = basename(token);
            if (exe.equals("curl") || exe.equals("wget")) {
                sawFetcher = true;
            }
            if (sawPipe && (exe.equals("sh") || exe.equals("bash") || exe.equals("zsh")
                    || exe.equals("dash") || exe.equals("ksh"))) {
                return sawFetcher;
            }
        }
        return false;
    }

    /** Output redirect over a file ({@code > } / {@code >|}) — ADR-0004 row 6. */
    private static boolean containsRedirectOverwrite(List<String> tokens) {
        for (String token : tokens) {
            // A truncating redirect is a single '>' or a '>|' form. An appending '>>'
            // does not overwrite, so it is not flagged here.
            if (token.equals(">") || token.equals(">|")) {
                return true;
            }
        }
        return false;
    }

    /** Fork-bomb shapes such as {@code :(){ :|:& };:} — ADR-0004 row 13. */
    private static boolean containsForkBomb(String command) {
        String compact = command.replaceAll("\\s+", "");
        // The classic bash fork bomb and near-variants: a self-referential function that
        // pipes into itself and backgrounds. Match the structural core conservatively.
        return compact.contains(":(){:|:&};:")
                || compact.contains(":(){:|:&}:")
                || compact.matches(".*\\w+\\(\\)\\{\\w+\\|\\w+&\\};\\w+.*");
    }

    private static List<List<String>> splitSegments(List<String> tokens) {
        List<List<String>> segments = new ArrayList<>();
        List<String> current = new ArrayList<>();
        for (String token : tokens) {
            if (isSegmentSeparator(token)) {
                segments.add(List.copyOf(current));
                current.clear();
            } else {
                current.add(token);
            }
        }
        segments.add(List.copyOf(current));
        return segments;
    }

    private static boolean isSegmentSeparator(String token) {
        return token.equals(";") || token.equals("&") || token.equals("&&")
                || token.equals("|") || token.equals("||") || isPipeOperator(token)
                || token.equals(">") || token.equals(">>") || token.equals(">|")
                || token.equals("<");
    }

    private static boolean isPipeOperator(String token) {
        return token.equals("|") || token.equals("||");
    }

    private static String firstNonFlag(List<String> segment, int from) {
        for (int i = from; i < segment.size(); i++) {
            if (!isFlag(segment.get(i))) {
                return segment.get(i).toLowerCase(Locale.ROOT);
            }
        }
        return null;
    }

    private static boolean hasFlag(List<String> segment, String shortLetter, String longName) {
        return hasShortFlagLetter(segment, shortLetter.charAt(0)) || hasLongFlag(segment, longName);
    }

    private static boolean hasShortFlagLetter(List<String> segment, char letter) {
        for (int i = 1; i < segment.size(); i++) {
            String token = segment.get(i);
            if (isShortFlagWith(token, List.of(String.valueOf(Character.toLowerCase(letter))))) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasLongFlag(List<String> segment, String name) {
        for (int i = 1; i < segment.size(); i++) {
            if (isLongFlag(segment.get(i), name)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether {@code token} is a clustered short flag ({@code -rf}, {@code -fdx}) that
     * contains any of the given flag letters (case-insensitive), and not a long flag.
     */
    private static boolean isShortFlagWith(String token, List<String> letters) {
        if (token.length() < 2 || token.charAt(0) != '-' || token.charAt(1) == '-') {
            return false;
        }
        String body = token.substring(1).toLowerCase(Locale.ROOT);
        for (String letter : letters) {
            if (body.indexOf(letter.toLowerCase(Locale.ROOT)) >= 0) {
                return true;
            }
        }
        return false;
    }

    private static boolean isLongFlag(String token, String... names) {
        if (!token.startsWith("--")) {
            return false;
        }
        String body = token.substring(2).toLowerCase(Locale.ROOT);
        for (String name : names) {
            if (body.equals(name.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isFlag(String token) {
        return token.startsWith("-");
    }

    /**
     * The basename of an executable token, lower-cased: {@code /usr/bin/RM} → {@code rm}.
     * Case folding makes {@code RM} match {@code rm} (the ADR "RM casing" adversarial case).
     */
    private static String basename(String token) {
        String t = token;
        int slash = Math.max(t.lastIndexOf('/'), t.lastIndexOf('\\'));
        if (slash >= 0 && slash < t.length() - 1) {
            t = t.substring(slash + 1);
        }
        return t.toLowerCase(Locale.ROOT);
    }
}
