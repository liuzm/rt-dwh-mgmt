package com.rtdwh.repository;

import com.rtdwh.entity.QualityAlert;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface QualityAlertRepository extends JpaRepository<QualityAlert, Long> {

    List<QualityAlert> findByResolved(Boolean resolved);
    long countByResolvedFalse();

    List<QualityAlert> findByResolvedFalseOrderByTriggeredAtDesc();

    List<QualityAlert> findByRuleIdAndResolvedFalseOrderByTriggeredAtDesc(Long ruleId);

    List<QualityAlert> findByLevel(String level);

    List<QualityAlert> findByResolvedAndLevel(Boolean resolved, String level);

    List<QualityAlert> findByRuleType(String ruleType);

    @Query("SELECT a FROM QualityAlert a WHERE " +
           "(:level IS NULL OR a.level = :level) AND " +
           "(:resolved IS NULL OR a.resolved = :resolved) AND " +
           "(:ruleType IS NULL OR a.ruleType = :ruleType) " +
           "ORDER BY a.triggeredAt DESC")
    List<QualityAlert> searchAlerts(@Param("level") String level,
                                     @Param("resolved") Boolean resolved,
                                     @Param("ruleType") String ruleType);
}
