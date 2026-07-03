package com.ubs.pesubapi.dto;

import com.ubs.pesubapi.entity.BbSnapshot;

import java.time.LocalDateTime;

/**
 * API view of a persisted Shadow BB run. Keeps the JPA {@link BbSnapshot} entity off the wire
 * (per the project's never-expose-entities rule) while preserving the exact JSON the UI's
 * {@code BBSnapshot} type consumes: {@code id}, {@code facilityId}, {@code calculatedAt},
 * {@code result}. The internal {@code calculatedBy} FK is intentionally omitted.
 */
public record BbSnapshotDto(
    Integer id,
    Integer facilityId,
    LocalDateTime calculatedAt,
    BbResult result
) {
    public static BbSnapshotDto from(BbSnapshot snapshot) {
        return new BbSnapshotDto(
            snapshot.getId(),
            snapshot.getFacilityId(),
            snapshot.getCalculatedAt(),
            snapshot.getResult()
        );
    }
}
