package com.rtdwh.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "saved_query", uniqueConstraints = {
        @UniqueConstraint(name = "uk_saved_query_user_name", columnNames = {"user_id", "name"})
})
public class SavedQuery {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false, length = 128)
    private String name;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String sqlText;

    @Column(length = 512)
    private String description;

    @Column(length = 256)
    private String tags;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
