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
    @UniqueConstraint(name = "uk_db_table", columnNames = {"paimon_db", "paimon_table"})
})
public class DwhTableMeta {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "paimon_db", nullable = false, length = 64)
    private String paimonDb;

    @Column(name = "paimon_table", nullable = false, length = 128)
    private String paimonTable;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    private TableLayer layer;

    @Column(columnDefinition = "TEXT")
    private String businessDesc;

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
