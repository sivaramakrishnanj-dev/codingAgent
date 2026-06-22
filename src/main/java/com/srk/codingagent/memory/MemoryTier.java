package com.srk.codingagent.memory;

/**
 * The tier a curated {@link MemoryEntry} is stored in (component C16, ADR-0007, RD-9,
 * AC-12.3). On write the agent classifies a learning as cross-project or repo-specific and
 * stores it in the corresponding tier; on load <em>both</em> tiers' indexes are read so the
 * awareness surface spans global and project memory.
 *
 * <p>The constant names are the schema's uppercase wire vocabulary
 * ({@code 06-formal/memory-entry.schema.json}, {@code tier} enum = {@code GLOBAL} /
 * {@code PROJECT}): the markdown front-matter emits the name verbatim, so an entry whose
 * {@code tier} is outside this enum is rejected (CT-SCH-12).
 */
public enum MemoryTier {

    /**
     * Cross-project memory, stored under {@code <store>/memory/} — learnings that apply
     * regardless of which repository the agent is working in (RD-9, AC-12.3).
     */
    GLOBAL,

    /**
     * Repository-specific memory, stored under
     * {@code <store>/projects/<repo-key>/memory/} — learnings scoped to one repository
     * (RD-9, AC-12.3).
     */
    PROJECT
}
