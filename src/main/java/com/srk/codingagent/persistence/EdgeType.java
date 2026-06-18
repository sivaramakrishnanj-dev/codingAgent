package com.srk.codingagent.persistence;

/**
 * The kind of lineage edge linking a child session to its parent in the conversation
 * tree (ADR-0005; {@code 03-data-model.md} § 2.1). A root session has no parent and
 * therefore no edge type.
 *
 * <p>T-0.4 records this on the session's {@code .meta.json} summary so the
 * lineage/compaction tasks can build the conversation tree; T-0.4 does not itself
 * create edges or write compaction events.
 */
public enum EdgeType {

    /** The child was derived from the parent (e.g. a resumed or branched session). */
    DERIVED_FROM,

    /** The child was spawned by the parent (a sub-agent session). */
    SPAWNED_BY
}
