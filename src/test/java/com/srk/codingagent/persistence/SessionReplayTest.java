package com.srk.codingagent.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.srk.codingagent.config.PermissionMode;
import com.srk.codingagent.model.converse.ConverseMessage;
import com.srk.codingagent.model.converse.Role;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for {@link SessionReplay} — the resume reconstruction (AC-7.2) that reverses
 * the wire-format boundary (03-data-model § 7: <em>events &rarr; our blocks &rarr; Converse
 * {@code messages[]}</em>).
 *
 * <p>Oracles:
 * <ul>
 *   <li><b>AC-7.2</b>: "the agent shall reconstruct its context by replaying the session's
 *       persisted events." The reconstructed {@code messages[]} carries exactly the
 *       message-bearing turns (USER_MESSAGE &rarr; user, MODEL_RESPONSE &rarr; assistant)
 *       with their content blocks preserved.</li>
 *   <li><b>INV-1</b>: events are replayed in gap-free {@code seq} order — the turn order is
 *       the event order.</li>
 *   <li><b>INV-6</b>: a replayed transcript preserves the {@code toolUse}&harr;{@code
 *       toolResult} pairing (a continued Converse call would otherwise error at the wire —
 *       the D2 failure shape).</li>
 *   <li><b>03-data-model § 7</b>: only the message-bearing event kinds become turns; the
 *       audit-only kinds (SESSION_START, MODEL_USAGE, TOOL_USE, PERMISSION_DECISION,
 *       TOOL_RESULT, OUTCOME) are not turns in {@code messages[]}.</li>
 * </ul>
 *
 * <p>The SUT (a real {@link SessionReplay}) is never mocked; events are built either with
 * the real codec from the contract fixture or with a real {@link EventLog} round-trip, so
 * the test exercises the actual events&rarr;messages mapping, not a hand-faked one.
 */
class SessionReplayTest {

    private static final String REPO_KEY = "github.com-example-widget";
    private static final String SESSION_ID = "2026-06-17T090000Z-replay";

    private final SessionReplay replay = new SessionReplay();

    @Test
    @DisplayName("AC-7.2: a real loop's USER/MODEL events replay into the exact messages[] the loop accumulated")
    void replay_reconstructsLoopTranscript(@TempDir Path root) {
        // Oracle: AC-7.2 — reconstruct context by replaying persisted events. The authoritative
        // transcript a real loop accumulates is, in seq order: the developer USER_MESSAGE, the
        // assistant MODEL_RESPONSE, the batched tool-result USER_MESSAGE the loop sends back, and
        // the re-call's assistant MODEL_RESPONSE (AgentLoopTest CT-INV-2 pins this event order).
        // Replay must reproduce exactly that messages[]: user, assistant, user, assistant.
        SessionStore store = new SessionStore(root);
        try (EventLog log = store.openLog(REPO_KEY, SESSION_ID)) {
            log.append(userText("Run the unit tests."));
            log.append(modelToolUse("I'll run them.", "tu_01", "run_command", Map.of("command", "mvn test")));
            log.append(toolUseDigest("tu_01", "run_command", Map.of("command", "mvn test")));
            log.append(permissionApprove("tu_01"));
            log.append(toolResultEvent("tu_01", ToolResultStatus.OK, "BUILD SUCCESS"));
            log.append(batchedToolResultUserMessage("tu_01", ToolResultStatus.OK.wireValue(), "BUILD SUCCESS"));
            log.append(modelText("All tests pass.", StopReason.END_TURN));
        }

        List<ConverseMessage> messages = replay.replay(store.readEvents(REPO_KEY, SESSION_ID));

        assertEquals(4, messages.size(),
                "AC-7.2: only the 2 USER_MESSAGE + 2 MODEL_RESPONSE turns are messages; the "
                        + "TOOL_USE/PERMISSION_DECISION/TOOL_RESULT events are audit, not turns");
        assertEquals(List.of(Role.USER, Role.ASSISTANT, Role.USER, Role.ASSISTANT),
                messages.stream().map(ConverseMessage::role).toList(),
                "the reconstructed turns alternate user/assistant in the loop's order (INV-1)");

        // Turn 0: the developer prompt, a single Text block carried through unchanged.
        assertEquals(List.of(ContentBlock.text("Run the unit tests.")), messages.get(0).content(),
                "turn 0 is the developer USER_MESSAGE's content block, verbatim");
        // Turn 1: the assistant turn carries the text AND the toolUse block, in order.
        assertEquals(
                List.of(ContentBlock.text("I'll run them."),
                        ContentBlock.toolUse("tu_01", "run_command", Map.of("command", "mvn test"))),
                messages.get(1).content(),
                "turn 1 is the assistant MODEL_RESPONSE's text+toolUse blocks, in order");
        // Turn 2: the batched tool-result user message carrying the toolResult block.
        assertEquals(
                List.of(ContentBlock.toolResult("tu_01", ToolResultStatus.OK.wireValue(), "BUILD SUCCESS")),
                messages.get(2).content(),
                "turn 2 is the batched toolResult the loop sent back as one user message");
        // Turn 3: the assistant's final answer.
        assertEquals(List.of(ContentBlock.text("All tests pass.")), messages.get(3).content(),
                "turn 3 is the assistant's end_turn answer");
    }

    @Test
    @DisplayName("INV-6: the replayed transcript preserves toolUse<->toolResult pairing (wire-valid)")
    void replay_preservesToolUseToolResultPairing(@TempDir Path root) {
        // Oracle: INV-6 — "every ToolResultBlock.toolUseId matches a prior ToolUseBlock.toolUseId
        // in the same session." A replayed messages[] that violated this would make the next
        // Converse call fail at the wire (the D2 failure shape). Assert every toolResult block in
        // the transcript is preceded by a toolUse block with the same id.
        SessionStore store = new SessionStore(root);
        try (EventLog log = store.openLog(REPO_KEY, SESSION_ID)) {
            log.append(userText("Run the unit tests."));
            log.append(modelToolUse("Running.", "tu_42", "run_command", Map.of("command", "mvn test")));
            log.append(batchedToolResultUserMessage("tu_42", ToolResultStatus.OK.wireValue(), "ok"));
            log.append(modelText("Done.", StopReason.END_TURN));
        }

        List<ConverseMessage> messages = replay.replay(store.readEvents(REPO_KEY, SESSION_ID));

        assertTrue(toolResultsArePaired(messages),
                "INV-6: every replayed toolResult must be preceded by a toolUse with the same id");
    }

    @Test
    @DisplayName("AC-7.2: replaying the contract fixture maps its message events to the right turns")
    void replay_contractFixtureMapsMessageEvents() {
        // Oracle: AC-7.2 — the contract fixture session-tool-use-cycle.jsonl is the validated
        // multi-event session. Its message-bearing events (USER_MESSAGE seq 1, MODEL_RESPONSE
        // seq 2, MODEL_RESPONSE seq 7) replay to user, assistant, assistant turns; its
        // SESSION_START/MODEL_USAGE/TOOL_USE/PERMISSION_DECISION/TOOL_RESULT/OUTCOME events are
        // audit, not turns. (The fixture is the T-0.4 persistence contract fixture: it records
        // the per-block TOOL_RESULT but not the loop's batched USER_MESSAGE, so this asserts the
        // projection of the fixture's own events, not a full live transcript.)
        List<Event> events = decodeFixture();

        List<ConverseMessage> messages = replay.replay(events);

        assertEquals(3, messages.size(),
                "the fixture has exactly 3 message-bearing events (1 USER_MESSAGE + 2 MODEL_RESPONSE)");
        assertEquals(List.of(Role.USER, Role.ASSISTANT, Role.ASSISTANT),
                messages.stream().map(ConverseMessage::role).toList(),
                "the fixture's message events map by role in seq order (INV-1)");
        assertEquals(List.of(ContentBlock.text("Run the unit tests and tell me if they pass.")),
                messages.get(0).content(),
                "the user turn carries the fixture's seq-1 prompt text verbatim");
        assertEquals(
                List.of(ContentBlock.text("I'll run the test suite."),
                        ContentBlock.toolUse("tu_01", "run_command", Map.of("command", "mvn -q test"))),
                messages.get(1).content(),
                "the first assistant turn carries the fixture's seq-2 text+toolUse blocks in order");
        assertEquals(List.of(ContentBlock.text("All 42 unit tests pass (exit 0).")),
                messages.get(2).content(),
                "the second assistant turn carries the fixture's seq-7 end_turn text");
    }

    @Test
    @DisplayName("03-data-model § 7: audit-only events (no USER/MODEL message) replay to no turns")
    void replay_auditOnlyEvents_yieldNoMessages() {
        // Oracle: 03-data-model § 7 — only USER_MESSAGE/MODEL_RESPONSE are messages. A session of
        // only audit events (start, usage, permission, tool result, outcome) reconstructs to an
        // empty messages[]: none of those is a conversation turn.
        List<Event> auditOnly = List.of(
                new Event(0, "2026-06-17T09:00:00Z", new SessionStartPayload(
                        SessionMode.BROWNFIELD, REPO_KEY, "anthropic.claude-opus-4-8",
                        PermissionMode.ASK_EVERY_TIME)),
                new Event(1, "2026-06-17T09:00:06Z", ModelUsagePayload.of(10, 5)),
                new Event(2, "2026-06-17T09:00:09Z", ToolResultPayload.of("tu_01", ToolResultStatus.OK, "x")),
                new Event(3, "2026-06-17T09:01:16Z", new OutcomePayload("adhoc", true, 1)));

        assertTrue(replay.replay(auditOnly).isEmpty(),
                "a session with no USER_MESSAGE/MODEL_RESPONSE reconstructs to no turns");
    }

    @Test
    @DisplayName("AC-7.2: replaying an empty event list yields an empty messages[] (fresh session)")
    void replay_emptyEvents_yieldsEmpty() {
        // Oracle: AC-7.2 — a session with no events reconstructs to no context (empty messages[]),
        // not a crash. (SessionStore.readEvents returns empty for a not-yet-written session.)
        assertTrue(replay.replay(List.of()).isEmpty(),
                "an empty event list reconstructs to an empty transcript");
    }

    @Test
    @DisplayName("INV-1: replay preserves seq order even if events are presented monotonically")
    void replay_preservesSeqOrder(@TempDir Path root) {
        // Oracle: INV-1 — events are gap-free seq order; replay never reorders. After a round-trip
        // through the store (which reads in seq order), the message turns are in that same order.
        SessionStore store = new SessionStore(root);
        try (EventLog log = store.openLog(REPO_KEY, SESSION_ID)) {
            log.append(userText("first"));
            log.append(modelText("second", StopReason.END_TURN));
            log.append(userText("third"));
            log.append(modelText("fourth", StopReason.END_TURN));
        }

        List<ConverseMessage> messages = replay.replay(store.readEvents(REPO_KEY, SESSION_ID));

        assertEquals(List.of("first", "second", "third", "fourth"),
                messages.stream().map(m -> ((ContentBlock.Text) m.content().get(0)).text()).toList(),
                "INV-1: the reconstructed turns are in the events' seq order");
    }

    @Test
    @DisplayName("AC-7.5 surface: a corrupt log line surfaces a PersistenceException at read (before replay)")
    void readEvents_corruptLine_surfacesBeforeReplay(@TempDir Path root) throws Exception {
        // Oracle: AC-7.5 — "if a session's persisted events are corrupt or unreadable, the agent
        // reports it ... rather than crashing." The read that feeds replay surfaces a corrupt line
        // as a catchable PersistenceException; replay is never reached with bad input.
        SessionStore store = new SessionStore(root);
        Path log = store.logPath(REPO_KEY, SESSION_ID);
        java.nio.file.Files.createDirectories(log.getParent());
        java.nio.file.Files.writeString(log, "not valid event json\n");

        assertThrows(PersistenceException.class, () -> store.readEvents(REPO_KEY, SESSION_ID),
                "AC-7.5: a corrupt log surfaces a PersistenceException, not a crash");
    }

    @Test
    @DisplayName("replay rejects a null events list (defensive contract)")
    void replay_nullEvents_rejected() {
        assertThrows(NullPointerException.class, () -> replay.replay(null),
                "replay must reject a null events list");
    }

    // --- helpers: build events the way the real loop does (UserMessagePayload / ModelResponsePayload) ---

    private static Event userText(String text) {
        return new Event(0, "2026-06-17T09:00:05Z",
                new UserMessagePayload(List.of(ContentBlock.text(text))));
    }

    private static Event modelText(String text, StopReason stop) {
        return new Event(0, "2026-06-17T09:01:16Z",
                new ModelResponsePayload(stop, List.of(ContentBlock.text(text))));
    }

    private static Event modelToolUse(String text, String id, String name, Map<String, Object> input) {
        return new Event(0, "2026-06-17T09:00:06Z", new ModelResponsePayload(
                StopReason.TOOL_USE,
                List.of(ContentBlock.text(text), ContentBlock.toolUse(id, name, input))));
    }

    private static Event toolUseDigest(String id, String name, Map<String, Object> input) {
        return new Event(0, "2026-06-17T09:00:06Z", new ToolUsePayload(id, name, input));
    }

    private static Event permissionApprove(String id) {
        return new Event(0, "2026-06-17T09:00:09Z", new PermissionDecisionPayload(
                id, OperationClass.SIDE_EFFECTING, PermissionMode.ASK_EVERY_TIME,
                PermissionDecisionOutcome.APPROVE, null));
    }

    private static Event toolResultEvent(String id, ToolResultStatus status, Object result) {
        return new Event(0, "2026-06-17T09:01:14Z", ToolResultPayload.of(id, status, result));
    }

    private static Event batchedToolResultUserMessage(String id, String status, Object content) {
        return new Event(0, "2026-06-17T09:01:14Z",
                new UserMessagePayload(List.of(ContentBlock.toolResult(id, status, content))));
    }

    private static List<Event> decodeFixture() {
        EventCodec codec = new EventCodec();
        List<Event> events = new ArrayList<>();
        try (var lines = new java.io.BufferedReader(new java.io.InputStreamReader(
                SessionReplayTest.class.getResourceAsStream("/fixtures/session-tool-use-cycle.jsonl"),
                java.nio.charset.StandardCharsets.UTF_8))) {
            String line;
            while ((line = lines.readLine()) != null) {
                if (!line.isBlank()) {
                    events.add(codec.decode(line));
                }
            }
        } catch (java.io.IOException e) {
            throw new IllegalStateException("failed to read contract fixture", e);
        }
        return events;
    }

    /** True iff every toolResult block in the transcript follows a toolUse block with the same id. */
    private static boolean toolResultsArePaired(List<ConverseMessage> messages) {
        Set<String> seenToolUseIds = new HashSet<>();
        for (ConverseMessage message : messages) {
            for (ContentBlock block : message.content()) {
                if (block instanceof ContentBlock.ToolUse toolUse) {
                    seenToolUseIds.add(toolUse.toolUseId());
                } else if (block instanceof ContentBlock.ToolResult toolResult
                        && !seenToolUseIds.contains(toolResult.toolUseId())) {
                    return false;
                }
            }
        }
        return true;
    }
}
