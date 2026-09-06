package com.rtdwh.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "role_data_scope")
public class RoleDataScope {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false) private Long roleId;
    @Column(nullable = false, length = 128) private String catalogPattern;
    @Column(nullable = false, length = 128) private String databasePattern;
    @Column(nullable = false, length = 128) private String tablePattern;
    @CreationTimestamp @Column(updatable = false) private LocalDateTime createdAt;
}
