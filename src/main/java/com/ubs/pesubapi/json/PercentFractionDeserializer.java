package com.ubs.pesubapi.json;

import com.ubs.pesubapi.util.MoneyValues;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

import java.math.BigDecimal;

/**
 * Reads an advance rate into the stored fraction (0.90) from either a JSON number or the
 * percent string ({@code "90%"}, {@code "95.0%"}) that BB snapshots persisted before rates went
 * NUMERIC. Snapshots are historical records that must keep loading — see
 * {@link com.ubs.pesubapi.entity.converter.BbResultConverter}, which already tolerates dropped
 * fields and null-for-primitive from those same older shapes.
 */
public class PercentFractionDeserializer extends ValueDeserializer<BigDecimal> {

    @Override
    public BigDecimal deserialize(JsonParser p, DeserializationContext ctxt) {
        if (p.currentToken() == JsonToken.VALUE_STRING) {
            return MoneyValues.fraction(p.getString());
        }
        return MoneyValues.fraction(p.getDecimalValue());
    }
}
