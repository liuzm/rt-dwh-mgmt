package com.rtdwh.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
@Entity @Table(name = "dataset_production", indexes = {
        @Index(name = "idx_dataset_production_output_time", columnList = "output_dataset_id,produced_at")
})
public class DatasetProduction {
    @Column(length = 36) private String assetId;
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false) private Long outputDatasetId;
    @Column(nullable = false) private Long taskId;
    @Column(nullable = false) private Long instanceId;
    @Column(nullable = false) private LocalDate businessDate;
    private LocalDate windowStart;
    private LocalDate windowEnd;
    private Long definitionVersionId;
    private Long attemptId;
    @Column(unique=true, length=96) private String deliveryKey;
    @Column(length=64) private String qualityBatchId;
    @Column(length=256) private String reason;
    private LocalDateTime checkedAt;
    @Column(nullable = false, length = 16) private String status;
    @Column(nullable = false) private LocalDateTime producedAt;
}
