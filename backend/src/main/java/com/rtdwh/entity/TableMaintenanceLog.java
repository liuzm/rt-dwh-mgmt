package com.rtdwh.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "table_maintenance_log")
public class TableMaintenanceLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long tableMetaId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private Operation operation;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TriggerType triggerType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    @Column(columnDefinition = "JSON")
    private String beforeMetrics;

    @Column(columnDefinition = "JSON")
    private String afterMetrics;

    private Long durationMs;

    @Column(columnDefinition = "TEXT")
    private String errorMsg;

    @Column(length = 64)
    private String operationId;

    @Column(length = 64)
    private String sessionId;

    @Column(length = 64)
    private String flinkJobId;

    @Column(length = 20)
    private String executionPhase;

    @Column(columnDefinition = "TEXT", name = "sql_content")
    private String sqlContent;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime startedAt;

    private LocalDateTime finishedAt;

    @Builder.Default @Column(nullable = false) private Long revision = 0L;
    @com.fasterxml.jackson.annotation.JsonIgnore
    @Column(length = 36) private String coordinationToken;
    @Builder.Default @Column(nullable = false, length = 32) private String contractOrigin = "legacy_unbound";
    @Column(length = 64) private String assetId;
    @Column(length = 128) private String catalogName;
    @Column(length = 128) private String databaseName;
    @Column(length = 128) private String tableName;
    private Long requestedBy;
    @Column(length = 1024) private String gatewayUrl;
    @Column(length = 1024) private String flinkUrl;
    @com.fasterxml.jackson.annotation.JsonIgnore
    @Column(columnDefinition = "JSON") private String environmentJson;
    @Column(length = 128) private String correlationName;
    private LocalDateTime observedAt;
    @Column(length = 32) private String observedState;
    @Builder.Default @Column(nullable = false, length = 24) private String cleanupStatus = "untracked";
    @Builder.Default @Column(nullable = false) private Integer cleanupAttempts = 0;
    private LocalDateTime cleanupNextAt;
    @Column(length = 512) private String cleanupError;
    private LocalDateTime cleanedAt;

    public enum Operation {
        compact, expire_snapshots, orphan_cleanup
    }

    public enum TriggerType {
        manual, scheduled
    }

    public enum Status {
        running, success, failed, pending, unknown, timed_out
    }
}
