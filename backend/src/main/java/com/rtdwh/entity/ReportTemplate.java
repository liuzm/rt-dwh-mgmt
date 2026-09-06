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
@Table(name = "report_template")
public class ReportTemplate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long creatorId;

    @Column(nullable = false, length = 128)
    private String reportName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ReportType reportType;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String sqlQuery;

    @Column(columnDefinition = "JSON")
    private String chartConfig;

    @Column(columnDefinition = "JSON")
    private String filterConfig;

    @Column(columnDefinition = "JSON")
    private String scheduleConfig;

    @Builder.Default
    @Column(nullable = false)
    private Boolean scheduleEnabled = false;

    private LocalDateTime nextRunAt;

    private LocalDateTime lastRunAt;

    @Column(nullable = false)
    private Boolean isPublished;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;

    public enum ReportType {
        line, bar, pie, table, mixed
    }
}
