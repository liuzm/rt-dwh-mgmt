package com.rtdwh.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
@Entity @Table(name = "data_service_app")
public class DataServiceApp {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false, length = 128) private String appName;
    @Column(nullable = false, unique = true, length = 64) private String appKey;
    @JsonIgnore @Column(nullable = false, length = 128) private String secretHash;
    @Builder.Default @Column(nullable = false) private Boolean enabled = true;
    private LocalDateTime expiresAt;
    @Column(nullable = false) private Long createdBy;
    @CreationTimestamp @Column(updatable = false) private LocalDateTime createdAt;
    @UpdateTimestamp private LocalDateTime updatedAt;
}
