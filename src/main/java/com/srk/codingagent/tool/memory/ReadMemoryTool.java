package com.srk.codingagent.tool.memory;

import com.srk.codingagent.memory.MemoryEntry;
import com.srk.codingagent.memory.MemoryStore;
import com.srk.codingagent.persistence.OperationClass;
import com.srk.codingagent.tool.ToolHandler;
import com.srk.codingagent.tool.ToolInvocationException;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.document.Document;

/**
 * The {@code read_memory} tool (component C12, ADR-0007): pulls a <em>full</em> curated
 * memory entry on demand, given its {@code slug} from the always-loaded memory index. The
 * full prose body is not in the index (the index is the quick-review surface, AC-14.3), so
 * the model calls this when it wants the detail behind an index line.
 *
 * <p>It is Class R ({@link OperationClass#READ}) — a read is auto-approved and never gated
 * (ADR-0004; the same posture as {@code read_file}). The entry is re-read from disk on every
 * call (INV-14): a hand-edited file is reflected, a hand-deleted one reports as not found.
 */
public final class ReadMemoryTool implements ToolHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(ReadMemoryTool.class);

    /** The tool name as it appears in the toolSpec and the model's {@code toolUse.name}. */
    public static final String NAME = "read_memory";

    private final MemoryStore store;
    private final String repoKey;

    /**
     * Creates the tool over a memory store, scoped to one repository (for the PROJECT tier).
     *
     * @param store   the memory store; must not be {@code null}.
     * @param repoKey the repository key (boundary-captured) for the PROJECT tier; non-blank.
     * @throws NullPointerException     if {@code store} is {@code null}.
     * @throws IllegalArgumentException if {@code repoKey} is blank.
     */
    public ReadMemoryTool(MemoryStore store, String repoKey) {
        this.store = Objects.requireNonNull(store, "store");
        if (Objects.requireNonNull(repoKey, "repoKey").isBlank()) {
            throw new IllegalArgumentException("repoKey must be non-blank");
        }
        this.repoKey = repoKey;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "Read a curated memory entry in full by its slug (from the memory index).";
    }

    @Override
    public Document inputSchema() {
        return MemoryToolSchemas.readMemory();
    }

    @Override
    public OperationClass operationClass() {
        return OperationClass.READ;
    }

    /**
     * Reads the full markdown of the named memory entry (GLOBAL tier preferred, then
     * PROJECT), re-reading from disk (INV-14).
     *
     * @param input the {@code toolUse.input}: requires {@code slug}.
     * @return the entry's markdown body text.
     * @throws ToolInvocationException if {@code slug} is missing/blank, or no entry by that
     *                                 slug exists in either tier.
     */
    @Override
    public Object handle(Map<String, Object> input) {
        Objects.requireNonNull(input, "input");
        String slug = MemoryToolInputs.requireString(input, "slug");
        LOGGER.info("Reading memory entry {}", slug);
        Optional<MemoryEntry> entry = store.readEntry(slug, repoKey);
        if (entry.isEmpty()) {
            throw new ToolInvocationException("no memory entry found for slug: " + slug);
        }
        return entry.get().body();
    }
}
