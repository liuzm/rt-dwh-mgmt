package com.rtdwh.entity;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;
@Data @Builder @NoArgsConstructor @AllArgsConstructor @Entity @Table(name="task_access_check")
public class TaskAccessCheck {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    private Long taskId;
    private Long definitionVersionId;
    private Long instanceId;
    private Long actorId;
    @Column(length=32) private String action;
    private Boolean allowed;
    @Column(length=256) private String reason;
    private LocalDateTime checkedAt;
}
