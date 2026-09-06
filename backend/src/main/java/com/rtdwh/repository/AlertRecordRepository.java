package com.rtdwh.repository;

import com.rtdwh.entity.AlertRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface AlertRecordRepository extends JpaRepository<AlertRecord, Long> {

    Optional<AlertRecord> findFirstByRuleIdAndDedupKeyAndResolvedFalseOrderByTriggeredAtDesc(
            Long ruleId, String dedupKey);

    List<AlertRecord> findByRuleIdAndResolvedFalse(Long ruleId);

    List<AlertRecord> findByRuleIdAndResolvedTrueAndRecoveryNotificationStatusIn(
            Long ruleId, List<String> recoveryNotificationStatuses);

    List<AlertRecord> findByResolved(Boolean resolved);
    long countByResolvedFalse();

    List<AlertRecord> findByLevel(String level);

    List<AlertRecord> findByResolvedAndLevel(Boolean resolved, String level);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE AlertRecord r SET r.notificationStatus = 'sending', r.deliveryKind = 'trigger', "
            + "r.deliveryClaimToken = :token, r.deliveryClaimedAt = :now, "
            + "r.deliveryAttemptCount = COALESCE(r.deliveryAttemptCount, 0) + 1 "
            + "WHERE r.id = :id AND r.ruleId = :ruleId AND r.resolved = false "
            + "AND (r.deliveryNextAttemptAt IS NULL OR r.deliveryNextAttemptAt <= :now) "
            + "AND (r.notificationStatus = 'pending' OR (r.notificationStatus = 'sending' "
            + "AND (r.deliveryClaimedAt IS NULL OR r.deliveryClaimedAt < :leaseCutoff))) "
            + "AND (r.deliveryClaimToken IS NULL OR r.deliveryClaimedAt IS NULL "
            + "OR r.deliveryClaimedAt < :leaseCutoff)")
    int claimTriggerDelivery(@Param("id") Long id,
                             @Param("ruleId") Long ruleId,
                             @Param("token") String token,
                             @Param("now") LocalDateTime now,
                             @Param("leaseCutoff") LocalDateTime leaseCutoff);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE AlertRecord r SET r.recoveryNotificationStatus = 'sending', "
            + "r.notificationStatus = CASE WHEN r.notificationStatus = 'sending' "
            + "THEN 'skipped' ELSE r.notificationStatus END, r.deliveryKind = 'recovery', "
            + "r.deliveryClaimToken = :token, r.deliveryClaimedAt = :now, "
            + "r.deliveryAttemptCount = COALESCE(r.deliveryAttemptCount, 0) + 1 "
            + "WHERE r.id = :id AND r.ruleId = :ruleId AND r.resolved = true "
            + "AND (r.deliveryNextAttemptAt IS NULL OR r.deliveryNextAttemptAt <= :now) "
            + "AND (r.recoveryNotificationStatus = 'pending' OR (r.recoveryNotificationStatus = 'sending' "
            + "AND (r.deliveryClaimedAt IS NULL OR r.deliveryClaimedAt < :leaseCutoff))) "
            + "AND (r.deliveryClaimToken IS NULL OR r.deliveryClaimedAt IS NULL "
            + "OR r.deliveryClaimedAt < :leaseCutoff)")
    int claimRecoveryDelivery(@Param("id") Long id,
                              @Param("ruleId") Long ruleId,
                              @Param("token") String token,
                              @Param("now") LocalDateTime now,
                              @Param("leaseCutoff") LocalDateTime leaseCutoff);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE AlertRecord r SET r.notificationStatus = :status, "
            + "r.recoveryNotificationStatus = :recoveryStatus, r.deliveryKind = NULL, "
            + "r.deliveryClaimToken = NULL, r.deliveryClaimedAt = NULL, "
            + "r.deliveryNextAttemptAt = :nextAttemptAt, r.deliveryLastError = :lastError "
            + "WHERE r.id = :id AND r.ruleId = :ruleId AND r.notificationStatus = 'sending' "
            + "AND r.deliveryKind = 'trigger' AND r.deliveryClaimToken = :token")
    int finishTriggerDelivery(@Param("id") Long id,
                              @Param("ruleId") Long ruleId,
                              @Param("token") String token,
                              @Param("status") String status,
                              @Param("recoveryStatus") String recoveryStatus,
                              @Param("nextAttemptAt") LocalDateTime nextAttemptAt,
                              @Param("lastError") String lastError);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE AlertRecord r SET r.recoveryNotificationStatus = :status, r.deliveryKind = NULL, "
            + "r.deliveryClaimToken = NULL, r.deliveryClaimedAt = NULL, "
            + "r.deliveryNextAttemptAt = :nextAttemptAt, r.deliveryLastError = :lastError "
            + "WHERE r.id = :id AND r.ruleId = :ruleId AND r.recoveryNotificationStatus = 'sending' "
            + "AND r.deliveryKind = 'recovery' AND r.deliveryClaimToken = :token")
    int finishRecoveryDelivery(@Param("id") Long id,
                               @Param("ruleId") Long ruleId,
                               @Param("token") String token,
                               @Param("status") String status,
                               @Param("nextAttemptAt") LocalDateTime nextAttemptAt,
                               @Param("lastError") String lastError);

    @Query("SELECT r FROM AlertRecord r WHERE " +
           "(:level IS NULL OR r.level = :level) AND " +
           "(:resolved IS NULL OR r.resolved = :resolved) AND " +
           "(:ruleType IS NULL OR r.ruleType = :ruleType) " +
           "ORDER BY r.triggeredAt DESC")
    List<AlertRecord> searchRecords(@Param("level") String level,
                                     @Param("resolved") Boolean resolved,
                                     @Param("ruleType") String ruleType);
}
