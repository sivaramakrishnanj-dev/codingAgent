package com.srk.codingagent.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.srk.codingagent.config.PermissionMode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the {@link EventPayload} records' schema-pinned validation
 * (event.schema.json {@code $defs}) and their {@link EventPayload#type()} mappings.
 * Each payload is self-validating so an invalid payload cannot be appended; the
 * oracle for each constraint is the corresponding schema {@code $def}.
 */
class EventPayloadValidationTest {

    @Test
    @DisplayName("each payload reports the EventType it is the body of (§ 2.2 type-from-payload)")
    void payloadTypes_match() {
        // Oracle: 03-data-model.md § 2.2 / § 3 taxonomy — each payload kind maps to its
        // EventType.
        assertEquals(EventType.SESSION_START, new SessionStartPayload(
                SessionMode.GREENFIELD, "r", "m", PermissionMode.UNRESTRICTED).type());
        assertEquals(EventType.USER_MESSAGE, new UserMessagePayload(List.of()).type());
        assertEquals(EventType.MODEL_RESPONSE,
                new ModelResponsePayload(StopReason.END_TURN, List.of()).type());
        assertEquals(EventType.MODEL_USAGE, ModelUsagePayload.of(0, 0).type());
        assertEquals(EventType.TOOL_USE, new ToolUsePayload("tu", "n", Map.of()).type());
        assertEquals(EventType.PERMISSION_DECISION, new PermissionDecisionPayload(
                "tu", OperationClass.READ, PermissionMode.READ_ONLY,
                PermissionDecisionOutcome.DENY, null).type());
        assertEquals(EventType.TOOL_RESULT,
                ToolResultPayload.of("tu", ToolResultStatus.OK, null).type());
        assertEquals(EventType.OUTCOME, new OutcomePayload("t", true, 0).type());
    }

    @Test
    @DisplayName("SESSION_START rejects a blank repoKey and modelId (schema string fields)")
    void sessionStart_blankFields_rejected() {
        // Oracle: event.schema.json $defs.sessionStart — repoKey/modelId are strings;
        // the model treats them as required non-blank session identity.
        assertThrows(IllegalArgumentException.class, () -> new SessionStartPayload(
                SessionMode.GREENFIELD, " ", "m", PermissionMode.UNRESTRICTED));
        assertThrows(IllegalArgumentException.class, () -> new SessionStartPayload(
                SessionMode.GREENFIELD, "r", "", PermissionMode.UNRESTRICTED));
    }

    @Test
    @DisplayName("MODEL_USAGE rejects negative token counts (schema minimum 0, AC-13.2)")
    void modelUsage_negativeTokens_rejected() {
        // Oracle: event.schema.json $defs.modelUsage — inputTokens/outputTokens minimum
        // 0; the optional cache fields, when present, are also minimum 0.
        assertThrows(IllegalArgumentException.class, () -> new ModelUsagePayload(-1, 0, null, null));
        assertThrows(IllegalArgumentException.class, () -> new ModelUsagePayload(0, -1, null, null));
        assertThrows(IllegalArgumentException.class, () -> new ModelUsagePayload(0, 0, -1, null));
        assertThrows(IllegalArgumentException.class, () -> new ModelUsagePayload(0, 0, null, -1));
    }

    @Test
    @DisplayName("MODEL_USAGE accepts present cache fields (schema optional cache tokens)")
    void modelUsage_withCacheFields_accepted() {
        // Oracle: event.schema.json $defs.modelUsage — cacheRead/WriteInputTokens are
        // optional integers >= 0.
        ModelUsagePayload usage = new ModelUsagePayload(100, 50, 10, 5);

        assertEquals(10, usage.cacheReadInputTokens());
        assertEquals(5, usage.cacheWriteInputTokens());
    }

    @Test
    @DisplayName("TOOL_USE rejects a blank toolUseId and name (schema minLength 1)")
    void toolUse_blankFields_rejected() {
        // Oracle: event.schema.json $defs.toolUse — toolUseId/name minLength 1.
        assertThrows(IllegalArgumentException.class, () -> new ToolUsePayload("", "n", Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new ToolUsePayload("tu", " ", Map.of()));
    }

    @Test
    @DisplayName("OUTCOME rejects a negative iteration count (schema minimum 0)")
    void outcome_negativeIterations_rejected() {
        // Oracle: event.schema.json $defs.outcome — iterations minimum 0.
        assertThrows(IllegalArgumentException.class, () -> new OutcomePayload("t", true, -1));
    }

    @Test
    @DisplayName("OUTCOME rejects a blank taskRef (schema requires a task reference)")
    void outcome_blankTaskRef_rejected() {
        // Oracle: event.schema.json $defs.outcome — taskRef is a required string.
        assertThrows(IllegalArgumentException.class, () -> new OutcomePayload(" ", true, 0));
    }

    @Test
    @DisplayName("TOOL_RESULT rejects a blank toolUseId (schema minLength 1)")
    void toolResult_blankId_rejected() {
        // Oracle: event.schema.json $defs.toolResult — toolUseId minLength 1.
        assertThrows(IllegalArgumentException.class,
                () -> ToolResultPayload.of("", ToolResultStatus.OK, null));
    }

    @Test
    @DisplayName("PERMISSION_DECISION rejects a blank toolUseId (schema minLength 1)")
    void permissionDecision_blankId_rejected() {
        // Oracle: event.schema.json $defs.permissionDecision — toolUseId minLength 1.
        assertThrows(IllegalArgumentException.class, () -> new PermissionDecisionPayload(
                " ", OperationClass.READ, PermissionMode.READ_ONLY,
                PermissionDecisionOutcome.APPROVE, null));
    }

    @Test
    @DisplayName("the permission-decision and tool-result enums expose their lowercase wire values (schema enums)")
    void wireValues_lowercase() {
        // Oracle: event.schema.json — decision enum {approve, deny}; status enum
        // {ok, error, denied}. The enums' wire values are exactly those lowercase tokens.
        assertEquals("approve", PermissionDecisionOutcome.APPROVE.wireValue());
        assertEquals("deny", PermissionDecisionOutcome.DENY.wireValue());
        assertEquals("ok", ToolResultStatus.OK.wireValue());
        assertEquals("error", ToolResultStatus.ERROR.wireValue());
        assertEquals("denied", ToolResultStatus.DENIED.wireValue());
    }
}
