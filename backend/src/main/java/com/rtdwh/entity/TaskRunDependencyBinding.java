package com.rtdwh.entity;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
@Data @Builder @NoArgsConstructor @AllArgsConstructor @Entity
@Table(name="task_run_dependency_binding", uniqueConstraints=@UniqueConstraint(columnNames={"instance_id","dependency_id"}))
public class TaskRunDependencyBinding {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    private Long instanceId;
    private Long dependencyId;
    private Long upstreamTaskId;
    private Long upstreamVersionId;
    private Long upstreamInstanceId;
    private Long outputDatasetId;
    private Long productionId;
    @Column(length=20) private String conditionType;
    @Column(length=24) private String bindingPolicy;
    private LocalDate windowStart;
    private LocalDate windowEnd;
    private LocalDateTime boundAt;
}
