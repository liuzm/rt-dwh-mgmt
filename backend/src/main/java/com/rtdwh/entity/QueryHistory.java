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
@Table(name = "query_history")
public class QueryHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String sqlText;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private QueryType queryType;

    @Builder.Default
    @Column(columnDefinition = "VARCHAR(16) NOT NULL DEFAULT 'doris'")
    private String queryEngine = "doris";

    @Column(length = 128)
    private String queryId;

    @Column(length = 128)
    private String traceId;

    private Long scannedRows;

    private Long scannedBytes;

    private Long cpuMs;

    private Long peakMemoryBytes;

    private Long localScanBytes;

    private Long remoteScanBytes;

    private Long cacheWriteBytes;

    private Long queueWaitMs;

    private Double costScore;

    @Builder.Default
    @Column(nullable = false)
    private Boolean budgetExceeded = false;

    @Column(length = 512)
    private String budgetReason;

    private Integer resultRowCount;

    private Long durationMs;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private QueryStatus status;

    @Column(columnDefinition = "TEXT")
    private String errorMsg;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    public enum QueryType {
        adhoc, report, data_service
    }

    public enum QueryStatus {
        running, success, failed, cancelled
    }
}
