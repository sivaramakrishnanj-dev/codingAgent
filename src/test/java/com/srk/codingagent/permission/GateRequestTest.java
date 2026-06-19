package com.srk.codingagent.permission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.srk.codingagent.persistence.OperationClass;
import com.srk.codingagent.tool.RunCommandTool;
import com.srk.codingagent.tool.WriteFileTool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link GateRequest} — the operation presentation the gate evaluates and prompts
 * on (AC-10.1: present the exact operation — command string, or file path + change summary).
 *
 * <p>Oracle: AC-10.1 (exact operation presentation) and the dependency contract that
 * {@code run_command}/{@code write_file} are Class X (04-apis § 3, reused from the tool
 * layer).
 */
class GateRequestTest {

    @Test
    @DisplayName("AC-10.1: a command request presents the full command string")
    void commandRequestPresentsCommand() {
        GateRequest request = GateRequest.forCommand("tu-1", "rm -rf build");
        assertEquals(RunCommandTool.NAME, request.toolName());
        assertEquals(OperationClass.SIDE_EFFECTING, request.operationClass());
        assertEquals("rm -rf build", request.command());
        assertTrue(request.presentation().contains("rm -rf build"),
                "the prompt presents the exact command string (AC-10.1)");
    }

    @Test
    @DisplayName("AC-10.1: a write request presents the file path and change summary")
    void writeRequestPresentsPathAndSummary() {
        GateRequest request = GateRequest.forWrite("tu-2", "/ws/src/Foo.java", "+10 lines");
        assertEquals(WriteFileTool.NAME, request.toolName());
        assertEquals(OperationClass.SIDE_EFFECTING, request.operationClass());
        assertEquals("/ws/src/Foo.java", request.filePath());
        assertTrue(request.presentation().contains("/ws/src/Foo.java"),
                "the prompt presents the file path (AC-10.1)");
        assertTrue(request.presentation().contains("+10 lines"),
                "the prompt presents the change summary (AC-10.1)");
    }

    @Test
    @DisplayName("AC-10.1: a generic tool request presents the tool name with its declared class")
    void toolRequestPresentsToolName() {
        GateRequest request = GateRequest.forTool("tu-3", "read_file", OperationClass.READ);
        assertEquals("read_file", request.toolName());
        assertEquals(OperationClass.READ, request.operationClass());
        assertNull(request.command(), "a generic tool request carries no command");
        assertNull(request.filePath(), "a generic tool request carries no file path");
        assertEquals("read_file", request.presentation());
    }

    @Test
    @DisplayName("GateRequest rejects a blank toolUseId / toolName and a null operationClass")
    void rejectsInvalidFields() {
        assertThrows(IllegalArgumentException.class,
                () -> GateRequest.forCommand(" ", "mvn test"), "blank toolUseId rejected");
        assertThrows(IllegalArgumentException.class,
                () -> GateRequest.forCommand("tu-1", " "), "blank command rejected");
        assertThrows(IllegalArgumentException.class,
                () -> GateRequest.forWrite("tu-1", " ", "summary"), "blank file path rejected");
        assertThrows(NullPointerException.class,
                () -> GateRequest.forTool("tu-1", "x", null), "null operationClass rejected");
    }
}
