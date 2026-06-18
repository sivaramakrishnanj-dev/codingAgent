package com.srk.codingagent.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link PermissionMode}.
 *
 * <p>Oracle: the resolved-config schema ({@code 06-formal/resolved-config.schema.json})
 * pins {@code permissionMode} to the enum
 * {@code [UNRESTRICTED, READ_ONLY, ASK_EVERY_TIME, ASK_ONCE_THEN_REMEMBER]}. The
 * test enforces that the Java enum's constant set is exactly that set — the schema
 * is the oracle, not the enum declaration.
 */
class PermissionModeTest {

    @Test
    @DisplayName("PermissionMode constants exactly match the schema enum (resolved-config schema)")
    void constants_matchSchemaEnum() {
        // Oracle: the resolved-config schema's permissionMode enum vocabulary.
        Set<String> expected = Set.of(
                "UNRESTRICTED", "READ_ONLY", "ASK_EVERY_TIME", "ASK_ONCE_THEN_REMEMBER");

        Set<String> actual = Arrays.stream(PermissionMode.values())
                .map(Enum::name)
                .collect(Collectors.toSet());

        assertEquals(expected, actual,
                "PermissionMode must declare exactly the schema's permissionMode enum members");
    }

    @Test
    @DisplayName("ASK_EVERY_TIME is a member (NFR-PERMISSION-DEFAULT)")
    void askEveryTime_isAMember() {
        // Oracle: NFR-PERMISSION-DEFAULT = ASK_EVERY_TIME must exist as a constant.
        boolean present = Arrays.stream(PermissionMode.values())
                .anyMatch(m -> m == PermissionMode.ASK_EVERY_TIME);
        assertTrue(present, "ASK_EVERY_TIME must exist (it is the configured default)");
    }
}
