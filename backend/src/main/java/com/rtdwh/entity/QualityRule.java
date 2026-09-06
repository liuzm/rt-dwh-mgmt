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
@Table(name = "quality_rule")
public class QualityRule {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String ruleName;

    @Column(nullable = false, length = 50)
    private String ruleType; // null_rate, uniqueness, volume_compare, range_check

    @Column(length = 20)
    private String layer; // ods, dwd, dws, ads — for filtering

    @Column(length = 100)
    private String targetTable;

    @Column(length = 100)
    private String targetColumn;

    @Column(nullable = false)
    private Double threshold; // threshold value for quality check (e.g. null rate > 0.05 → alert)

    @Column(length = 500)
    private String expression; // optional custom expression for range_check

    @Builder.Default
    @Column(nullable = false)
    private Boolean enabled = true;

    @Builder.Default
    @Column(nullable = false, length = 20)
    private String checkScope = "full_table";

    @Column(length = 100)
    private String timeColumn;

    @Builder.Default
    @Column(nullable = false, length = 20)
    private String emptyPolicy = "fail";

    @Version
    @Column(nullable = false)
    private Long version;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
