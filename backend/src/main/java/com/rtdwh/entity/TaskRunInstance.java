package com.rtdwh.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "task_run_instance", uniqueConstraints = {
        @UniqueConstraint(name = "uk_task_run_batch_date", columnNames = {"task_id", "batch_id", "business_date"})
}, indexes = {
        @Index(name = "idx_task_run_status_time", columnList = "status,created_at"),
        @Index(name = "idx_task_run_retry_time", columnList = "status,next_retry_at"),
        @Index(name = "idx_task_run_lease", columnList = "status,lease_expires_at"),
        @Index(name = "idx_task_run_task_date", columnList = "task_id,business_date")
})
public class TaskRunInstance {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false)
    private Long taskId;
    private Long definitionVersionId;
    private Long activeAttemptId;
    @Builder.Default private Integer attemptCount = 0;
    @Column(length=16) @Builder.Default private String deliveryStatus = "pending";
    @Column(length=256) private String deliveryError;
    private LocalDate windowStart;
    private LocalDate windowEnd;
    @Column(length=24) private String bindingPolicy;
    private Long scheduleRevisionId;
    private java.time.Instant scheduledAt;
    private LocalDateTime accessCheckedAt;
    @Column(nullable = false, length = 64)
    private String batchId;
    @Column(nullable = false)
    private LocalDate businessDate;
    @Column(nullable = false, length = 20)
    private String triggerType;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RunStatus status;
    @Column(columnDefinition = "JSON")
    private String parametersJson;
    @Column(length = 64)
    private String executorId;
    @Column(length = 64)
    private String externalJobId;
    @Builder.Default
    @Column(nullable = false)
    private Integer retryCount = 0;
    @Column(columnDefinition = "TEXT")
    private String errorMessage;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private LocalDateTime heartbeatAt;
    private LocalDateTime leaseExpiresAt;
    private LocalDateTime nextRetryAt;
    @Column(nullable = false)
    private Long createdBy;
    @Column(nullable = false)
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public enum RunStatus {
        waiting, queued, running, success, failed, cancelled
    }
}
