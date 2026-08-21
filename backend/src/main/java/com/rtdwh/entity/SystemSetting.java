package com.rtdwh.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "sys_setting", uniqueConstraints = {
        @UniqueConstraint(name = "uk_sys_setting_key", columnNames = "setting_key")
})
public class SystemSetting {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "setting_key", nullable = false, length = 128)
    private String settingKey;

    @Column(name = "setting_value", nullable = false, length = 1024)
    private String settingValue;

    @Column(length = 256)
    private String description;

    @Column(length = 64)
    private String updatedBy;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
