package com.srk.codingagent.persistence;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * One block of conversation content carried by a {@link UserMessagePayload} or a
 * {@link ModelResponsePayload} (and, as a tool result, by a
 * {@link ToolResultPayload}'s embedded content). Mirrors the {@code ContentBlock}
 * schema ({@code 06-formal/content-block.schema.json}): a tagged union
 * discriminated by the {@code kind} field.
 *
 * <p><b>Scope.</b> Six block kinds are modelled here: {@link Text}, {@link ToolUse},
 * and {@link ToolResult} (the three present in the contract fixture
 * {@code session-tool-use-cycle.jsonl}, T-0.4), {@link Reasoning} (T-2.2 — the
 * reasoning block compaction must replay verbatim so its {@code signature} survives a
 * live Converse call, INV-7), and the multimodal input variants {@link Image} and
 * {@link Document} (T-4.2 — {@code --attach}/{@code /attach}; § 2.3 multimodal input,
 * capability-gated by INV-19). The remaining schema variant (cachePoint) is deliberately
 * not modelled yet — it is realized by the task that needs it; the {@code kind}
 * discriminator and sealed hierarchy make each extension additive. See
 * {@code stated_assumptions} in the task handoff.
 *
 * <p><b>Multimodal bytes are carried by reference ({@code bytesRef}, not inlined).</b> The
 * {@link Image} and {@link Document} variants carry a {@code bytesRef} — a path/reference to
 * the raw bytes on disk — rather than the bytes themselves. This is the
 * {@code content-block.schema.json} shape ({@code ImageBlock}/{@code DocumentBlock} both
 * require {@code bytesRef}) and reconciles with § 2.3's "sourced as raw bytes (the SDK
 * base64-encodes)": the persisted/replayed block carries only the reference (so a JSONL
 * event line does not bloat with base64), and the wire boundary ({@code ConverseWireMapper})
 * resolves the reference to raw bytes at send time, where the SDK base64-encodes them.
 * See {@code stated_assumptions} in the task handoff.
 *
 * <p>The {@code kind} discriminator is a real field of each block so it both
 * serializes (matching the schema's {@code kind} const) and round-trips on read
 * without reflective getter inference. Each variant serializes to exactly the fields
 * its schema branch allows ({@code additionalProperties: false}); {@code null}
 * optional fields are omitted so the emitted JSON matches the schema's optional-field
 * semantics. Use the {@link #text}, {@link #toolUse}, {@link #toolResult}, {@link #image},
 * and {@link #document} factories, which set the correct discriminator.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY,
        property = "kind", visible = true)
@JsonSubTypes({
    @JsonSubTypes.Type(value = ContentBlock.Text.class, name = ContentBlock.KIND_TEXT),
    @JsonSubTypes.Type(value = ContentBlock.ToolUse.class, name = ContentBlock.KIND_TOOL_USE),
    @JsonSubTypes.Type(value = ContentBlock.ToolResult.class, name = ContentBlock.KIND_TOOL_RESULT),
    @JsonSubTypes.Type(value = ContentBlock.Reasoning.class, name = ContentBlock.KIND_REASONING),
    @JsonSubTypes.Type(value = ContentBlock.Image.class, name = ContentBlock.KIND_IMAGE),
    @JsonSubTypes.Type(value = ContentBlock.Document.class, name = ContentBlock.KIND_DOCUMENT)
})
public sealed interface ContentBlock
        permits ContentBlock.Text, ContentBlock.ToolUse, ContentBlock.ToolResult,
                ContentBlock.Reasoning, ContentBlock.Image, ContentBlock.Document {

    /** The wire tag for a {@link Text} block. */
    String KIND_TEXT = "text";

    /** The wire tag for a {@link ToolUse} block. */
    String KIND_TOOL_USE = "toolUse";

    /** The wire tag for a {@link ToolResult} block. */
    String KIND_TOOL_RESULT = "toolResult";

    /** The wire tag for a {@link Reasoning} block. */
    String KIND_REASONING = "reasoning";

    /** The wire tag for an {@link Image} block. */
    String KIND_IMAGE = "image";

    /** The wire tag for a {@link Document} block. */
    String KIND_DOCUMENT = "document";

    /**
     * The wire discriminator for this block ({@code text}, {@code toolUse},
     * {@code toolResult}, {@code reasoning}, {@code image}, or {@code document}). Matches
     * the schema's {@code kind} const for the variant.
     *
     * @return the block kind tag; never {@code null}.
     */
    String kind();

    /**
     * Creates a text block.
     *
     * @param text the text content; must not be {@code null}.
     * @return a {@link Text} block.
     */
    static Text text(String text) {
        return new Text(KIND_TEXT, text);
    }

    /**
     * Creates a tool-use block.
     *
     * @param toolUseId the correlating tool-use id; non-blank.
     * @param name      the tool name; non-blank.
     * @param input     the tool input object; must not be {@code null}.
     * @return a {@link ToolUse} block.
     */
    static ToolUse toolUse(String toolUseId, String name, Map<String, Object> input) {
        return new ToolUse(KIND_TOOL_USE, toolUseId, name, input);
    }

    /**
     * Creates a tool-result block.
     *
     * @param toolUseId the correlating tool-use id; non-blank.
     * @param status    {@code ok} or {@code error}.
     * @param content   the result content, or {@code null}.
     * @return a {@link ToolResult} block.
     */
    static ToolResult toolResult(String toolUseId, String status, Object content) {
        return new ToolResult(KIND_TOOL_RESULT, toolUseId, status, content);
    }

    /**
     * Creates a reasoning block. The {@code signature} is a tamper-check hash over the
     * conversation that MUST be replayed verbatim (INV-7); the factory carries it (and
     * the {@code text}/{@code redactedContent}) through unchanged.
     *
     * @param text            the reasoning text, or {@code null} when only redacted
     *                        content is present.
     * @param signature       the tamper-check signature, or {@code null} when the
     *                        provider returned none.
     * @param redactedContent base64 redacted reasoning, or {@code null} when absent.
     * @return a {@link Reasoning} block.
     */
    static Reasoning reasoning(String text, String signature, String redactedContent) {
        return new Reasoning(KIND_REASONING, text, signature, redactedContent);
    }

    /**
     * Creates an image block (multimodal input, § 2.3 — input only, developer &rarr; model).
     *
     * @param format   the image format; one of {@code png}, {@code jpeg}, {@code gif},
     *                 {@code webp} (the Converse {@code ImageBlock} formats, schema
     *                 {@code ImageBlock.format} enum); non-blank.
     * @param bytesRef the path/reference to the raw bytes the wire layer reads at send time
     *                 (the SDK base64-encodes; bytes are not inlined into the JSONL log);
     *                 non-blank.
     * @return an {@link Image} block.
     */
    static Image image(String format, String bytesRef) {
        return new Image(KIND_IMAGE, format, bytesRef);
    }

    /**
     * Creates a document block (multimodal input, § 2.3 — input only, developer &rarr; model).
     *
     * @param name     the neutral, sanitized document name (INV-18 — prompt-injection
     *                 surface): alphanumeric/space/hyphen/parens/brackets, {@code <= 200}
     *                 chars, matching the schema's {@code DocumentBlock.name} pattern; must
     *                 already be sanitized (this factory rejects, it does not sanitize).
     * @param format   the document format; one of {@code pdf}, {@code csv}, {@code doc},
     *                 {@code docx}, {@code xls}, {@code xlsx}, {@code html}, {@code txt},
     *                 {@code md} (the Converse {@code DocumentBlock} formats, schema
     *                 {@code DocumentBlock.format} enum); non-blank.
     * @param bytesRef the path/reference to the raw bytes the wire layer reads at send time
     *                 (the SDK base64-encodes); non-blank.
     * @return a {@link Document} block.
     */
    static Document document(String name, String format, String bytesRef) {
        return new Document(KIND_DOCUMENT, name, format, bytesRef);
    }

    /**
     * A plain text block (the {@code TextBlock} schema variant).
     *
     * @param kind the discriminator; must equal {@link #KIND_TEXT}.
     * @param text the text content; must not be {@code null}.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Text(String kind, String text) implements ContentBlock {

        /**
         * Validates the block and its discriminator.
         *
         * @throws NullPointerException     if {@code text} is {@code null}.
         * @throws IllegalArgumentException if {@code kind} is not {@link #KIND_TEXT}.
         */
        public Text {
            requireKind(kind, KIND_TEXT);
            Objects.requireNonNull(text, "text");
        }
    }

    /**
     * A tool-invocation request block (the {@code ToolUseBlock} schema variant).
     *
     * @param kind      the discriminator; must equal {@link #KIND_TOOL_USE}.
     * @param toolUseId the correlating tool-use id; non-blank (schema
     *                  {@code minLength 1}).
     * @param name      the tool name; non-blank (schema {@code minLength 1}).
     * @param input     the tool input object; must not be {@code null} (schema
     *                  {@code type object}).
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record ToolUse(String kind, String toolUseId, String name, Map<String, Object> input)
            implements ContentBlock {

        /**
         * Validates the block and defensively copies {@code input}.
         *
         * @throws NullPointerException     if a non-discriminator field is
         *                                  {@code null}.
         * @throws IllegalArgumentException if {@code kind} is not
         *                                  {@link #KIND_TOOL_USE}, or
         *                                  {@code toolUseId}/{@code name} is blank.
         */
        public ToolUse {
            requireKind(kind, KIND_TOOL_USE);
            Payloads.requireNonBlank(toolUseId, "toolUseId");
            Payloads.requireNonBlank(name, "name");
            input = Map.copyOf(Objects.requireNonNull(input, "input"));
        }
    }

    /**
     * A tool-result block (the {@code ToolResultBlock} schema variant), as it may
     * appear inside conversation content. Distinct from the top-level
     * {@link ToolResultPayload} carried by a {@code TOOL_RESULT} event.
     *
     * @param kind      the discriminator; must equal {@link #KIND_TOOL_RESULT}.
     * @param toolUseId the correlating tool-use id; non-blank.
     * @param status    {@code ok} or {@code error} (the schema's content-block
     *                  status enum, narrower than the event payload's enum).
     * @param content   the result content (arbitrary JSON), or {@code null}.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record ToolResult(String kind, String toolUseId, String status, Object content)
            implements ContentBlock {

        /**
         * Validates the block.
         *
         * @throws NullPointerException     if {@code toolUseId} or {@code status} is
         *                                  {@code null}.
         * @throws IllegalArgumentException if {@code kind} is not
         *                                  {@link #KIND_TOOL_RESULT}, or
         *                                  {@code toolUseId} is blank.
         */
        public ToolResult {
            requireKind(kind, KIND_TOOL_RESULT);
            Payloads.requireNonBlank(toolUseId, "toolUseId");
            Objects.requireNonNull(status, "status");
        }
    }

    /**
     * A reasoning block (the {@code ReasoningBlock} schema variant): the model's
     * extended-thinking content. The schema marks its fields permissive because they
     * round-trip to the model verbatim — in particular the {@code signature} is a
     * tamper-check hash that MUST be replayed unchanged within a live conversation, or
     * the Converse call errors (INV-7, § 6.A.1). Only {@code kind} is required by the
     * schema; {@code text}, {@code signature}, and {@code redactedContent} are all
     * optional and carried through verbatim (no normalization).
     *
     * @param kind            the discriminator; must equal {@link #KIND_REASONING}.
     * @param text            the reasoning text, or {@code null}.
     * @param signature       the tamper-check signature replayed verbatim (INV-7), or
     *                        {@code null}.
     * @param redactedContent base64 redacted reasoning, or {@code null}.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Reasoning(String kind, String text, String signature, String redactedContent)
            implements ContentBlock {

        /**
         * Validates the discriminator. The {@code text}/{@code signature}/
         * {@code redactedContent} fields are permissive (schema optional; round-tripped
         * verbatim), so they are not constrained here beyond the {@code kind} const.
         *
         * @throws IllegalArgumentException if {@code kind} is not {@link #KIND_REASONING}.
         */
        public Reasoning {
            requireKind(kind, KIND_REASONING);
        }
    }

    /**
     * An image block (the {@code ImageBlock} schema variant): a multimodal <em>input</em>
     * (developer &rarr; model, § 2.3) carried by reference. The {@code format} is one of the
     * Converse image formats; the {@code bytesRef} points to the raw bytes the wire boundary
     * reads at send time (the SDK base64-encodes; bytes are not inlined into the JSONL log,
     * per the schema's {@code bytesRef} description). Image input is capability-gated (INV-19):
     * the attachment pipeline attaches this block only when the active
     * {@code ModelCapabilityProfile} reports {@code supportsImageInput}.
     *
     * @param kind     the discriminator; must equal {@link #KIND_IMAGE}.
     * @param format   the image format; must be one of {@link #IMAGE_FORMATS} (schema
     *                 {@code ImageBlock.format} enum {@code png|jpeg|gif|webp}).
     * @param bytesRef the path/reference to the raw bytes; non-blank (schema requires it).
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Image(String kind, String format, String bytesRef) implements ContentBlock {

        /**
         * Validates the block and its discriminator against the schema.
         *
         * @throws NullPointerException     if {@code format} or {@code bytesRef} is
         *                                  {@code null}.
         * @throws IllegalArgumentException if {@code kind} is not {@link #KIND_IMAGE},
         *                                  {@code bytesRef} is blank, or {@code format} is
         *                                  outside {@link #IMAGE_FORMATS} (CT-SCH-7).
         */
        public Image {
            requireKind(kind, KIND_IMAGE);
            if (!IMAGE_FORMATS.contains(format)) {
                throw new IllegalArgumentException(
                        "image format must be one of " + IMAGE_FORMATS + " but was '" + format + "'");
            }
            Payloads.requireNonBlank(bytesRef, "bytesRef");
        }
    }

    /**
     * A document block (the {@code DocumentBlock} schema variant): a multimodal <em>input</em>
     * (developer &rarr; model, § 2.3) carried by reference. The {@code name} must be neutral
     * and sanitized (INV-18 — the document name is a prompt-injection surface, so it is never
     * raw untrusted text); the canonical constructor rejects a name that does not match the
     * schema's {@code DocumentBlock.name} pattern/length (CT-INV-15: a non-sanitized name is
     * rejected before the block can be sent). The {@code format} is one of the Converse
     * document formats; the {@code bytesRef} points to the raw bytes the wire boundary reads
     * at send time. Document input is capability-gated (INV-19) by
     * {@code supportsDocumentInput}.
     *
     * @param kind     the discriminator; must equal {@link #KIND_DOCUMENT}.
     * @param name     the neutral, sanitized name; must match {@link #DOCUMENT_NAME_PATTERN}
     *                 and be {@code <= 200} chars (INV-18; schema {@code DocumentBlock.name}).
     * @param format   the document format; must be one of {@link #DOCUMENT_FORMATS} (schema
     *                 {@code DocumentBlock.format} enum).
     * @param bytesRef the path/reference to the raw bytes; non-blank (schema requires it).
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Document(String kind, String name, String format, String bytesRef)
            implements ContentBlock {

        /**
         * Validates the block, its discriminator, and the INV-18 name rule against the schema.
         *
         * @throws NullPointerException     if {@code name}, {@code format}, or
         *                                  {@code bytesRef} is {@code null}.
         * @throws IllegalArgumentException if {@code kind} is not {@link #KIND_DOCUMENT},
         *                                  {@code bytesRef} is blank, {@code format} is outside
         *                                  {@link #DOCUMENT_FORMATS} (CT-SCH-8), or {@code name}
         *                                  has disallowed characters or is {@code > 200} chars
         *                                  (INV-18, CT-SCH-6 / CT-INV-15).
         */
        public Document {
            requireKind(kind, KIND_DOCUMENT);
            requireSanitizedName(name);
            if (!DOCUMENT_FORMATS.contains(format)) {
                throw new IllegalArgumentException(
                        "document format must be one of " + DOCUMENT_FORMATS + " but was '"
                                + format + "'");
            }
            Payloads.requireNonBlank(bytesRef, "bytesRef");
        }
    }

    /**
     * The image formats the Converse {@code ImageBlock} accepts (schema
     * {@code ImageBlock.format} enum, § 6.A multimodal): {@code png}, {@code jpeg},
     * {@code gif}, {@code webp}. A format outside this set is rejected (CT-SCH-7).
     */
    Set<String> IMAGE_FORMATS = Set.of("png", "jpeg", "gif", "webp");

    /**
     * The document formats the Converse {@code DocumentBlock} accepts (schema
     * {@code DocumentBlock.format} enum, § 6.A multimodal): the nine
     * {@code pdf|csv|doc|docx|xls|xlsx|html|txt|md} formats (Word/Excel attach natively, no
     * conversion). A format outside this set is rejected (CT-SCH-8).
     */
    Set<String> DOCUMENT_FORMATS =
            Set.of("pdf", "csv", "doc", "docx", "xls", "xlsx", "html", "txt", "md");

    /**
     * The maximum length of a {@code DocumentBlock.name} (INV-18; schema {@code maxLength 200}).
     */
    int DOCUMENT_NAME_MAX_LENGTH = 200;

    /**
     * The {@code DocumentBlock.name} pattern (INV-18; schema {@code DocumentBlock.name.pattern}):
     * a neutral, sanitized name made of alphanumeric / space / hyphen / parens / brackets, that
     * starts with an alphanumeric and ends with an alphanumeric or a closing paren/bracket. A
     * name that does not match is rejected before send (the prompt-injection guard, CT-INV-15).
     */
    Pattern DOCUMENT_NAME_PATTERN =
            Pattern.compile("^[A-Za-z0-9]([A-Za-z0-9 ()\\[\\]-]*[A-Za-z0-9()\\[\\]])?$");

    private static void requireKind(String kind, String expected) {
        if (!expected.equals(kind)) {
            throw new IllegalArgumentException("kind must be '" + expected + "' but was '" + kind + "'");
        }
    }

    /**
     * Enforces INV-18 on a {@code DocumentBlock.name}: non-null, within
     * {@link #DOCUMENT_NAME_MAX_LENGTH}, and matching {@link #DOCUMENT_NAME_PATTERN}. Rejection
     * names the rule so a caller can see why a raw/unsanitized name was refused before send.
     */
    private static void requireSanitizedName(String name) {
        Objects.requireNonNull(name, "name");
        if (name.length() > DOCUMENT_NAME_MAX_LENGTH) {
            throw new IllegalArgumentException("document name must be <= " + DOCUMENT_NAME_MAX_LENGTH
                    + " chars (INV-18) but was " + name.length());
        }
        if (!DOCUMENT_NAME_PATTERN.matcher(name).matches()) {
            throw new IllegalArgumentException("document name must be neutral/sanitized "
                    + "(alphanumeric/space/hyphen/parens/brackets, INV-18) but was '" + name + "'");
        }
    }
}
