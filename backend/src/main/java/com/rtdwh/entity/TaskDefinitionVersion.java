package com.rtdwh.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "task_definition_version", uniqueConstraints = {
        @UniqueConstraint(name = "uk_task_version", columnNames = {"task_id", "version_no"})
})
public class TaskDefinitionVersion {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false)
    private Long taskId;
    @Column(nullable = false)
    private Integer versionNo;
    @Column(nullable = false, length = 256)
    private String changeSummary;
    @Column(nullable = false, columnDefinition = "LONGTEXT")
    private String snapshotJson;
    @Column(columnDefinition = "LONGTEXT")
    private String contractJson;
    @Column(length = 64)
    private String contractHash;

    public String getContractProvenance() {
        return contractJson == null ? "legacy-inferred" : "frozen-v1";
    }
    @Column(nullable = false)
    private Long createdBy;
    @Column(nullable = false)
    private LocalDateTime createdAt;
}
