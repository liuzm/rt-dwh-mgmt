package com.rtdwh.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "alert_record")
public class AlertRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long ruleId;

    @Column(length = 160)
    private String dedupKey;

    @Column(nullable = false, length = 50)
    private String ruleType;

    @Column(columnDefinition = "TEXT")
    private String message;

    @Column(length = 20)
    private String level; // info, warn, error

    @Column(nullable = false)
    private Boolean resolved = false;

    private LocalDateTime resolvedAt;

    @Column(length = 20)
    private String resolutionReason;

    private LocalDateTime recoveredAt;

    private LocalDateTime lastEvaluatedAt;

    @Column(length = 20)
    private String notificationStatus;

    @Column(length = 20)
    private String recoveryNotificationStatus;

    @Column(length = 20)
    private String deliveryKind;

    @Column(length = 64)
    private String deliveryClaimToken;

    private LocalDateTime deliveryClaimedAt;

    @Column(nullable = false)
    private Integer deliveryAttemptCount = 0;

    private LocalDateTime deliveryNextAttemptAt;

    @Column(columnDefinition = "TEXT")
    private String deliveryLastError;

    private LocalDateTime triggeredAt;
}
