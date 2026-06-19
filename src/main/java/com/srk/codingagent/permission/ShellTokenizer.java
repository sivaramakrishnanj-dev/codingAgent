package com.srk.codingagent.permission;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits a command string into shell words, honoring single and double quotes and
 * backslash escapes (ADR-0004: "Tokenize the command (shell-word split, honoring
 * quotes)"). This is the load-bearing front end the agent's permission gate (C8, T-0.7)
 * runs <em>before</em> the destructive-command denylist test and before RD-1 grant-key
 * normalization — both consume the tokens this produces, so unsound tokenization would
 * let a destructive command slip through the gate.
 *
 * <p><b>Conservative bias.</b> The gate is a security chokepoint: a false positive (an
 * extra confirmation prompt) is safe; a false negative (a destructive command sneaking
 * past the denylist) is a defect. The tokenizer therefore keeps shell metacharacters that
 * separate or chain commands ({@code ; & | > < ` $( )}) as their own tokens rather than
 * folding them into adjacent words, so the denylist can see — for example — the {@code |}
 * in {@code curl ... | sh} and the {@code ;} in a {@code rm -rf /; echo done} compound.
 * It does <em>not</em> attempt a full POSIX shell parse (that is neither needed nor safe
 * to rely on for an allow decision); it tokenizes enough that a conservative pattern test
 * over the tokens cannot be defeated by ordinary quoting or chaining.
 *
 * <p>Package-private; not part of the permission public API. The gate, the denylist, and
 * the match-key normalizer use it internally.
 */
final class ShellTokenizer {

    /** Shell metacharacters that always stand as their own single-character token. */
    private static final String SINGLE_CHAR_OPERATORS = "<>;&|()";

    private ShellTokenizer() {
        // Non-instantiable.
    }

    /**
     * Tokenizes a command string into shell words.
     *
     * <p>Quoting and escaping rules (a conservative subset of POSIX sufficient for a
     * security pattern test):
     * <ul>
     *   <li>Whitespace separates words (outside quotes).</li>
     *   <li>Single quotes group a literal run (no escapes inside).</li>
     *   <li>Double quotes group a run; a backslash escapes the next character inside.</li>
     *   <li>An unquoted backslash escapes the next character (it joins to the current
     *       word).</li>
     *   <li>The operators {@code < > ; & | ( )} are emitted as their own tokens when
     *       unquoted, even when adjacent to a word, so chained/redirected commands are
     *       visible to the denylist. Adjacent operator characters (e.g. {@code &&},
     *       {@code ||}, {@code >>}) coalesce into one operator token.</li>
     * </ul>
     *
     * @param command the raw command string; must not be {@code null}.
     * @return the tokens in order; never {@code null}. A blank command yields an empty
     *         list.
     */
    static List<String> tokenize(String command) {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean wordStarted = false;
        int i = 0;
        int n = command.length();
        while (i < n) {
            char c = command.charAt(i);
            if (c == '\'') {
                wordStarted = true;
                i = consumeSingleQuoted(command, i + 1, current);
            } else if (c == '"') {
                wordStarted = true;
                i = consumeDoubleQuoted(command, i + 1, current);
            } else if (c == '\\') {
                wordStarted = true;
                if (i + 1 < n) {
                    current.append(command.charAt(i + 1));
                    i += 2;
                } else {
                    // Trailing backslash with nothing to escape: keep it literally.
                    current.append(c);
                    i++;
                }
            } else if (Character.isWhitespace(c)) {
                wordStarted = flushWord(tokens, current, wordStarted);
                i++;
            } else if (isOperatorChar(c)) {
                wordStarted = flushWord(tokens, current, wordStarted);
                i = consumeOperatorRun(command, i, tokens);
            } else {
                wordStarted = true;
                current.append(c);
                i++;
            }
        }
        flushWord(tokens, current, wordStarted);
        return tokens;
    }

    private static int consumeSingleQuoted(String command, int start, StringBuilder current) {
        int i = start;
        while (i < command.length() && command.charAt(i) != '\'') {
            current.append(command.charAt(i));
            i++;
        }
        // Skip the closing quote if present; an unterminated quote ends the word.
        return i < command.length() ? i + 1 : i;
    }

    private static int consumeDoubleQuoted(String command, int start, StringBuilder current) {
        int i = start;
        while (i < command.length() && command.charAt(i) != '"') {
            char c = command.charAt(i);
            if (c == '\\' && i + 1 < command.length()) {
                current.append(command.charAt(i + 1));
                i += 2;
            } else {
                current.append(c);
                i++;
            }
        }
        return i < command.length() ? i + 1 : i;
    }

    private static int consumeOperatorRun(String command, int start, List<String> tokens) {
        char first = command.charAt(start);
        int i = start + 1;
        // Coalesce a run of the SAME operator char (&&, ||, >>) into one token; distinct
        // adjacent operators stay separate so each is visible to the denylist.
        while (i < command.length() && command.charAt(i) == first) {
            i++;
        }
        tokens.add(command.substring(start, i));
        return i;
    }

    private static boolean isOperatorChar(char c) {
        return SINGLE_CHAR_OPERATORS.indexOf(c) >= 0;
    }

    /**
     * Emits the accumulated word (if any started, including an explicitly empty quoted
     * word) and clears the buffer.
     *
     * @return {@code false} — the next word has not started yet.
     */
    private static boolean flushWord(List<String> tokens, StringBuilder current, boolean wordStarted) {
        if (wordStarted) {
            tokens.add(current.toString());
            current.setLength(0);
        }
        return false;
    }
}
