package com.rtdwh.repository;

import com.rtdwh.entity.QualityCheckRun;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface QualityCheckRunRepository extends JpaRepository<QualityCheckRun, Long> {
    List<QualityCheckRun> findTop100ByOrderByStartedAtDesc();
    List<QualityCheckRun> findTop100ByRuleIdOrderByStartedAtDesc(Long ruleId);

    @Query("SELECT r FROM QualityCheckRun r WHERE r.id IN "
            + "(SELECT MAX(latest.id) FROM QualityCheckRun latest GROUP BY latest.ruleId)")
    List<QualityCheckRun> findLatestRunForEachRule();

    long countByStartedAtGreaterThanEqual(LocalDateTime startedAt);

    @Query("SELECT AVG(r.durationMs) FROM QualityCheckRun r "
            + "WHERE r.durationMs IS NOT NULL AND r.status <> 'running'")
    Double findAverageCompletedDurationMs();

    @Query(value = """
            SELECT DATE(started_at), COUNT(*),
                   SUM(CASE WHEN status = 'passed' THEN 1 ELSE 0 END),
                   SUM(CASE WHEN status IN ('failed', 'error') THEN 1 ELSE 0 END)
            FROM quality_check_run
            WHERE started_at >= :startedAt
            GROUP BY DATE(started_at)
            ORDER BY DATE(started_at)
            """, nativeQuery = true)
    List<Object[]> summarizeDailyRuns(@Param("startedAt") LocalDateTime startedAt);

    List<QualityCheckRun> findByStatusAndStartedAtBefore(String status, LocalDateTime startedAt);

    boolean existsByRuleIdAndScopeKeyAndIdGreaterThanAndStatusNot(Long ruleId, String scopeKey, Long id, String status);

    boolean existsByRuleIdAndIdGreaterThanAndStatusNot(Long ruleId, Long id, String status);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE QualityCheckRun r SET r.status = :status, r.checkSql = :checkSql, "
            + "r.actualValue = :actualValue, r.checkedRows = :checkedRows, r.violationRows = :violationRows, r.durationMs = :durationMs, "
            + "r.errorMessage = :errorMessage, r.finishedAt = :finishedAt "
            + "WHERE r.id = :id AND r.status = 'running'")
    int finalizeRunningRun(@Param("id") Long id,
                           @Param("status") String status,
                           @Param("checkSql") String checkSql,
                           @Param("actualValue") Double actualValue,
                           @Param("checkedRows") Long checkedRows,
                           @Param("violationRows") Long violationRows,
                           @Param("durationMs") Long durationMs,
                           @Param("errorMessage") String errorMessage,
                           @Param("finishedAt") LocalDateTime finishedAt);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE QualityCheckRun r SET r.status = 'error', r.durationMs = :durationMs, "
            + "r.errorMessage = :errorMessage, r.finishedAt = :finishedAt "
            + "WHERE r.id = :id AND r.status = 'running'")
    int recoverRunningRun(@Param("id") Long id,
                          @Param("durationMs") Long durationMs,
                          @Param("errorMessage") String errorMessage,
                          @Param("finishedAt") LocalDateTime finishedAt);
}
