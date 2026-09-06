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
@Table(name = "task_dependency", uniqueConstraints = {
        @UniqueConstraint(name = "uk_task_dependency", columnNames = {"upstream_task_id", "downstream_task_id"})
})
public class TaskDependency {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false)
    private Long upstreamTaskId;
    @Column(nullable = false)
    private Long downstreamTaskId;
    @Column(nullable = false, length = 20)
    private String conditionType;
    private Long outputDatasetId;
    @Column(nullable = false)
    private LocalDateTime createdAt;
    @Column(nullable = false)
    private Long createdBy;
}
