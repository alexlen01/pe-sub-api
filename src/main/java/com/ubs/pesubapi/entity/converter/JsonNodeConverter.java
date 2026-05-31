package com.ubs.pesubapi.entity.converter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.postgresql.util.PGobject;

@Converter
public class JsonNodeConverter implements AttributeConverter<JsonNode, Object> {

    private static final ObjectMapper mapper = new ObjectMapper();

    @Override
    public Object convertToDatabaseColumn(JsonNode node) {
        if (node == null) return null;
        try {
            PGobject pg = new PGobject();
            pg.setType("jsonb");
            pg.setValue(mapper.writeValueAsString(node));
            return pg;
        } catch (Exception e) {
            throw new IllegalArgumentException("Could not serialize JsonNode", e);
        }
    }

    @Override
    public JsonNode convertToEntityAttribute(Object dbData) {
        if (dbData == null) return null;
        try {
            return mapper.readTree(dbData.toString());
        } catch (Exception e) {
            throw new IllegalArgumentException("Could not deserialize JsonNode", e);
        }
    }
}
