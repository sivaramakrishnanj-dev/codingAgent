package com.srk.codingagent.cli;

import com.srk.codingagent.model.ModelCapabilityProfile;
import com.srk.codingagent.persistence.ContentBlock;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The C1&rarr;C4 attachment pipeline (component C1, T-4.2): resolves an
 * {@code --attach}/{@code /attach <path>} request into either an admitted multimodal
 * {@link ContentBlock} (an {@link ContentBlock.Image} or {@link ContentBlock.Document}) for the
 * user turn, or a {@link Attachment.Declined} carrying a user-facing message. The stages, in
 * order (§ 2.3 multimodal input):
 *
 * <ol>
 *   <li><b>Format inference.</b> The file extension selects the block kind and format:
 *       {@code png|jpg|jpeg|gif|webp} &rarr; an {@link ContentBlock.Image} (the Converse image
 *       formats; {@code jpg} normalizes to the {@code jpeg} format); the nine
 *       {@code pdf|csv|doc|docx|xls|xlsx|html|txt|md} extensions &rarr; a
 *       {@link ContentBlock.Document} (the Converse document formats). An unknown / extensionless
 *       path declines with a message (it is not silently dropped, 04-apis-style fail-soft for an
 *       attachment).</li>
 *   <li><b>Name sanitization (INV-18, documents only).</b> The {@link ContentBlock.Document}
 *       {@code name} is a prompt-injection surface, so it is never the raw filename: the source
 *       filename is reduced to a neutral name (alphanumeric/space/hyphen/parens/brackets,
 *       {@code <= 200} chars). Image blocks carry no name.</li>
 *   <li><b>Capability gate (INV-19).</b> The block is admitted only when the active
 *       {@link ModelCapabilityProfile} reports the matching input support
 *       ({@code supportsImageInput} for an image, {@code supportsDocumentInput} for a document).
 *       When the model lacks support the attachment is <em>declined with a message, not sent</em>
 *       (graceful degradation) — the agent call still proceeds, just without the attachment.</li>
 * </ol>
 *
 * <p>The block carries its bytes by reference ({@code bytesRef} = the resolved path); the wire
 * boundary ({@code ConverseWireMapper}) reads the file to raw bytes at send time. This resolver
 * does not read the file's bytes — it only inspects the extension and references the path — so it
 * is pure given a path string + a profile, and unit-testable without any filesystem read or AWS
 * call. Stateless and thread-safe.
 */
public final class AttachmentResolver {

    private static final Logger LOGGER = LoggerFactory.getLogger(AttachmentResolver.class);

    /**
     * The neutral fallback {@link ContentBlock.Document} name used when a source filename
     * sanitizes to nothing usable (e.g. a name made entirely of disallowed characters). A safe,
     * INV-18-conformant constant rather than risking an empty / pattern-violating name.
     */
    static final String DEFAULT_DOCUMENT_NAME = "document";

    /**
     * Image-file extensions and the Converse {@code ImageBlock} format each maps to (§ 2.3;
     * {@code content-block.schema.json} {@code ImageBlock.format}). {@code jpg} and {@code jpeg}
     * both map to the {@code jpeg} format — {@code jpg} is the common extension, {@code jpeg} the
     * Converse format token.
     */
    private static final Map<String, String> IMAGE_EXTENSION_TO_FORMAT = Map.of(
            "png", "png",
            "jpg", "jpeg",
            "jpeg", "jpeg",
            "gif", "gif",
            "webp", "webp");

    /**
     * Document-file extensions and the Converse {@code DocumentBlock} format each maps to (§ 2.3;
     * {@code content-block.schema.json} {@code DocumentBlock.format}). The extension equals the
     * format token for all nine document formats.
     */
    private static final Map<String, String> DOCUMENT_EXTENSION_TO_FORMAT = Map.of(
            "pdf", "pdf",
            "csv", "csv",
            "doc", "doc",
            "docx", "docx",
            "xls", "xls",
            "xlsx", "xlsx",
            "html", "html",
            "txt", "txt",
            "md", "md");

    private final ModelCapabilityProfile profile;

    /**
     * Creates a resolver bound to the session's active capability profile (the INV-19 gate).
     *
     * @param profile the model's capability profile, whose {@code supportsImageInput} /
     *                {@code supportsDocumentInput} flags gate whether an attachment is sent
     *                (INV-19); must not be {@code null}.
     * @throws NullPointerException if {@code profile} is {@code null}.
     */
    public AttachmentResolver(ModelCapabilityProfile profile) {
        this.profile = Objects.requireNonNull(profile, "profile");
    }

    /**
     * Resolves an attachment request for the given path.
     *
     * @param path the {@code --attach}/{@code /attach} path argument; must not be {@code null}.
     * @return an {@link Attachment.Attached} carrying the image/document block when the extension
     *         maps to a supported format <em>and</em> the model accepts that input (INV-19), else
     *         an {@link Attachment.Declined} carrying the user-facing reason (unknown extension,
     *         or capability not supported). Never {@code null}.
     * @throws NullPointerException if {@code path} is {@code null}.
     */
    public Attachment resolve(String path) {
        Objects.requireNonNull(path, "path");
        String extension = extensionOf(path);

        String imageFormat = IMAGE_EXTENSION_TO_FORMAT.get(extension);
        if (imageFormat != null) {
            return resolveImage(path, imageFormat);
        }
        String documentFormat = DOCUMENT_EXTENSION_TO_FORMAT.get(extension);
        if (documentFormat != null) {
            return resolveDocument(path, documentFormat);
        }
        // Unknown / extensionless: decline with a message rather than guessing a format or
        // silently dropping the attachment (§ 2.3 — only the listed image/document formats).
        LOGGER.info("Declining attachment '{}': unsupported extension '{}'", path, extension);
        return Attachment.declined("cannot attach '" + path
                + "': unsupported file type (expected an image " + ContentBlock.IMAGE_FORMATS
                + " or a document " + ContentBlock.DOCUMENT_FORMATS + ")");
    }

    /** INV-19 gate for an image attachment, then build the block (bytes carried by reference). */
    private Attachment resolveImage(String path, String format) {
        if (!profile.supportsImageInput()) {
            // INV-19: the model does not accept image input — decline with a message, do not send.
            LOGGER.info("Declining image attachment '{}': model does not support image input "
                    + "(INV-19)", path);
            return Attachment.declined("cannot attach image '" + path
                    + "': the active model does not support image input");
        }
        return Attachment.of(ContentBlock.image(format, path));
    }

    /** INV-19 gate for a document attachment, then build the block with a sanitized name (INV-18). */
    private Attachment resolveDocument(String path, String format) {
        if (!profile.supportsDocumentInput()) {
            // INV-19: the model does not accept document input — decline with a message, not sent.
            LOGGER.info("Declining document attachment '{}': model does not support document "
                    + "input (INV-19)", path);
            return Attachment.declined("cannot attach document '" + path
                    + "': the active model does not support document input");
        }
        String name = sanitizeName(fileNameOf(path));
        return Attachment.of(ContentBlock.document(name, format, path));
    }

    /**
     * Reduces a source filename to a neutral, INV-18-conformant {@link ContentBlock.Document}
     * name (the prompt-injection guard): every character outside the allowed
     * alphanumeric/space/hyphen/parens/brackets set is replaced with a space, runs of whitespace
     * are collapsed, the result is trimmed and capped at the schema's {@code maxLength}, and any
     * leading/trailing non-alphanumeric boundary char (which the schema's anchored pattern
     * forbids) is stripped. A name that reduces to nothing usable falls back to
     * {@link #DEFAULT_DOCUMENT_NAME}. The output is what {@link ContentBlock#document} re-validates
     * against the schema pattern, so this never produces a name the block constructor would reject.
     */
    static String sanitizeName(String rawFileName) {
        String replaced = rawFileName.replaceAll("[^A-Za-z0-9 ()\\[\\]-]", " ");
        String collapsed = replaced.replaceAll("\\s+", " ").strip();
        // The schema pattern requires the name start with an alphanumeric and end with an
        // alphanumeric or a closing paren/bracket; strip any leading/trailing char that is not a
        // valid boundary so the sanitized name matches the anchored pattern.
        String trimmedEnds = collapsed
                .replaceAll("^[^A-Za-z0-9]+", "")
                .replaceAll("[^A-Za-z0-9()\\[\\]]+$", "");
        if (trimmedEnds.isEmpty()) {
            return DEFAULT_DOCUMENT_NAME;
        }
        if (trimmedEnds.length() > ContentBlock.DOCUMENT_NAME_MAX_LENGTH) {
            String capped = trimmedEnds.substring(0, ContentBlock.DOCUMENT_NAME_MAX_LENGTH)
                    .replaceAll("[^A-Za-z0-9()\\[\\]]+$", "");
            return capped.isEmpty() ? DEFAULT_DOCUMENT_NAME : capped;
        }
        return trimmedEnds;
    }

    /** The lowercase file extension (no dot), or empty when the path has none. */
    private static String extensionOf(String path) {
        String fileName = fileNameOf(path);
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /** The final path segment (the filename), independent of the OS path separator. */
    private static String fileNameOf(String path) {
        return Optional.of(path.replace('\\', '/'))
                .map(p -> p.substring(p.lastIndexOf('/') + 1))
                .orElse(path);
    }
}
