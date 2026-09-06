package com.rtdwh.entity;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;
@Data @Builder @NoArgsConstructor @AllArgsConstructor @Entity @Table(name="dataset_production_check")
public class DatasetProductionCheck {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    private Long productionId;
    @Column(length=64) private String qualityBatchId;
    @Column(length=16) private String status;
    @Column(length=256) private String reason;
    private LocalDateTime checkedAt;
}
