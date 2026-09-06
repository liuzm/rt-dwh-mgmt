package com.rtdwh.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "sync_task")
public class SyncTask {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long creatorId;

    @Column(nullable = false, length = 128)
    private String taskName;

    @Column(length = 512)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TaskType taskType;

    /** Product-level scenario identity, decoupled from the Flink execution adapter. */
    @Column(nullable = false, length = 64)
    private String scenarioCode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ExecutionMode executionMode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private DefinitionStatus definitionStatus;

    private Long publishedVersionId;

    private Long activeDeploymentId;

    @Column(columnDefinition = "JSON")
    private String parameterSchemaJson;

    /** Stored only within immutable definition JSON, not the mutable task row. */
    @Transient private String runtimeConfigJson;
    @Transient private String runtimeConfigHash;

    private Long sourceConfigId;

    private Long targetConfigId;

    @Column(columnDefinition = "TEXT")
    private String flinkSql;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private SyncStrategy syncStrategy;

    @Column(columnDefinition = "JSON")
    private String tableMappings;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private TaskStatus status;

    @Column(length = 64)
    private String flinkJobId;

    /** Flink jar ID on the cluster (after upload) */
    @Column(length = 128)
    private String flinkJarId;

    /** Savepoint trigger ID for async savepoint operations */
    @Column(length = 64)
    private String savepointTriggerId;

    @Column(columnDefinition = "JSON")
    private String checkpointInfo;

    private Long currentLagMs;

    private Double throughputQps;

    @Column(columnDefinition = "TEXT")
    private String lastErrorMsg;

    /** Parallelism for the Flink job */
    @Builder.Default
    private Integer parallelism = 1;

    /** Checkpoint interval in milliseconds */
    @Builder.Default
    private Long checkpointIntervalMs = 60000L;

    /** When the task was submitted to Flink cluster */
    private LocalDateTime submittedAt;

    /** Last checkpoint completed timestamp */
    private LocalDateTime lastCheckpointTime;

    /** Number of completed checkpoints */
    @Builder.Default
    private Long checkpointCount = 0L;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;

    @Version
    private Long version;

    public enum TaskType {
        cdc_sync, etl, materialized
    }

    public enum ExecutionMode {
        continuous, scheduled
    }

    public enum DefinitionStatus {
        draft, published, disabled
    }

    public enum TaskStatus {
        /** 刚创建，未提交到 Flink */
        draft,
        /** 正在运行 */
        running,
        /** 暂停中（已做 Savepoint） */
        paused,
        /** 任务失败 */
        failed,
        /** 已停止（正常或手动停止） */
        finished,
        /** 正在做 Savepoint（中间状态） */
        saving_point,
        /** 正在提交到 Flink（中间状态） */
        submitting
    }

    public enum SyncStrategy {
        full_then_incremental, incremental_only
    }

    /**
     * Valid state transitions for task lifecycle
     */
    public boolean canTransitionTo(TaskStatus target) {
        switch (this.status) {
            case draft:
                return target == TaskStatus.submitting;
            case submitting:
                return target == TaskStatus.running || target == TaskStatus.failed;
            case running:
                return target == TaskStatus.saving_point || target == TaskStatus.failed || target == TaskStatus.finished;
            case saving_point:
                return target == TaskStatus.paused || target == TaskStatus.running || target == TaskStatus.failed;
            case paused:
                return target == TaskStatus.submitting || target == TaskStatus.finished;
            case failed:
                return target == TaskStatus.submitting || target == TaskStatus.finished;
            case finished:
                return false; // Terminal state
            default:
                return false;
        }
    }
}
