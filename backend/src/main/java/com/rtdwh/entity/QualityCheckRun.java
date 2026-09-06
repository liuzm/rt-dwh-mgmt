package com.rtdwh.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "quality_check_run", indexes = {
        @Index(name = "idx_quality_run_batch", columnList = "batch_id"),
        @Index(name = "idx_quality_run_rule_time", columnList = "rule_id,started_at")
})
public class QualityCheckRun {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "batch_id", nullable = false, length = 64)
    private String batchId;
    @Column(name = "rule_id", nullable = false)
    private Long ruleId;
    @Column(name = "rule_name", nullable = false, length = 100)
    private String ruleName;
    @Column(name = "rule_type", length = 50)
    private String ruleType;
    @Column(name = "target_table", length = 255)
    private String targetTable;
    @Column(length = 20)
    private String layer;
    @Builder.Default
    @Column(nullable = false, length = 128)
    private String scopeKey = "full_table";
    private LocalDateTime windowStart;
    private LocalDateTime windowEnd;
    @Column(name = "target_column", length = 100)
    private String targetColumn;
    @Column(name = "rule_version")
    private Long ruleVersion;
    @Column(nullable = false, length = 20)
    private String triggerType;
    @Column(nullable = false, length = 20)
    private String engine;
    @Column(nullable = false, length = 20)
    private String status;
    @Column(columnDefinition = "TEXT", nullable = false)
    private String checkSql;
    private Double actualValue;
    private Double thresholdValue;
    private Long durationMs;
    private Long checkedRows;
    private Long violationRows;
    @Column(length = 100)
    private String timeColumn;
    @Column(length = 20)
    private String emptyPolicy;
    @Column(columnDefinition = "TEXT")
    private String errorMessage;
    @Column(nullable = false)
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
}
