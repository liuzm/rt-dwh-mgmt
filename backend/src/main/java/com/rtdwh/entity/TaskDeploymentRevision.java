package com.rtdwh.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
@Entity @Table(name = "task_deployment_revision")
public class TaskDeploymentRevision {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false) private Long taskId;
    @Column(nullable = false) private Long definitionVersionId;
    @Column(nullable = false) private Long requestedBy;
    @Column(nullable = false, length = 20) private String actionType;
    @Column(nullable = false, length = 20) private String status;
    @Column(length = 64) private String flinkJobId;
    @Column(length = 64) private String contractHash;
    @Column(length = 2048) private String restorePath;
    @Column(length = 512) private String errorMessage;
    private Integer desiredParallelism;
    @Column(nullable = false) private LocalDateTime createdAt;
    private LocalDateTime observedAt;
}
