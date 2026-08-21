package com.rtdwh.repository;

import com.rtdwh.entity.SavedQuery;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SavedQueryRepository extends JpaRepository<SavedQuery, Long> {
    List<SavedQuery> findByUserIdOrderByUpdatedAtDesc(Long userId);
    Optional<SavedQuery> findByIdAndUserId(Long id, Long userId);
    boolean existsByUserIdAndName(Long userId, String name);
    boolean existsByUserIdAndNameAndIdNot(Long userId, String name, Long id);
}
