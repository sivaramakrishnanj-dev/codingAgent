package com.srk.codingagent.config;

/**
 * How the agent obtains authorization before running a tool that mutates state.
 *
 * <p>The four modes are pinned by the data model ({@code 03-data-model.md} § 4,
 * sourced from AC-9.1). T-0.2 introduces the enum as a foundational value type
 * carried by {@link ResolvedConfig#permissionMode()}; the permission <em>gate</em>
 * that interprets these modes (Class R/X classification, denylist) is built by a
 * later task and is out of scope here. The configured default is
 * {@link #ASK_EVERY_TIME} (NFR-PERMISSION-DEFAULT).
 */
public enum PermissionMode {

    /** Every tool runs without prompting. No confirmation gate. */
    UNRESTRICTED,

    /** Only non-mutating (read-only) tools run; mutating tools are refused. */
    READ_ONLY,

    /**
     * The agent prompts for confirmation before every mutating tool.
     * The configured default (NFR-PERMISSION-DEFAULT).
     */
    ASK_EVERY_TIME,

    /**
     * The agent prompts once for a given action class, then remembers the answer
     * for the remainder of the session.
     */
    ASK_ONCE_THEN_REMEMBER
}
