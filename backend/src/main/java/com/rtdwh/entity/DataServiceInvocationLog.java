package com.rtdwh.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import java.time.LocalDateTime;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
@Entity @Table(name = "data_service_invocation_log", indexes = {
        @Index(name = "idx_data_service_log_time", columnList = "service_id,created_at"),
        @Index(name = "idx_data_service_log_app", columnList = "app_id,created_at")
})
public class DataServiceInvocationLog {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    private Long serviceId;
    private Long appId;
    private Long versionId;
    private Integer apiVersion;
    private Long executionUserId;
    @Column(nullable = false, length = 64) private String serviceCode;
    @Column(nullable = false, length = 16) private String status;
    @Column(nullable = false) private Integer httpStatus;
    private Integer rowCount;
    private Long durationMs;
    @Column(length = 64) private String clientIp;
    @Column(columnDefinition = "TEXT") private String errorMessage;
    @CreationTimestamp @Column(updatable = false) private LocalDateTime createdAt;
}
