package com.rtdwh.entity;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;
@Data @Builder @NoArgsConstructor @AllArgsConstructor @Entity
@Table(name="task_run_attempt", uniqueConstraints=@UniqueConstraint(columnNames={"instance_id","attempt_no"}))
public class TaskRunAttempt {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    private Long instanceId;
    private Integer attemptNo;
    @Column(length=64) private String executorId;
    @Column(length=64) private String externalJobId;
    @Column(length=24) private String status;
    @Column(length=512) private String errorMessage;
    private LocalDateTime startedAt;
    private LocalDateTime submittedAt;
    private LocalDateTime finishedAt;
}
