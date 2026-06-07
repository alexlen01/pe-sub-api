package com.ubs.pesubapi.entity.converter;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.postgresql.util.PGobject;

import java.util.List;

@Converter
public class StringListConverter implements AttributeConverter<List<String>, Object> {

    private static final ObjectMapper mapper = new ObjectMapper();
    private static final TypeReference<List<String>> TYPE = new TypeReference<>() {};

    @Override
    public Object convertToDatabaseColumn(List<String> list) {
        if (list == null) return null;
        try {
            PGobject pg = new PGobject();
            pg.setType("jsonb");
            pg.setValue(mapper.writeValueAsString(list));
            return pg;
        } catch (Exception e) {
            throw new IllegalArgumentException("Could not serialize List<String> to jsonb", e);
        }
    }

    @Override
    public List<String> convertToEntityAttribute(Object dbData) {
        if (dbData == null) return List.of();
        try {
            return mapper.readValue(dbData.toString(), TYPE);
        } catch (Exception e) {
            throw new IllegalArgumentException("Could not deserialize jsonb to List<String>", e);
        }
    }
}
