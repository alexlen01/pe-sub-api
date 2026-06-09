package com.ubs.pesubapi.dto;

import com.ubs.pesubapi.entity.FmBlocklistEntry;

public record FmBlocklistEntryDto(Integer id, String qualifier, String reason) {
    public static FmBlocklistEntryDto from(FmBlocklistEntry e) {
        return new FmBlocklistEntryDto(e.getId(), e.getQualifier(), e.getReason());
    }
}
