package com.ubs.pesubapi.dto;

import com.ubs.pesubapi.entity.FmSuggestion;

import java.time.LocalDateTime;

public record FmSuggestionDto(
    Integer       id,
    String        extractedHeader,
    String        canonicalField,
    String        suggestedBy,
    String        source,
    Integer       confidence,
    LocalDateTime createdAt
) {
    public static FmSuggestionDto from(FmSuggestion s) {
        return new FmSuggestionDto(
            s.getId(), s.getExtractedHeader(), s.getCanonicalField(),
            s.getSuggestedBy(), s.getSource(), s.getConfidence(), s.getCreatedAt());
    }
}
