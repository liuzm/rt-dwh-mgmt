package com.rtdwh.entity;

import com.fasterxml.jackson.annotation.JsonProperty;
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
@Builder(toBuilder = true)
@Entity
@Table(name = "datasource_config")
public class DatasourceConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long creatorId;

    @Column(nullable = false, length = 128)
    private String configName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DbType dbType;

    @Column(nullable = false, length = 128)
    private String host;

    @Column(nullable = false)
    private Integer port;

    @Column(nullable = false, length = 128)
    private String database;

    @Column(nullable = false, length = 64)
    private String username;

    @Column(nullable = false, length = 256)
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String passwordEncrypted;

    @Column(columnDefinition = "JSON")
    private String extraParams;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;

    public enum DbType {
        mysql, postgresql, paimon
    }
}
