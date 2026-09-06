package com.rtdwh.dto;

import com.rtdwh.entity.QualityCheckRun;

import java.time.LocalDate;
import java.util.List;

public record QualityOverviewSummary(
        List<QualityCheckRun> latestRuns,
        List<DailyRunSummary> dailyRuns,
        long last24hRuns,
        long averageDurationMs
) {
    public record DailyRunSummary(LocalDate date, long total, long passed, long abnormal) {
    }
}
