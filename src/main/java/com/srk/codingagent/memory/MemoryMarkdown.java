package com.srk.codingagent.memory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;

/**
 * Serializes a {@link MemoryEntry} to its on-disk markdown form (a YAML front-matter block
 * delimited by {@code ---} lines, followed by the prose body) and parses it back
 * (ADR-0007, AC-12.2, AC-14.1). Plain markdown a human can read, edit, and delete in a text
 * editor — there is no database (RD-9).
 *
 * <p><b>Schema-faithful front-matter (CT-SCH-11).</b> The emitted block carries exactly the
 * schema fields ({@code slug}, {@code tier}, {@code created}, {@code originSession},
 * {@code why}, {@code status}) with the schema's casing: {@code tier} uppercase
 * ({@code GLOBAL}/{@code PROJECT}), {@code status} lowercase ({@code active}/{@code retired}).
 * The {@code created} timestamp is emitted as a <em>quoted</em> string so YAML keeps it a
 * string rather than coercing an unquoted ISO datetime into a timestamp type (the validated
 * fixture quotes it, and the schema types {@code created} as a string).
 *
 * <p>Parsing uses a {@link SafeConstructor} (no arbitrary-type instantiation from the
 * document), matching the config loader's posture (ADR-0009). Package-private; the store's
 * read/write methods are the public surface.
 */
final class MemoryMarkdown {

    private static final String DELIMITER = "---";
    private static final String NEWLINE = "\n";

    private MemoryMarkdown() {
        // Non-instantiable.
    }

    /**
     * Renders an entry to its markdown text: the front-matter block then the prose body.
     *
     * @param entry the entry to render; must not be {@code null}.
     * @return the full markdown text of the {@code <slug>.md} file.
     * @throws NullPointerException if {@code entry} is {@code null}.
     */
    static String render(MemoryEntry entry) {
        Objects.requireNonNull(entry, "entry");
        StringBuilder out = new StringBuilder();
        out.append(DELIMITER).append(NEWLINE);
        out.append(field("slug", entry.slug()));
        out.append(field("tier", entry.tier().name()));
        out.append(quotedField("created", entry.created()));
        out.append(quotedField("originSession", entry.originSession()));
        out.append(quotedField("why", entry.why()));
        out.append(field("status", entry.status().wireValue()));
        out.append(DELIMITER).append(NEWLINE);
        out.append(NEWLINE);
        out.append(entry.body());
        if (!entry.body().endsWith(NEWLINE)) {
            out.append(NEWLINE);
        }
        return out.toString();
    }

    /**
     * Parses a {@code <slug>.md} file's text back into a {@link MemoryEntry}.
     *
     * @param markdown the full file text (front-matter block + prose body); must not be
     *                 {@code null}.
     * @return the parsed entry.
     * @throws NullPointerException     if {@code markdown} is {@code null}.
     * @throws MemoryStoreException     if the text has no well-formed front-matter block, a
     *                                  required field is missing, or a field value is
     *                                  outside its enum.
     */
    static MemoryEntry parse(String markdown) {
        Objects.requireNonNull(markdown, "markdown");
        FrontMatterSplit split = splitFrontMatter(markdown);
        Map<String, Object> fields = loadYaml(split.frontMatter());
        try {
            return new MemoryEntry(
                    stringField(fields, "slug"),
                    MemoryTier.valueOf(stringField(fields, "tier")),
                    stringField(fields, "created"),
                    stringField(fields, "originSession"),
                    stringField(fields, "why"),
                    MemoryStatus.fromWire(stringField(fields, "status")),
                    split.body());
        } catch (IllegalArgumentException e) {
            // A bad enum value or a slug/why that fails the entry's own validation.
            throw new MemoryStoreException("memory entry front-matter is invalid: " + e.getMessage(), e);
        }
    }

    private static FrontMatterSplit splitFrontMatter(String markdown) {
        String normalized = markdown.replace("\r\n", NEWLINE);
        if (!normalized.startsWith(DELIMITER + NEWLINE)) {
            throw new MemoryStoreException("memory entry must begin with a '---' front-matter block");
        }
        int afterOpen = DELIMITER.length() + NEWLINE.length();
        int close = normalized.indexOf(NEWLINE + DELIMITER, afterOpen - NEWLINE.length());
        if (close < 0) {
            throw new MemoryStoreException("memory entry front-matter block is not closed with '---'");
        }
        String frontMatter = normalized.substring(afterOpen, close + NEWLINE.length());
        int bodyStart = close + NEWLINE.length() + DELIMITER.length();
        String body = bodyStart >= normalized.length() ? "" : normalized.substring(bodyStart);
        return new FrontMatterSplit(frontMatter, stripLeadingBlankLine(body));
    }

    private static String stripLeadingBlankLine(String body) {
        String trimmed = body;
        if (trimmed.startsWith(NEWLINE)) {
            trimmed = trimmed.substring(NEWLINE.length());
        }
        if (trimmed.startsWith(NEWLINE)) {
            trimmed = trimmed.substring(NEWLINE.length());
        }
        return trimmed;
    }

    private static Map<String, Object> loadYaml(String frontMatter) {
        try {
            Object root = new Yaml(new SafeConstructor(new LoaderOptions())).load(frontMatter);
            if (!(root instanceof Map<?, ?> raw)) {
                throw new MemoryStoreException("memory entry front-matter is not a YAML mapping");
            }
            Map<String, Object> fields = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : raw.entrySet()) {
                fields.put(String.valueOf(e.getKey()), e.getValue());
            }
            return fields;
        } catch (YAMLException e) {
            throw new MemoryStoreException("memory entry front-matter is not valid YAML: " + e.getMessage(), e);
        }
    }

    private static String stringField(Map<String, Object> fields, String name) {
        Object value = fields.get(name);
        if (value == null) {
            throw new MemoryStoreException("memory entry front-matter is missing required field '" + name + "'");
        }
        return String.valueOf(value);
    }

    private static String field(String key, String value) {
        return key + ": " + value + NEWLINE;
    }

    private static String quotedField(String key, String value) {
        return key + ": \"" + escape(value) + "\"" + NEWLINE;
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** The two parts of a parsed memory file: the raw front-matter YAML and the prose body. */
    private record FrontMatterSplit(String frontMatter, String body) {
    }
}
