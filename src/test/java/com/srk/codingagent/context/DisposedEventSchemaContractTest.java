package com.srk.codingagent.context;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.networknt.schema.InputFormat;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import com.networknt.schema.resource.ClasspathSchemaLoader;
import com.srk.codingagent.persistence.Event;
import com.srk.codingagent.persistence.EventCodec;
import com.srk.codingagent.persistence.ToolResultPayload;
import com.srk.codingagent.persistence.ToolResultStatus;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * CT-SCH-1 extension for output disposal (T-1.5): a TOOL_RESULT event whose body carries the
 * disposal fields ({@code truncated} / {@code fullRef}) must still validate against the formal
 * {@code event.schema.json} (the same schema CT-SCH-1 validates fixture lines against). This is
 * the D2-class guard the task calls out: "a new truncated/fullRef-bearing event must still
 * validate". A disposal event that the schema rejects would be a live-only defect &mdash; the
 * log line would be written but fail the contract that {@code 06-formal} pins (AC-13.1/13.3).
 *
 * <p>The events are encoded with the real {@link EventCodec} (the same encoder the
 * {@link com.srk.codingagent.persistence.EventLog} uses), so what is validated is exactly what
 * the loop would write to disk, not a hand-built JSON string.
 */
class DisposedEventSchemaContractTest {

    private static final String EVENT_SCHEMA_ID = "https://codingagent.srk/schemas/event.schema.json";
    private static final String CONTENT_BLOCK_SCHEMA_ID =
            "https://codingagent.srk/schemas/content-block.schema.json";
    private static final String TS = "2026-06-17T09:00:00Z";

    private static JsonSchema eventSchema;
    private final EventCodec codec = new EventCodec();

    @BeforeAll
    static void loadSchema() {
        JsonSchemaFactory factory = JsonSchemaFactory.getInstance(
                SpecVersion.VersionFlag.V202012,
                builder -> builder
                        .schemaLoaders(loaders -> loaders.add(new ClasspathSchemaLoader()))
                        .schemaMappers(mappers -> mappers.mappings(Map.of(
                                EVENT_SCHEMA_ID, "classpath:schemas/event.schema.json",
                                CONTENT_BLOCK_SCHEMA_ID, "classpath:schemas/content-block.schema.json"))));
        eventSchema = factory.getSchema(SchemaLocation.of(EVENT_SCHEMA_ID));
    }

    private Set<ValidationMessage> validate(Event event) {
        return eventSchema.validate(codec.encode(event), InputFormat.JSON);
    }

    @Test
    @DisplayName("CT-SCH-1 (+): the FULL persisted TOOL_RESULT event (no truncated/fullRef) validates (AC-19.2)")
    void fullPersistedToolResult_validates() {
        // Oracle: AC-19.2 — the loop persists the FULL output to the log as a TOOL_RESULT with
        // the un-truncated content. That event (truncated/fullRef omitted) must validate against
        // event.schema.json, just as the fixture's TOOL_RESULT does.
        Event full = new Event(5, TS,
                ToolResultPayload.of("tu_1", ToolResultStatus.OK, "x".repeat(40_000)));

        assertTrue(validate(full).isEmpty(),
                "the full persisted TOOL_RESULT event must validate against the schema (AC-19.2)");
    }

    @Test
    @DisplayName("CT-SCH-1 (+): a TOOL_RESULT event carrying truncated=true + fullRef validates (D2 guard)")
    void truncatedFullRefBearingToolResult_validates() {
        // Oracle: event.schema.json $defs.toolResult permits truncated (boolean) and fullRef
        // (string). A disposal event that sets them must still validate — the D2-class guard
        // that a truncated/fullRef-bearing event is schema-valid (AC-13.1/13.3). This covers the
        // representation in which a persisted event marks itself truncated and points elsewhere,
        // so the schema contract holds whichever disposal representation a later task uses.
        Event truncated = new Event(6, TS, new ToolResultPayload(
                "tu_1", ToolResultStatus.OK, "head ... tail", Boolean.TRUE, FullRef.forSeq(5)));

        assertTrue(validate(truncated).isEmpty(),
                "a truncated/fullRef-bearing TOOL_RESULT event must still validate (CT-SCH-1, D2 guard)");
    }

    @Test
    @DisplayName("CT-SCH-1 (+): a TOOL_RESULT event whose result is a reduced head+tail string validates")
    void reducedStringResult_validates() {
        // Oracle: AC-19.1 — the reduced (head+tail) content the model receives is a plain string;
        // the schema's toolResult.result accepts "text or a structured result". A reduced-string
        // result event validates, so the disposed shape is schema-legal end to end.
        OutputDisposer disposer = new OutputDisposer(64);
        var reduced = disposer.reduceForContext(
                com.srk.codingagent.persistence.ContentBlock.toolResult(
                        "tu_1", "ok", "B".repeat(500) + " BUILD FAILED"), 5);
        Event event = new Event(7, TS,
                ToolResultPayload.of("tu_1", ToolResultStatus.OK, reduced.content()));

        assertTrue(validate(event).isEmpty(),
                "a TOOL_RESULT carrying the reduced head+tail string must validate (AC-19.1)");
    }
}
