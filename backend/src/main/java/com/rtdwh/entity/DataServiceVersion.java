package com.rtdwh.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Immutable;
import java.time.LocalDateTime;

/** Append-only publication evidence. No update endpoint exists. */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
@Entity @Immutable @Table(name = "data_service_version")
public class DataServiceVersion {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false) private Long serviceId;
    @Column(nullable = false) private Integer versionNo;
    @Column(nullable = false, length = 64) private String serviceCode;
    @Column(nullable = false, length = 128) private String serviceName;
    @Column(length = 512) private String description;
    @Column(nullable = false) private Long creatorId;
    @Column(nullable = false, columnDefinition = "TEXT") private String sqlTemplate;
    @Column(columnDefinition = "JSON") private String parameterConfig;
    @Column(nullable = false, length = 128) private String catalogName;
    @Column(nullable = false, length = 64) private String databaseName;
    @Column(nullable = false) private Integer maxRows;
    @Column(nullable = false) private Integer timeoutSeconds;
    @Column(nullable = false) private Integer rateLimitPerMinute;
    @Column(columnDefinition = "JSON") private String resultColumnsJson;
    @Column(columnDefinition = "JSON") private String dependenciesJson;
    @Column(nullable = false) private Long sourceRevision;
    @Column(nullable = false, length = 32) private String origin;
    private Long sourceVersionId;
    private Long publishedBy;
    @Column(length = 512) private String changeSummary;
    @Column(nullable = false) private LocalDateTime createdAt;
}
