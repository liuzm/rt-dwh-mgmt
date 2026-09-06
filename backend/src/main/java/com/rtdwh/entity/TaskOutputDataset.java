package com.rtdwh.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
@Entity @Table(name = "task_output_dataset", uniqueConstraints = @UniqueConstraint(
        name = "uk_task_output_name", columnNames = {"task_id", "catalog_name", "database_name", "table_name"}))
public class TaskOutputDataset {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false) private Long taskId;
    @Column(length = 36) private String assetId;
    @Column(nullable = false, length = 128) private String catalogName;
    @Column(nullable = false, length = 64) private String databaseName;
    @Column(nullable = false, length = 128) private String tableName;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 8) private DwhTableMeta.TableLayer layer;
    @Column(length = 64) private String owner;
    @Column(length = 512) private String businessDesc;
    @Builder.Default @Column(nullable = false) private Integer slaMinutes = 1440;
    @Builder.Default @Column(nullable = false) private Boolean qualityGateEnabled = false;
    @Builder.Default @Column(nullable = false) private Boolean enabled = true;
    private LocalDateTime lastProducedAt;
    private Long lastInstanceId;
    @CreationTimestamp @Column(updatable = false) private LocalDateTime createdAt;
    @UpdateTimestamp private LocalDateTime updatedAt;
}
