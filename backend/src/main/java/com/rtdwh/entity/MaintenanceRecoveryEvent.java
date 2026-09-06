package com.rtdwh.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Immutable;
import java.time.LocalDateTime;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
@Entity @Immutable @Table(name = "maintenance_recovery_event")
public class MaintenanceRecoveryEvent {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false) private Long maintenanceId;
    private Long actorId;
    @Column(nullable = false, length = 40) private String action;
    @Column(length = 1000) private String reason;
    @Column(nullable = false, columnDefinition = "JSON") private String evidenceJson;
    @Column(nullable = false) private LocalDateTime createdAt;
}
