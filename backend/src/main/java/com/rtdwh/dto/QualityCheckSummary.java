package com.rtdwh.dto;

import java.time.LocalDateTime;

/** A stable summary returned after a single-rule or batch quality check. */
public record QualityCheckSummary(
        String batchId,
        int total,
        int passed,
        int failed,
        int errorCount,
        int abnormalCount,
        LocalDateTime startedAt,
        LocalDateTime finishedAt,
        long durationMs
) {
}
