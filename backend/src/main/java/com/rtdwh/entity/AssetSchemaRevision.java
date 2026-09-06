package com.rtdwh.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity @Table(name = "asset_schema_revision", uniqueConstraints = @UniqueConstraint(
        name = "uk_asset_schema_revision", columnNames = {"table_meta_id", "revision_no"}))
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class AssetSchemaRevision {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false) private Long tableMetaId;
    @Column(nullable = false) private Integer revisionNo;
    @Column(nullable = false, length = 16) private String severity;
    @Column(nullable = false, length = 32) private String evidenceSource;
    @Column(nullable = false, length = 64) private String fingerprint;
    @Column(columnDefinition = "JSON") private String beforeSchema;
    @Column(nullable = false, columnDefinition = "JSON") private String afterSchema;
    @Column(nullable = false, columnDefinition = "JSON") private String changesJson;
    @Column(nullable = false) private LocalDateTime observedAt;
}
