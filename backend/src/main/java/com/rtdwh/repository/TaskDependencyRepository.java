package com.rtdwh.repository;

import com.rtdwh.entity.TaskDependency;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TaskDependencyRepository extends JpaRepository<TaskDependency, Long> {
    List<TaskDependency> findAllByOrderByIdAsc();
    List<TaskDependency> findByDownstreamTaskId(Long downstreamTaskId);
    void deleteByUpstreamTaskIdAndDownstreamTaskId(Long upstreamTaskId, Long downstreamTaskId);
    void deleteByUpstreamTaskId(Long upstreamTaskId);
    void deleteByDownstreamTaskId(Long downstreamTaskId);
    boolean existsByUpstreamTaskIdAndDownstreamTaskId(Long upstreamTaskId, Long downstreamTaskId);
}
