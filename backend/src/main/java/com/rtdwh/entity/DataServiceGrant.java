package com.rtdwh.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import java.time.LocalDateTime;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
@Entity @Table(name = "data_service_grant", uniqueConstraints = @UniqueConstraint(
        name = "uk_data_service_grant", columnNames = {"app_id", "service_id"}))
public class DataServiceGrant {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false) private Long appId;
    @Column(nullable = false) private Long serviceId;
    @Column(nullable = false) private Long createdBy;
    @CreationTimestamp @Column(updatable = false) private LocalDateTime createdAt;
}
