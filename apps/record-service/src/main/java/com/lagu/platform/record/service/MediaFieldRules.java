package com.lagu.platform.record.service;

import com.lagu.platform.record.client.MetadataClient.FieldSchemaDto;
import com.lagu.platform.storage.ImageConstraints;
import com.lagu.platform.storage.MediaPolicy;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The upload rules for a media field: what an admin declared in schema-registry, over the
 * built-in default for the field's type.
 *
 * <p>This is the no-code seam for record media. The rules ride in the field's
 * {@code validationRules} JSONB, which the schema-registry field API already exposes for editing
 * and already delivers to this service with the schema — so restricting a floor-plan field to
 * PDF, or capping a venue gallery at twelve photos, is a configuration change rather than a
 * release.
 *
 * <pre>
 * {"allowedMimeTypes": ["image/jpeg", "image/png"], "maxSizeMb": 10,
 *  "minCount": 3, "maxCount": 12}
 * </pre>
 *
 * <p>Rules arrive as deserialized JSON from a service this one does not control, so anything
 * malformed is ignored rather than fatal — a bad rule falls back to the built-in default instead
 * of taking the upload path down with it.
 */
public final class MediaFieldRules {

    /**
     * An IMAGE field's value is rendered by clients, and a signed bucket URL serves an object
     * with whatever Content-Type it was stored under — so SVG or HTML in one is a stored-XSS
     * vector. Raster formats only, matching document-service's reasoning.
     */
    private static final MediaPolicy IMAGE_DEFAULT = MediaPolicy.of(
            List.of("image/jpeg", "image/png", "image/webp"), 25);

    /** FILE fields also take PDFs. */
    private static final MediaPolicy FILE_DEFAULT = MediaPolicy.of(
            List.of("image/jpeg", "image/png", "image/webp", "application/pdf"), 25);

    /** A gallery is a set of photographs, so it inherits the image rules rather than the file
     *  ones — a PDF in a venue's photo carousel is not a thing anyone wants to render. */
    private static final MediaPolicy GALLERY_DEFAULT = IMAGE_DEFAULT;

    /**
     * Ceiling on gallery size when an admin sets none. High enough not to obstruct a real venue
     * listing, low enough that one vendor cannot make a listing page unloadable — every item is
     * a separate object, and the consumer app fetches signed URLs for all of them.
     */
    public static final int DEFAULT_MAX_COUNT = 30;

    public static final String TYPE_IMAGE = "IMAGE";
    public static final String TYPE_FILE = "FILE";
    public static final String TYPE_GALLERY = "MEDIA_GALLERY";

    private static final String RULE_MIME_TYPES = "allowedMimeTypes";
    private static final String RULE_MAX_SIZE_MB = "maxSizeMb";
    private static final String RULE_MIN_COUNT = "minCount";
    private static final String RULE_MAX_COUNT = "maxCount";
    private static final String RULE_MIN_WIDTH = "minWidth";
    private static final String RULE_MIN_HEIGHT = "minHeight";
    private static final String RULE_MAX_WIDTH = "maxWidth";
    private static final String RULE_MAX_HEIGHT = "maxHeight";

    private MediaFieldRules() {
    }

    /** True for the field types whose value is an uploaded object rather than client data. */
    public static boolean isMediaField(String fieldType) {
        return TYPE_IMAGE.equals(fieldType) || TYPE_FILE.equals(fieldType)
                || TYPE_GALLERY.equals(fieldType);
    }

    /** What one uploaded object may be: the type's default, narrowed or widened by the admin. */
    public static MediaPolicy policyFor(FieldSchemaDto field) {
        MediaPolicy base = switch (field.type()) {
            case TYPE_IMAGE -> IMAGE_DEFAULT;
            case TYPE_GALLERY -> GALLERY_DEFAULT;
            default -> FILE_DEFAULT;
        };
        Map<String, Object> rules = field.validation();
        if (rules == null || rules.isEmpty()) {
            return base;
        }
        return base.overriddenBy(
                stringList(rules.get(RULE_MIME_TYPES)), intOrNull(rules.get(RULE_MAX_SIZE_MB)));
    }

    /**
     * Pixel bounds for the field, or {@link ImageConstraints#NONE} when it declares none.
     *
     * <p>Only bites on formats the platform can decode. A PDF in a FILE field has no pixel
     * dimensions to check, and neither does a HEIC, so a field mixing those with images gets the
     * rule applied to the images alone.
     */
    public static ImageConstraints imageConstraintsFor(FieldSchemaDto field) {
        Map<String, Object> rules = field.validation();
        if (rules == null || rules.isEmpty()) return ImageConstraints.NONE;
        return new ImageConstraints(
                intOrNull(rules.get(RULE_MIN_WIDTH)),
                intOrNull(rules.get(RULE_MIN_HEIGHT)),
                intOrNull(rules.get(RULE_MAX_WIDTH)),
                intOrNull(rules.get(RULE_MAX_HEIGHT)));
    }

    /**
     * Whether uploads to this field get thumbnails.
     *
     * <p>Image-shaped fields do; a FILE field is as likely to hold a floor plan as a photo, and
     * a thumbnail of a PDF is not something this pipeline can produce anyway.
     */
    public static boolean wantsDerivatives(FieldSchemaDto field) {
        return TYPE_IMAGE.equals(field.type()) || TYPE_GALLERY.equals(field.type());
    }

    /** Most items a gallery may hold. Always a real number — an unconfigured gallery is still
     *  bounded, because "unbounded" is not a useful state for something a page has to render. */
    public static int maxCount(FieldSchemaDto field) {
        Integer configured = intOrNull(rule(field, RULE_MAX_COUNT));
        return (configured == null || configured <= 0) ? DEFAULT_MAX_COUNT : configured;
    }

    /**
     * Fewest items the gallery must hold to be valid, or 0 when unset.
     *
     * <p>This is the listing-quality control an admin actually reaches for — "a venue needs at
     * least five photos before it can be submitted" — and it is enforced by record validation
     * rather than at upload, because it is a statement about the finished listing.
     */
    public static int minCount(FieldSchemaDto field) {
        Integer configured = intOrNull(rule(field, RULE_MIN_COUNT));
        return (configured == null || configured < 0) ? 0 : configured;
    }

    /**
     * True when the field cannot legitimately be left empty, regardless of its {@code required}
     * flag — a gallery with a minimum photo count is asking for photos, and honouring the count
     * only once the vendor has uploaded at least one would invert the rule.
     */
    public static boolean requiresContent(FieldSchemaDto field) {
        return TYPE_GALLERY.equals(field.type()) && minCount(field) > 0;
    }

    private static Object rule(FieldSchemaDto field, String key) {
        Map<String, Object> rules = field.validation();
        return rules == null ? null : rules.get(key);
    }

    private static List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) return null;
        return list.stream().filter(Objects::nonNull).map(Object::toString).toList();
    }

    private static Integer intOrNull(Object value) {
        return value instanceof Number n ? n.intValue() : null;
    }
}
