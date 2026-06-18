package com.srk.codingagent.model.converse;

import com.srk.codingagent.persistence.StopReason;
import java.util.Locale;

/**
 * Maps the Bedrock Converse wire stop reason to our domain {@link StopReason} (§ 6.A.1;
 * 03-data-model.md § 7 — {@code StopReason: response stopReason, response → us}).
 *
 * <p>Per § 6.A.1 the Converse wire form is lowercase/underscore
 * ({@code end_turn | tool_use | max_tokens | stop_sequence | guardrail_intervened |
 * content_filtered | malformed_model_output | malformed_tool_use |
 * model_context_window_exceeded}); our domain {@link StopReason} enum names are the
 * upper-snake-case equivalents. The mapping is therefore a case fold of the wire token
 * to the enum constant name — done against the raw wire string
 * ({@code ConverseResponse.stopReasonAsString()}) so a stop reason the SDK enum does
 * not yet know ({@code UNKNOWN_TO_SDK_VERSION}) is still mapped by its wire token rather
 * than collapsing to the SDK's unknown sentinel.
 */
final class StopReasonMapper {

    private StopReasonMapper() {
    }

    /**
     * Maps a Converse wire stop-reason token to the domain {@link StopReason}.
     *
     * @param wireStopReason the wire token from {@code ConverseResponse.stopReasonAsString()},
     *                       e.g. {@code "end_turn"} or {@code "tool_use"}; must not be
     *                       {@code null} or blank.
     * @return the corresponding domain {@link StopReason}.
     * @throws IllegalArgumentException if {@code wireStopReason} is {@code null}, blank,
     *                                  or not one of the § 6.A.1 stop-reason tokens.
     */
    static StopReason fromWire(String wireStopReason) {
        if (wireStopReason == null || wireStopReason.isBlank()) {
            throw new IllegalArgumentException("stopReason must be non-blank");
        }
        String constantName = wireStopReason.toUpperCase(Locale.ROOT);
        try {
            return StopReason.valueOf(constantName);
        } catch (IllegalArgumentException unknownReason) {
            throw new IllegalArgumentException(
                    "unknown Converse stopReason: '" + wireStopReason + "'", unknownReason);
        }
    }
}
