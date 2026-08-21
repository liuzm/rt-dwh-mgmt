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
    @Column(nullable = false, length = 10)
    private QueryType queryType;

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
        adhoc, report
    }

    public enum QueryStatus {
        running, success, failed, cancelled
    }
}
