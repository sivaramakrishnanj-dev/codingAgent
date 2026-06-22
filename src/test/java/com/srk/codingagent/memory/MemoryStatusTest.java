package com.srk.codingagent.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link MemoryStatus}'s wire-value bridge (component C16, ADR-0007). The enum
 * is the SUT. Oracle: {@code memory-entry.schema.json} pins the {@code status} enum as
 * <em>lowercase</em> {@code active}/{@code retired}, while the Java constants are uppercase;
 * {@link MemoryStatus#wireValue()} / {@link MemoryStatus#fromWire(String)} must bridge the two
 * faithfully so emitted front-matter validates (CT-SCH-11).
 */
class MemoryStatusTest {

    @Test
    @DisplayName("schema casing: wireValue is the lowercase schema token")
    void wireValueIsLowercase() {
        // Oracle: schema status enum = {active, retired} (lowercase).
        assertEquals("active", MemoryStatus.ACTIVE.wireValue());
        assertEquals("retired", MemoryStatus.RETIRED.wireValue());
    }

    @Test
    @DisplayName("fromWire parses the schema's lowercase token back to the enum")
    void fromWireParsesLowercase() {
        assertEquals(MemoryStatus.ACTIVE, MemoryStatus.fromWire("active"));
        assertEquals(MemoryStatus.RETIRED, MemoryStatus.fromWire("retired"));
    }

    @Test
    @DisplayName("wireValue and fromWire round-trip")
    void roundTrips() {
        for (MemoryStatus status : MemoryStatus.values()) {
            assertEquals(status, MemoryStatus.fromWire(status.wireValue()),
                    status + " round-trips through wireValue / fromWire");
        }
    }

    @Test
    @DisplayName("fromWire rejects an unknown token")
    void fromWireRejectsUnknown() {
        assertThrows(IllegalArgumentException.class, () -> MemoryStatus.fromWire("archived"));
    }
}
