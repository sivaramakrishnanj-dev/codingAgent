package com.srk.codingagent.cli;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the CLI entry point {@link Main}.
 *
 * <p>Oracles trace to T-0.1 spec symbols:
 * <ul>
 *   <li>exit-code contract "{@code 0} success" &rarr; an informational launch
 *       ({@code --help} / {@code --version}) returns {@code 0}.</li>
 *   <li>NFR-PLAT-JAVA "Java 21 (LTS)" &rarr; compiled bytecode targets the
 *       Java 21 class-file major version (65).</li>
 * </ul>
 *
 * <p>The no-argument shape now enters the real interactive REPL (T-1.1), whose
 * production composition resolves live SigV4 credentials and builds a Bedrock client
 * — not unit-testable without a live AWS call, exactly like the one-shot composition.
 * The interactive read-eval logic is covered by {@code ReplRunnerTest} and the inline
 * approval by {@code InteractiveApproverTest}; the credentials-needed composition is
 * confirmed by the real-Bedrock smoke test at the G1 gate. These {@code Main} tests
 * therefore exercise only the shapes {@code run} resolves <em>before</em> any model
 * call (the INFO and fail-fast paths).
 *
 * <p>{@link Main#main(String[])} is intentionally not exercised directly: it calls
 * {@link System#exit(int)}, which would terminate the test JVM. Its launch logic
 * is the {@link Main#run(String[])} seam, which these tests cover.
 */
class MainTest {

    /**
     * Java 21 class-file major version, per the JVM specification (Java SE 21 =
     * class-file format 65.0). Oracle for NFR-PLAT-JAVA ("Java 21 (LTS)").
     */
    private static final int JAVA_21_CLASSFILE_MAJOR_VERSION = 65;

    @Test
    @DisplayName("run() returns the success exit code on an informational launch "
            + "(exit-code contract: 0 = success)")
    void run_informationalLaunch_returnsSuccessExitCode() {
        // Expected value 0 is pinned by the CLI exit contract ("0 success"), NOT by Main's
        // observed return. --version is the simplest shape run() resolves to a clean 0
        // before any model call (the no-arg shape now enters the credentials-needed REPL).
        int exitCode = Main.run(new String[] {"--version"});

        assertEquals(0, exitCode,
                "An informational launch must exit 0 (exit-code contract: 0 = success)");
    }

    @Test
    @DisplayName("run() exit code matches the named SUCCESS_EXIT_CODE constant "
            + "(exit-code contract: 0 = success)")
    void run_returnValue_equalsSuccessConstant() {
        assertEquals(Main.SUCCESS_EXIT_CODE, Main.run(new String[] {"--version"}),
                "run() must return the documented success code on a clean informational launch");
    }

    @Test
    @DisplayName("SUCCESS_EXIT_CODE is 0 (exit-code contract: 0 = success)")
    void successExitCode_isZero() {
        // Pins the named constant directly to the spec symbol "0 success".
        assertEquals(0, Main.SUCCESS_EXIT_CODE,
                "Success exit code must be 0 per the CLI exit contract");
    }

    @Test
    @DisplayName("run() launches without throwing for a typical argument "
            + "(exit-code contract: 0 = success)")
    void run_withTypicalArgument_doesNotThrow() {
        // A representative INFO launch completes the success path without error.
        assertDoesNotThrow(() -> Main.run(new String[] {"--help"}),
                "Launch must not throw for a representative argument");
    }

    @Test
    @DisplayName("Main compiles to Java 21 bytecode (NFR-PLAT-JAVA: Java 21 LTS)")
    void mainClass_targetsJava21Bytecode() throws IOException {
        // Oracle: NFR-PLAT-JAVA pins Java 21; the JVM spec maps Java SE 21 to
        // class-file major version 65. Reads the major-version field from the
        // compiled Main.class to confirm the toolchain emitted Java-21 bytecode.
        int majorVersion = readClassFileMajorVersion(Main.class);

        assertEquals(JAVA_21_CLASSFILE_MAJOR_VERSION, majorVersion,
                "Compiled bytecode must target Java 21 (class-file major version 65)");
    }

    /**
     * Reads the class-file major version from a compiled class's {@code .class}
     * resource. Class-file layout (JVMS s4.1): bytes 0-3 magic (0xCAFEBABE),
     * bytes 4-5 minor version, bytes 6-7 major version.
     */
    private static int readClassFileMajorVersion(Class<?> type) throws IOException {
        String resource = type.getSimpleName() + ".class";
        try (InputStream in = type.getResourceAsStream(resource);
                DataInputStream data = new DataInputStream(in)) {
            int magic = data.readInt();
            if (magic != 0xCAFEBABE) {
                throw new IOException("Not a class file: bad magic " + Integer.toHexString(magic));
            }
            data.readUnsignedShort(); // minor version (ignored)
            return data.readUnsignedShort(); // major version
        }
    }
}
