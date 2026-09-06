package com.rtdwh.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
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
@Table(name = "report_run", indexes = {
        @Index(name = "idx_report_run_report_time", columnList = "report_id,started_at"),
        @Index(name = "idx_report_run_status_time", columnList = "status,started_at")
})
public class ReportRun {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false)
    private Long reportId;
    @Column(nullable = false, length = 20)
    private String triggerType;
    @Column(nullable = false, length = 20)
    private String status;
    private LocalDateTime scheduledAt;
    @Column(nullable = false)
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private Long durationMs;
    private Integer rowCount;
    private Integer attemptCount;
    @JsonIgnore
    @Column(columnDefinition = "LONGTEXT")
    private String resultJson;
    @Column(columnDefinition = "TEXT")
    private String errorMessage;
    @Column(length = 20)
    private String deliveryStatus;
    @Column(columnDefinition = "TEXT")
    private String deliveryError;
    @Column(nullable = false)
    private Long executedBy;
}
