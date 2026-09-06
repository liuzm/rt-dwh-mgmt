package com.rtdwh.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "quality_alert")
public class QualityAlert {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 50)
    private String ruleType;

    @Column(length = 255)
    private String targetTable;
    @Column(length = 20)
    private String layer;
    @Builder.Default
    @Column(nullable = false, length = 128)
    private String scopeKey = "full_table";
    private LocalDateTime windowStart;
    private LocalDateTime windowEnd;

    @Column(length = 100)
    private String targetColumn;

    private Double actualValue;

    private Double thresholdValue;

    @Column(columnDefinition = "TEXT")
    private String message;

    @Column(length = 20)
    private String level; // info, warn, error

    private Long ruleId;

    @Builder.Default
    @Column(nullable = false)
    private Boolean resolved = false;

    private LocalDateTime resolvedAt;

    @Column(length = 20)
    private String resolutionReason; // recovered, acknowledged, suppressed

    private LocalDateTime triggeredAt;
}
