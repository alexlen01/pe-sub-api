package com.ubs.pesubapi.entity.converter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ubs.pesubapi.dto.BbResult;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter
public class BbResultConverter implements AttributeConverter<BbResult, String> {

    private static final ObjectMapper mapper = new ObjectMapper();

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
