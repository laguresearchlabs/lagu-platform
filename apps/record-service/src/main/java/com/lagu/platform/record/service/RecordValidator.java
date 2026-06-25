package com.lagu.platform.record.service;

import com.lagu.platform.common.exception.ValidationException;
import com.lagu.platform.record.client.MetadataClient;
import com.lagu.platform.record.client.MetadataClient.FieldSchemaDto;
import com.lagu.platform.record.client.MetadataClient.ObjectTypeSchemaDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

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
            Object value = data.get(field.name());

            if (field.required() && (value == null || isBlank(value))) {
                errors.add(field.name() + ": field is required");
                continue;
            }
            if (value == null) continue;

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
            case "DATE", "DATETIME", "TIME" -> {} // format validated client-side
            default -> {} // FILE, IMAGE, ENTITY_REFERENCE, USER_REFERENCE, JSON, GEOLOCATION, ADDRESS, CURRENCY
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
            if (rules.containsKey("min") && num < ((Number) rules.get("min")).doubleValue()) {
                errors.add(field.name() + ": must be >= " + rules.get("min"));
            }
            if (rules.containsKey("max") && num > ((Number) rules.get("max")).doubleValue()) {
                errors.add(field.name() + ": must be <= " + rules.get("max"));
            }
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
            if (rules.containsKey("maxLength") && str.length() > ((Number) rules.get("maxLength")).intValue()) {
                errors.add(field.name() + ": exceeds max length of " + rules.get("maxLength"));
            }
            if (rules.containsKey("pattern")) {
                Pattern p = Pattern.compile((String) rules.get("pattern"));
                if (!p.matcher(str).matches()) {
                    errors.add(field.name() + ": does not match required pattern");
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

    private boolean isBlank(Object value) {
        return value instanceof String str && str.isBlank();
    }
}
