package com.srk.codingagent.tool.memory;

import com.srk.codingagent.memory.MemoryEntry;
import com.srk.codingagent.memory.MemoryStatus;
import com.srk.codingagent.memory.MemoryStore;
import com.srk.codingagent.memory.MemoryTier;
import com.srk.codingagent.persistence.Event;
import com.srk.codingagent.persistence.EventLog;
import com.srk.codingagent.persistence.MemoryWritePayload;
import com.srk.codingagent.persistence.OperationClass;
import com.srk.codingagent.tool.ToolHandler;
import com.srk.codingagent.tool.ToolInvocationException;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.document.Document;

/**
 * The {@code write_memory} tool (component C12, ADR-0007): the explicit "remember X" write
 * path (US-12, AC-12.1). When the developer instructs the agent to remember a fact, the
 * agent calls this tool, which classifies the learning into a tier (AC-12.3), writes a
 * human-readable markdown entry plus its index line via the {@link MemoryStore} (AC-12.2,
 * AC-14.1, AC-14.3), and records a {@code MEMORY_WRITE} provenance event in the session log
 * (AC-12.4).
 *
 * <p>It is Class X ({@link OperationClass#SIDE_EFFECTING}) — a mutating write the permission
 * mode gates (ADR-0004); the gate itself sits between the model's {@code toolUse} and the
 * registry's dispatch (T-0.7) and is not re-implemented here.
 *
 * <p><b>Curated-only / no auto-extract (INV-13).</b> This handler persists an entry only when
 * the model explicitly calls {@code write_memory}; there is no path that writes an entry as a
 * side effect of anything else (CT-INV-11, AC-21.4). T-2.5's propose-and-approve flow is a
 * <em>separate</em> caller of {@link MemoryStore#write} (after the developer approves a
 * proposal); this tool is only the explicit path and does not implement the proposal flow.
 *
 * <p><b>Boundary-captured provenance (ADR-0005/ADR-0007).</b> The {@code created} timestamp
 * comes from the injected {@code clock} {@link Supplier}, and the {@code originSession} is
 * the injected session id — neither is derived in-process, so a write is reproducible and
 * tests are deterministic.
 */
public final class WriteMemoryTool implements ToolHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(WriteMemoryTool.class);

    /** The tool name as it appears in the toolSpec and the model's {@code toolUse.name}. */
    public static final String NAME = "write_memory";

    private final MemoryStore store;
    private final EventLog log;
    private final Supplier<String> clock;
    private final String originSession;
    private final String repoKey;

    /**
     * Creates the tool.
     *
     * @param store         the memory store the entry is written to; must not be {@code null}.
     * @param log           the session event log the {@code MEMORY_WRITE} event is appended to
     *                      (AC-12.4); must not be {@code null}.
     * @param clock         the boundary timestamp source for {@code created} (ADR-0005 —
     *                      never {@code Instant.now()}); must not be {@code null}.
     * @param originSession the session id that produced the write (provenance, AC-12.2);
     *                      non-blank.
     * @param repoKey       the repository key (boundary-captured) for the PROJECT tier;
     *                      non-blank.
     * @throws NullPointerException     if {@code store}, {@code log}, or {@code clock} is
     *                                  {@code null}.
     * @throws IllegalArgumentException if {@code originSession} or {@code repoKey} is blank.
     */
    public WriteMemoryTool(MemoryStore store, EventLog log, Supplier<String> clock,
            String originSession, String repoKey) {
        this.store = Objects.requireNonNull(store, "store");
        this.log = Objects.requireNonNull(log, "log");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.originSession = requireNonBlank(originSession, "originSession");
        this.repoKey = requireNonBlank(repoKey, "repoKey");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "Remember a fact or preference as a curated memory entry. "
                + "Classify it GLOBAL (cross-project) or PROJECT (this repo). "
                + "Subject to the active permission mode.";
    }

    @Override
    public Document inputSchema() {
        return MemoryToolSchemas.writeMemory();
    }

    @Override
    public OperationClass operationClass() {
        return OperationClass.SIDE_EFFECTING;
    }

    /**
     * Writes the curated memory entry and logs its {@code MEMORY_WRITE} provenance event.
     *
     * @param input the {@code toolUse.input}: requires {@code slug}, {@code tier},
     *              {@code why}, and {@code body}.
     * @return a short ok summary naming the slug and tier written.
     * @throws ToolInvocationException if a required input is missing/blank, {@code tier} is
     *                                 not {@code GLOBAL}/{@code PROJECT}, {@code slug} is not
     *                                 kebab-case, or the write fails.
     */
    @Override
    public Object handle(Map<String, Object> input) {
        Objects.requireNonNull(input, "input");
        String slug = MemoryToolInputs.requireString(input, "slug");
        MemoryTier tier = parseTier(MemoryToolInputs.requireString(input, "tier"));
        String why = MemoryToolInputs.requireString(input, "why");
        String body = MemoryToolInputs.requireString(input, "body");

        MemoryEntry entry = buildEntry(slug, tier, why, body);
        store.write(entry, repoKey);
        log.append(new Event(log.nextSeq(), clock.get(),
                new MemoryWritePayload(entry.slug(), entry.tier().name(), originSession, entry.why())));
        LOGGER.info("Remembered '{}' into {} memory", slug, tier);
        return "ok: remembered '" + slug + "' into " + tier + " memory";
    }

    private MemoryEntry buildEntry(String slug, MemoryTier tier, String why, String body) {
        try {
            return new MemoryEntry(slug, tier, clock.get(), originSession, why, MemoryStatus.ACTIVE, body);
        } catch (IllegalArgumentException e) {
            // A non-kebab slug or blank field: surface as a tool error the model can correct.
            throw new ToolInvocationException("invalid memory entry: " + e.getMessage(), e);
        }
    }

    private static MemoryTier parseTier(String raw) {
        try {
            return MemoryTier.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ToolInvocationException(
                    "input 'tier' must be GLOBAL or PROJECT but was '" + raw + "'", e);
        }
    }

    private static String requireNonBlank(String value, String field) {
        if (Objects.requireNonNull(value, field).isBlank()) {
            throw new IllegalArgumentException(field + " must be non-blank");
        }
        return value;
    }
}
