package com.lagu.platform.common.cache;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.serializer.SerializationException;

import java.io.IOException;

/**
 * Type-aware Jackson JSON serializer for Redis — avoids deprecated Spring Data Redis serializers.
 */
public class JacksonRedisSerializer implements RedisSerializer<Object> {

    private final ObjectMapper mapper;

    public JacksonRedisSerializer() {
        this.mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .activateDefaultTyping(
                        BasicPolymorphicTypeValidator.builder()
                                .allowIfBaseType(Object.class)
                                .build(),
                        // NON_FINAL omits the @class tag for final runtime types (e.g. Java records like
                        // ListingTypeSchemaDto) since it assumes the declared field type already pins the
                        // concrete class. That assumption doesn't hold here — everything round-trips through
                        // this type-erased Object-typed cache, so EVERYTHING is required to reliably recover
                        // the concrete type on read.
                        ObjectMapper.DefaultTyping.EVERYTHING,
                        JsonTypeInfo.As.PROPERTY
                );
    }

    @Override
    public byte[] serialize(Object value) throws SerializationException {
        if (value == null) return null;
        try {
            return mapper.writeValueAsBytes(value);
        } catch (IOException e) {
            throw new SerializationException("Failed to serialize cache value", e);
        }
    }

    @Override
    public Object deserialize(byte[] bytes) throws SerializationException {
        if (bytes == null || bytes.length == 0) return null;
        try {
            return mapper.readValue(bytes, Object.class);
        } catch (IOException e) {
            throw new SerializationException("Failed to deserialize cache value", e);
        }
    }
}
