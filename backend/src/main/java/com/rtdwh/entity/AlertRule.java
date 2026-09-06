package com.rtdwh.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "alert_rule")
public class AlertRule {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String ruleName;

    @Column(nullable = false, length = 50)
    private String ruleType; // task_failure, data_delay, quality_failure

    @Column(columnDefinition = "TEXT")
    private String expression;

    @Column(nullable = false)
    private Boolean enabled = true;

    @Version
    @Column(nullable = false)
    private Long version;

    @Column(length = 20)
    private String notifyChannel; // dingtalk, wecom, email

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
