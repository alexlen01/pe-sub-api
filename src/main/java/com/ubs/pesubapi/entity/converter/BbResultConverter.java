package com.ubs.pesubapi.entity.converter;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import com.ubs.pesubapi.dto.BbResult;

@Converter
public class BbResultConverter implements AttributeConverter<BbResult, String> {

    // Ignore unknown properties so snapshots persisted under an older BbResult/ComputedLpRecord shape
    // (e.g. the removed `rank` field) still deserialize cleanly, and keep Jackson 2's
    // null -> false/0 coercion so historical rows with explicit nulls for today's primitive
    // fields still load (Jackson 3 fails on null-for-primitive by default).
    private static final ObjectMapper mapper = JsonMapper.builder()
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
        .build();

    @Override
    public String convertToDatabaseColumn(BbResult result) {
        if (result == null) return null;
        try {
            return mapper.writeValueAsString(result);
        } catch (Exception e) {
            throw new IllegalArgumentException("Could not serialize BbResult", e);
        }
    }

    @Override
    public BbResult convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) return null;
        try {
            return mapper.readValue(dbData, BbResult.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("Could not deserialize BbResult", e);
        }
    }
}
