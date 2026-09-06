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
@Table(name = "operation_audit", indexes = {
        @Index(name = "idx_audit_user_time", columnList = "username,created_at"),
        @Index(name = "idx_audit_resource_time", columnList = "resource_type,created_at")
})
public class OperationAudit {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long userId;
    @Column(nullable = false, length = 64)
    private String username;
    @Column(nullable = false, length = 16)
    private String httpMethod;
    @Column(nullable = false, length = 256)
    private String requestPath;
    @Column(nullable = false, length = 64)
    private String action;
    @Column(nullable = false, length = 64)
    private String resourceType;
    @Column(length = 128)
    private String resourceId;
    @Column(length = 64)
    private String clientIp;
    @Column(nullable = false)
    private Boolean success;
    private Integer responseStatus;
    private Long durationMs;
    @Column(columnDefinition = "TEXT")
    private String errorMessage;
    @Column(nullable = false)
    private LocalDateTime createdAt;
}
