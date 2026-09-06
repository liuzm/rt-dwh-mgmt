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
@Table(name = "dwh_column_meta")
public class DwhColumnMeta {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long tableMetaId;

    @Column(nullable = false, length = 128)
    private String columnName;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String columnType;

    private Long engineFieldId;

    @Column(length = 512)
    private String businessComment;

    private Boolean isPk;

    private Boolean isNullable;

    @Column(length = 128)
    private String defaultValue;

    @Column(length = 128)
    private String sourceColumn;

    private Integer sortOrder;
}
