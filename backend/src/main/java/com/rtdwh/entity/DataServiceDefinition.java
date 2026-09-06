package com.rtdwh.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
@Entity @Table(name = "data_service_definition")
public class DataServiceDefinition {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false, unique = true, length = 64) private String serviceCode;
    @Column(nullable = false, length = 128) private String serviceName;
    @Column(length = 512) private String description;
    @Column(nullable = false) private Long creatorId;
    @Column(nullable = false, columnDefinition = "TEXT") private String sqlTemplate;
    @Column(columnDefinition = "JSON") private String parameterConfig;
    @Column(nullable = false, length = 128) private String catalogName;
    @Column(nullable = false, length = 64) private String databaseName;
    @Builder.Default @Column(nullable = false) private Integer maxRows = 1000;
    @Builder.Default @Column(nullable = false) private Integer timeoutSeconds = 30;
    @Builder.Default @Column(nullable = false) private Integer rateLimitPerMinute = 60;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16) private ServiceStatus status;
    @Builder.Default @Column(nullable = false) private Integer apiVersion = 1;
    private LocalDateTime publishedAt;
    private Long publishedVersionId;
    @Builder.Default @Version @Column(nullable = false) private Long revision = 0L;
    @Transient private Boolean hasDraftChanges;
    @Transient private Boolean manageable;
    @CreationTimestamp @Column(updatable = false) private LocalDateTime createdAt;
    @UpdateTimestamp private LocalDateTime updatedAt;
    public enum ServiceStatus { draft, published, offline }
}
