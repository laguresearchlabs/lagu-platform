package com.lagu.platform.schema.domain;

public enum FieldType {
    TEXT, LONG_TEXT, NUMBER, DECIMAL, BOOLEAN,
    DATE, DATETIME, TIME,
    EMAIL, PHONE, URL,
    ADDRESS, GEOLOCATION, CURRENCY,
    ENUM, MULTI_SELECT,
    FILE, IMAGE,
    ENTITY_REFERENCE, USER_REFERENCE,
    JSON,
    ARRAY_OF_OBJECTS   // nested array; item_schema holds each item's field definitions
}
