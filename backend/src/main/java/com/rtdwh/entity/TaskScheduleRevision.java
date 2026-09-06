package com.rtdwh.entity;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;
@Data @Builder @NoArgsConstructor @AllArgsConstructor @Entity
@Table(name="task_schedule_revision", uniqueConstraints=@UniqueConstraint(columnNames={"task_id","revision_no"}))
public class TaskScheduleRevision {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    private Long taskId;
    private Long scheduleId;
    private Integer revisionNo;
    @Column(length=128) private String cronExpression;
    @Column(length=64) private String timezone;
    private Integer businessDateOffset;
    @Column(columnDefinition="JSON") private String parametersJson;
    private Boolean enabled;
    @Column(length=16) private String action;
    private Long createdBy;
    private LocalDateTime createdAt;
}
