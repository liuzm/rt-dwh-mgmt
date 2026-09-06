package com.rtdwh.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "dwh_table_meta", uniqueConstraints = {
    @UniqueConstraint(name = "uk_catalog_db_table", columnNames = {"catalog_name", "paimon_db", "paimon_table"})
})
public class DwhTableMeta {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 36, updatable = false)
    private String assetId;
    @Column(length = 128) private String catalogName;
    @Builder.Default @Column(nullable = false, length = 32) private String assetType = "paimon_table";
    @Builder.Default @Column(nullable = false, length = 16) private String discoveryStatus = "unverified";
    @Builder.Default @Column(nullable = false, length = 16) private String schemaStatus = "unknown";
    private LocalDateTime lastSeenAt;
    private LocalDateTime schemaObservedAt;

    @PrePersist
    void initializeAsset() {
        if (assetId == null) assetId = java.util.UUID.randomUUID().toString();
        if (assetType == null) assetType = "paimon_table";
        if (discoveryStatus == null) discoveryStatus = "unverified";
        if (schemaStatus == null) schemaStatus = "unknown";
        if (sensitivityLevel == null) sensitivityLevel = "internal";
        if (lifecycleStatus == null) lifecycleStatus = "active";
    }

    @Column(name = "paimon_db", nullable = false, length = 64)
    private String paimonDb;

    @Column(name = "paimon_table", nullable = false, length = 128)
    private String paimonTable;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    private TableLayer layer;

    @Column(columnDefinition = "TEXT")
    private String businessDesc;

    @Column(length = 64)
    private String owner;

    @Column(length = 64)
    private String businessDomain;

    @Column(columnDefinition = "JSON")
    private String tags;

    @Builder.Default
    @Column(nullable = false, length = 16)
    private String sensitivityLevel = "internal";

    @Builder.Default
    @Column(nullable = false, length = 16)
    private String lifecycleStatus = "active";

    @Column(columnDefinition = "JSON")
    private String schemaJson;

    @Column(length = 256)
    private String partitionKeys;

    @Column(length = 256)
    private String primaryKeys;

    private Integer snapshotCount;

    private Long latestSnapshotId;

    private LocalDateTime latestCommitTime;

    private Integer fileCount;

    private Long totalSizeBytes;

    private Long recordCount;

    @Version
    private Long version;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;

    public enum TableLayer {
        ods, dwd, dws, ads
    }
}
