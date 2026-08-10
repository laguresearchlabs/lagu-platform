package com.lagu.platform.record.service;

import com.lagu.platform.common.exception.ValidationException;
import com.lagu.platform.common.visibility.VisibilityRules;
import com.lagu.platform.record.client.MetadataClient;
import com.lagu.platform.record.client.MetadataClient.FieldSchemaDto;
import com.lagu.platform.record.client.MetadataClient.ObjectTypeSchemaDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

@Component
@RequiredArgsConstructor
@Slf4j
public class RecordValidator {

    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final Pattern PHONE_PATTERN =
            Pattern.compile("^\\+?[0-9\\-\\s()]{7,20}$");
    private static final Pattern URL_PATTERN =
            Pattern.compile("^https?://.*");

    private final MetadataClient metadataClient;

    public void validate(String objectType, Map<String, Object> data) {
        ObjectTypeSchemaDto schema = metadataClient.getSchema(objectType.toUpperCase());
        List<String> errors = new ArrayList<>();

        for (FieldSchemaDto field : schema.fields()) {
            // A field hidden by its own or its section's rule is not part of this record, so it
            // can be neither required nor type-checked. Without this, a required field the user
            // was never shown would make the record unsubmittable. Rules are evaluated against
            // the incoming data, exactly as the client evaluated them to build the form.
            if (!VisibilityRules.isVisible(field.visibleWhen(), data)) continue;

            Object value = data.get(field.name());

            // A gallery with a minimum photo count is required by that alone. Without this, an
            // admin setting minCount=5 on an optional gallery would get a rule that fires for a
            // vendor who uploads four photos and stays silent for one who uploads none — which
            // is the opposite of what "at least five photos" is asking for.
            boolean required = field.required() || MediaFieldRules.requiresContent(field);

            if (required && (value == null || isBlank(value))) {
                errors.add(field.name() + ": field is required");
                continue;
            }
            if (value == null || (!required && isBlank(value))) continue;

            validateByType(field, value, errors);
        }

        Set<String> knownFields = schema.fields().stream()
                .map(FieldSchemaDto::name)
                .collect(java.util.stream.Collectors.toSet());
        data.keySet().stream()
                .filter(k -> !knownFields.contains(k))
                .forEach(k -> log.warn("Unknown field '{}' for objectType {} — stored but not schema-defined", k, objectType));

        if (!errors.isEmpty()) {
            throw new ValidationException(objectType, errors);
        }
    }

    /**
     * Drops values for fields whose visibility rule is currently false.
     *
     * <p>Hidden means "not applicable to this record", so a hidden field's value has no business
     * being stored. events-ui already strips these before submitting, but that is a client
     * courtesy — a stale client, a direct API call, or a future non-web client would otherwise
     * persist values for fields nobody can see, which would reappear if the rule later flipped
     * back.
     *
     * <p>Values are removed rather than rejected: rejecting would race with schema changes, where
     * a client holding a slightly older schema legitimately believes a field is visible.
     *
     * <p>Rules are evaluated against the incoming data, not the partially-stripped result, so the
     * outcome does not depend on the order fields happen to be iterated in.
     */
    public Map<String, Object> stripHiddenFields(String objectType, Map<String, Object> data) {
        ObjectTypeSchemaDto schema = metadataClient.getSchema(objectType.toUpperCase());
        Map<String, Object> result = new java.util.HashMap<>(data);

        for (FieldSchemaDto field : schema.fields()) {
            if (!VisibilityRules.isVisible(field.visibleWhen(), data)) {
                result.remove(field.name());
            }
        }
        return result;
    }

    /**
     * Replaces FILE/IMAGE values in an incoming write with the ones already stored, dropping
     * them entirely when the record has none.
     *
     * <p>These fields hold an object storage key, and the only thing entitled to write one is
     * the confirm step in {@code RecordFileController}, which mints the key itself and verifies
     * the uploaded bytes before saving it. The generic record write reaches the same JSONB
     * though, and it used to accept any non-blank string there — so a caller with UPDATE on
     * their own record could set {@code cover_image} to a key belonging to a different record
     * and then collect a signed URL for it from the download endpoint. Keys are guessable
     * enough to matter: the record id is in the path, and the rest is a UUID plus the original
     * filename.
     *
     * <p>Client-supplied values are ignored rather than rejected, matching how
     * {@link #stripHiddenFields} treats fields a caller should not be setting — a read-modify-
     * write client that sends the record back unchanged keeps working, and one that tampers
     * simply has no effect.
     *
     * @param existing the record's current data; empty for a create, where no upload can have
     *                 happened yet because the key is scoped to a record id that does not exist
     */
    public Map<String, Object> preserveServerOwnedFields(String objectType,
                                                         Map<String, Object> incoming,
                                                         Map<String, Object> existing) {
        ObjectTypeSchemaDto schema = metadataClient.getSchema(objectType.toUpperCase());
        Map<String, Object> result = new java.util.HashMap<>(incoming);

        for (FieldSchemaDto field : schema.fields()) {
            if (!SERVER_OWNED_TYPES.contains(field.type())) continue;
            Object stored = existing.get(field.name());
            if (stored != null) {
                result.put(field.name(), stored);
            } else {
                result.remove(field.name());
            }
        }
        return result;
    }

    /** Field types whose value only this service may write. See {@link #preserveServerOwnedFields}. */
    private static final java.util.Set<String> SERVER_OWNED_TYPES = java.util.Set.of(
            MediaFieldRules.TYPE_FILE, MediaFieldRules.TYPE_IMAGE, MediaFieldRules.TYPE_GALLERY);

    private void validateByType(FieldSchemaDto field, Object value, List<String> errors) {
        String type = field.type();
        switch (type) {
            case "NUMBER"   -> validateNumber(field, value, errors);
            case "DECIMAL"  -> validateDecimal(field, value, errors);
            case "TEXT"     -> validateText(field, value, errors);
            case "LONG_TEXT" -> validateLongText(field, value, errors);
            case "EMAIL"    -> validateEmail(field, value, errors);
            case "PHONE"    -> validatePhone(field, value, errors);
            case "URL"      -> validateUrl(field, value, errors);
            case "ENUM"     -> validateEnum(field, value, errors);
            case "MULTI_SELECT" -> validateMultiSelect(field, value, errors);
            case "BOOLEAN"  -> validateBoolean(field, value, errors);
            case "ARRAY_OF_OBJECTS" -> validateArrayOfObjects(field, value, errors);
            case "DATE"     -> validateDate(field, value, errors);
            case "DATETIME" -> validateDateTime(field, value, errors);
            case "TIME"     -> validateTime(field, value, errors);
            case "ENTITY_REFERENCE", "USER_REFERENCE" -> validateReference(field, value, errors);
            case "FILE", "IMAGE" -> validateFileOrImage(field, value, errors);
            case "MEDIA_GALLERY" -> validateGallery(field, value, errors);
            case "CURRENCY" -> validateCurrency(field, value, errors);
            case "GEOLOCATION", "ADDRESS" -> validateStructured(field, value, errors);
            // JSON is deliberately unvalidated beyond being valid JSON (already guaranteed by
            // request deserialization) — an arbitrary nested payload is the entire point of the
            // type, so there is no further structural check to apply generically here.
            case "JSON" -> {}
            default -> log.warn("No validator registered for field type '{}' on field '{}' — value stored unchecked",
                    type, field.name());
        }
    }

    private void validateNumber(FieldSchemaDto field, Object value, List<String> errors) {
        if (!(value instanceof Number)) {
            errors.add(field.name() + ": must be a number");
            return;
        }
        double num = ((Number) value).doubleValue();
        Map<String, Object> rules = field.validation();
        if (rules != null) {
            asNumber(rules.get("min")).ifPresentOrElse(
                    min -> { if (num < min.doubleValue()) errors.add(field.name() + ": must be >= " + min); },
                    () -> warnIfPresent(rules, "min", field.name()));
            asNumber(rules.get("max")).ifPresentOrElse(
                    max -> { if (num > max.doubleValue()) errors.add(field.name() + ": must be <= " + max); },
                    () -> warnIfPresent(rules, "max", field.name()));
        }
    }

    /** Safely narrows a schema-author-supplied validationRules value to a Number, rather than an
     *  unchecked cast that would throw ClassCastException (500) if it were mis-typed — e.g. a
     *  string "10" instead of the number 10 in a field's rules JSON. */
    private java.util.Optional<Number> asNumber(Object raw) {
        return raw instanceof Number n ? java.util.Optional.of(n) : java.util.Optional.empty();
    }

    private void warnIfPresent(Map<String, Object> rules, String key, String fieldName) {
        if (rules.containsKey(key)) {
            log.warn("validationRules.{} for field '{}' is not a number ({}) — rule ignored",
                    key, fieldName, rules.get(key));
        }
    }

    private void validateDecimal(FieldSchemaDto field, Object value, List<String> errors) {
        if (!(value instanceof Number)) {
            errors.add(field.name() + ": must be a decimal number");
        }
    }

    private void validateText(FieldSchemaDto field, Object value, List<String> errors) {
        if (!(value instanceof String str)) {
            errors.add(field.name() + ": must be a string");
            return;
        }
        Map<String, Object> rules = field.validation();
        if (rules != null) {
            asNumber(rules.get("maxLength")).ifPresentOrElse(
                    maxLen -> { if (str.length() > maxLen.intValue()) {
                        errors.add(field.name() + ": exceeds max length of " + maxLen);
                    } },
                    () -> warnIfPresent(rules, "maxLength", field.name()));
            if (rules.get("pattern") instanceof String patternStr) {
                try {
                    // A schema-author-supplied regex used to run uncached and uncaught here — a
                    // malformed pattern threw PatternSyntaxException straight to a 500, and a
                    // catastrophically-backtracking one could hang the request thread. Catching
                    // the compile error turns the first into a clean validation error; the
                    // matcher itself is still not run under a timeout, so a hostile pattern can
                    // still be slow — full ReDoS protection would need matching on a bounded
                    // executor, which is a larger change than this fix.
                    if (!Pattern.compile(patternStr).matcher(str).matches()) {
                        errors.add(field.name() + ": does not match required pattern");
                    }
                } catch (PatternSyntaxException e) {
                    log.warn("validationRules.pattern for field '{}' is not a valid regex: {}", field.name(), e.getMessage());
                    errors.add(field.name() + ": field is misconfigured (invalid validation pattern)");
                }
            }
        }
    }

    private void validateLongText(FieldSchemaDto field, Object value, List<String> errors) {
        if (!(value instanceof String)) {
            errors.add(field.name() + ": must be a string");
        }
    }

    private void validateEmail(FieldSchemaDto field, Object value, List<String> errors) {
        if (!(value instanceof String str) || !EMAIL_PATTERN.matcher(str).matches()) {
            errors.add(field.name() + ": must be a valid email address");
        }
    }

    private void validatePhone(FieldSchemaDto field, Object value, List<String> errors) {
        if (!(value instanceof String str) || !PHONE_PATTERN.matcher(str).matches()) {
            errors.add(field.name() + ": must be a valid phone number");
        }
    }

    private void validateUrl(FieldSchemaDto field, Object value, List<String> errors) {
        if (!(value instanceof String str) || !URL_PATTERN.matcher(str).matches()) {
            errors.add(field.name() + ": must be a valid URL starting with http:// or https://");
        }
    }

    private void validateEnum(FieldSchemaDto field, Object value, List<String> errors) {
        List<String> allowed = field.enumValues();
        if (allowed == null || allowed.isEmpty()) return;
        if (!(value instanceof String str) || !allowed.contains(str)) {
            errors.add(field.name() + ": must be one of " + allowed);
        }
    }

    private void validateMultiSelect(FieldSchemaDto field, Object value, List<String> errors) {
        if (!(value instanceof List<?> list)) {
            errors.add(field.name() + ": must be an array");
            return;
        }
        List<String> allowed = field.enumValues();
        if (allowed != null && !allowed.isEmpty()) {
            list.stream()
                .filter(item -> !allowed.contains(String.valueOf(item)))
                .forEach(item -> errors.add(field.name() + ": invalid value '" + item + "', must be one of " + allowed));
        }
    }

    private void validateBoolean(FieldSchemaDto field, Object value, List<String> errors) {
        if (!(value instanceof Boolean)) {
            errors.add(field.name() + ": must be true or false");
        }
    }

    private void validateDate(FieldSchemaDto field, Object value, List<String> errors) {
        if (!(value instanceof String str)) {
            errors.add(field.name() + ": must be a string in ISO date format (yyyy-MM-dd)");
            return;
        }
        try {
            LocalDate.parse(str);
        } catch (DateTimeParseException e) {
            errors.add(field.name() + ": must be a valid ISO date (yyyy-MM-dd)");
        }
    }

    private void validateDateTime(FieldSchemaDto field, Object value, List<String> errors) {
        if (!(value instanceof String str)) {
            errors.add(field.name() + ": must be a string in ISO date-time format");
            return;
        }
        // Accepts both an offset/zoned instant (2026-01-01T10:00:00Z) and a bare local
        // date-time (2026-01-01T10:00:00) — events-ui's DynamicSchemaForm sends the latter for
        // a plain <input type="datetime-local">, which OffsetDateTime.parse alone would reject.
        try {
            OffsetDateTime.parse(str);
        } catch (DateTimeParseException e1) {
            try {
                java.time.LocalDateTime.parse(str);
            } catch (DateTimeParseException e2) {
                errors.add(field.name() + ": must be a valid ISO date-time");
            }
        }
    }

    private void validateTime(FieldSchemaDto field, Object value, List<String> errors) {
        if (!(value instanceof String str)) {
            errors.add(field.name() + ": must be a string in ISO time format (HH:mm or HH:mm:ss)");
            return;
        }
        try {
            LocalTime.parse(str);
        } catch (DateTimeParseException e) {
            errors.add(field.name() + ": must be a valid ISO time (HH:mm or HH:mm:ss)");
        }
    }

    /** ENTITY_REFERENCE/USER_REFERENCE: checked structurally (must be a real record/user id
     *  shape) but not for existence — that would require a live cross-service lookup on every
     *  field of every record write, which RecordValidator deliberately does not do. A malformed
     *  reference like "not-a-uuid" is rejected here; a well-formed but dangling UUID is not
     *  caught until whatever consumes the reference (workflow/search/UI) resolves it. */
    private void validateReference(FieldSchemaDto field, Object value, List<String> errors) {
        if (!(value instanceof String str)) {
            errors.add(field.name() + ": must be a record id (string)");
            return;
        }
        try {
            UUID.fromString(str);
        } catch (IllegalArgumentException e) {
            errors.add(field.name() + ": must be a valid UUID");
        }
    }

    /**
     * FILE/IMAGE values are server-owned, so by the time validation runs the value here is the
     * stored key {@code RecordFileController} wrote after verifying the uploaded bytes —
     * {@link #preserveServerOwnedFields} has already replaced whatever the client sent.
     *
     * <p>Nothing further to check, then: the key was produced by this service, and its bytes
     * were sniffed at confirm. Only the {@code required} check above still applies, and it is
     * the meaningful one — it asks whether an upload happened.
     */
    private void validateFileOrImage(FieldSchemaDto field, Object value, List<String> errors) {
        if (!(value instanceof String str) || str.isBlank()) {
            errors.add(field.name() + ": must reference an uploaded file");
        }
    }

    /**
     * A gallery's items are server-owned in the same way a FILE value is, so the shape is
     * already established by the time this runs — {@code RecordGalleryController} wrote it.
     *
     * <p>What is worth checking here is the count, because that is a statement about the
     * finished listing rather than about any one upload. {@code minCount} is the control an
     * admin reaches for to hold listing quality up ("a venue needs five photos before it can be
     * submitted"), and it can only be enforced where the whole record is in view.
     */
    private void validateGallery(FieldSchemaDto field, Object value, List<String> errors) {
        if (!(value instanceof List<?> items)) {
            errors.add(field.name() + ": must be a list of gallery items");
            return;
        }
        int min = MediaFieldRules.minCount(field);
        if (items.size() < min) {
            errors.add(field.name() + ": needs at least " + min + " photo(s), has " + items.size());
        }
        int max = MediaFieldRules.maxCount(field);
        if (items.size() > max) {
            errors.add(field.name() + ": holds " + items.size() + " photo(s), maximum is " + max);
        }
    }

    /** Matches search-service's IndexMappingBuilder, which indexes CURRENCY as a plain double —
     *  this is a bare numeric amount, not a {amount, currencyCode} structure (no such convention
     *  exists anywhere else in the platform to validate against). */
    private void validateCurrency(FieldSchemaDto field, Object value, List<String> errors) {
        if (!(value instanceof Number)) {
            errors.add(field.name() + ": must be a number");
        }
    }

    /** GEOLOCATION/ADDRESS have no established field-shape convention anywhere else in the
     *  platform (frontend falls back to a plain text input for both). Enforcing a specific set
     *  of sub-keys here would be inventing a contract nothing else agrees to, so this only
     *  checks that the value is object-shaped rather than a bare scalar. */
    private void validateStructured(FieldSchemaDto field, Object value, List<String> errors) {
        if (!(value instanceof Map)) {
            errors.add(field.name() + ": must be an object");
        }
    }

    /**
     * Shallow structural check for ARRAY_OF_OBJECTS fields: value must be a List of Maps, and
     * each item is checked against field.itemSchema() entries (keys: "name", "type", optional
     * "required"). Nested ARRAY_OF_OBJECTS-within-items is not supported — one level deep only.
     */
    private void validateArrayOfObjects(FieldSchemaDto field, Object value, List<String> errors) {
        if (!(value instanceof List<?> items)) {
            errors.add(field.name() + ": must be an array");
            return;
        }
        List<Map<String, Object>> itemSchema = field.itemSchema();
        if (itemSchema == null || itemSchema.isEmpty()) return;

        for (int i = 0; i < items.size(); i++) {
            Object item = items.get(i);
            if (!(item instanceof Map<?, ?> itemMap)) {
                errors.add(field.name() + "[" + i + "]: must be an object");
                continue;
            }
            for (Map<String, Object> subField : itemSchema) {
                String subName = String.valueOf(subField.get("name"));
                Object subValue = itemMap.get(subName);
                boolean subRequired = Boolean.TRUE.equals(subField.get("required"));
                if (subRequired && (subValue == null || isBlank(subValue))) {
                    errors.add(field.name() + "[" + i + "]." + subName + ": field is required");
                    continue;
                }
                if (subValue == null) continue;

                Object subType = subField.get("type");
                if (subType != null) {
                    validateSubFieldType(field.name() + "[" + i + "]." + subName, String.valueOf(subType), subValue, errors);
                }
            }
        }
    }

    private void validateSubFieldType(String path, String type, Object value, List<String> errors) {
        switch (type) {
            case "NUMBER", "DECIMAL" -> {
                if (!(value instanceof Number)) errors.add(path + ": must be a number");
            }
            case "BOOLEAN" -> {
                if (!(value instanceof Boolean)) errors.add(path + ": must be true or false");
            }
            case "TEXT", "LONG_TEXT" -> {
                if (!(value instanceof String)) errors.add(path + ": must be a string");
            }
            default -> {} // other types checked loosely / client-side, consistent with top-level validateByType
        }
    }

    private boolean isBlank(Object value) {
        // A required MULTI_SELECT/ARRAY_OF_OBJECTS field previously passed the required check
        // with an empty [] — only String was ever considered "blank".
        if (value instanceof String str) return str.isBlank();
        if (value instanceof java.util.Collection<?> coll) return coll.isEmpty();
        if (value instanceof Map<?, ?> map) return map.isEmpty();
        return false;
    }
}
