package com.rtdwh.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.time.LocalDateTime;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
@Entity @Table(name = "task_schedule", uniqueConstraints = @UniqueConstraint(name = "uk_task_schedule_task", columnNames = "task_id"))
public class TaskSchedule {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false) private Long taskId;
    @Column(nullable = false, length = 128) private String cronExpression;
    @Column(nullable = false, length = 64) private String timezone;
    @Builder.Default @Column(nullable = false) private Integer businessDateOffset = -1;
    @Column(columnDefinition = "JSON") private String parametersJson;
    @Builder.Default @Column(nullable = false) private Boolean enabled = true;
    private Instant nextRunAt;
    private Instant lastRunAt;
    private Long activeRevisionId;
    @Column(length=256) private String lastError;
    @Column(nullable = false) private Long createdBy;
    @CreationTimestamp @Column(updatable = false) private LocalDateTime createdAt;
    @UpdateTimestamp private LocalDateTime updatedAt;
}
