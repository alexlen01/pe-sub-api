package com.ubs.pesubapi.dto;

/**
 * Outcome of a bulk seed/ingest call from pe-sub-jobs (facilities, LP Master, LP record seeds).
 * Rows that cannot be applied (blank keys, unresolvable facility/LP references) are counted as
 * skipped rather than failing the whole batch — mirroring the fault-tolerant skip policy the
 * batch jobs used when they wrote to the database directly.
 */
public record IngestSummary(int created, int updated, int skipped) {}
