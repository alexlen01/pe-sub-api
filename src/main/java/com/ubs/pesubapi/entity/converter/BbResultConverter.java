package com.ubs.pesubapi.entity.converter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ubs.pesubapi.dto.BbResult;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.postgresql.util.PGobject;

@Converter
public class BbResultConverter implements AttributeConverter<BbResult, Object> {

    private static final ObjectMapper mapper = new ObjectMapper();

    @Override
    public Object convertToDatabaseColumn(BbResult result) {
        if (result == null) return null;
        try {
            PGobject pg = new PGobject();
            pg.setType("jsonb");
            pg.setValue(mapper.writeValueAsString(result));
            return pg;
        } catch (Exception e) {
            throw new IllegalArgumentException("Could not serialize BbResult", e);
        }
    }

    @Override
    public BbResult convertToEntityAttribute(Object dbData) {
        if (dbData == null) return null;
        try {
            return mapper.readValue(dbData.toString(), BbResult.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("Could not deserialize BbResult", e);
        }
    }
}
