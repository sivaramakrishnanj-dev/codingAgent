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
 *   <li>exit-code contract "{@code 0} success" + Verify "empty CLI launches"
 *       &rarr; {@link Main#run(String[])} returns {@code 0}.</li>
 *   <li>NFR-PLAT-JAVA "Java 21 (LTS)" &rarr; compiled bytecode targets the
 *       Java 21 class-file major version (65).</li>
 * </ul>
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
    @DisplayName("run() returns the success exit code on a clean launch "
            + "(exit-code contract: 0 = success; Verify: empty CLI launches)")
    void run_cleanLaunch_returnsSuccessExitCode() {
        // Expected value 0 is pinned by the CLI exit contract ("0 success") and the
        // T-0.1 Verify column ("empty CLI launches"), NOT by Main's observed return.
        int exitCode = Main.run(new String[] {});

        assertEquals(0, exitCode,
                "A clean launch must exit 0 (exit-code contract: 0 = success)");
    }

    @Test
    @DisplayName("run() exit code matches the named SUCCESS_EXIT_CODE constant "
            + "(exit-code contract: 0 = success)")
    void run_returnValue_equalsSuccessConstant() {
        assertEquals(Main.SUCCESS_EXIT_CODE, Main.run(new String[] {}),
                "run() must return the documented success code");
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
            + "(Verify: empty CLI launches)")
    void run_withTypicalArgument_doesNotThrow() {
        // "Launches" (Verify column) = completes the success path without error.
        assertDoesNotThrow(() -> Main.run(new String[] {"--help"}),
                "Launch must not throw for a representative argument");
    }

    @Test
    @DisplayName("run() launches and returns success for a null argument array "
            + "(Verify: empty CLI launches; null-arg contract)")
    void run_withNullArgs_returnsSuccessAndDoesNotThrow() {
        // run()'s Javadoc admits a stable main-style signature; the body guards
        // against null. "Launches" still means the success path (exit 0).
        int exitCode = assertDoesNotThrow(() -> Main.run(null),
                "Launch must not throw on a null argument array");

        assertEquals(0, exitCode,
                "A clean launch must exit 0 even with null args "
                        + "(exit-code contract: 0 = success)");
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
