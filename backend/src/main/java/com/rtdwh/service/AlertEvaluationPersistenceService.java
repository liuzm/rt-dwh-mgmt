package com.rtdwh.service;

import com.rtdwh.entity.AlertRecord;
import com.rtdwh.entity.AlertRule;
import com.rtdwh.entity.QualityAlert;
import com.rtdwh.repository.AlertRecordRepository;
import com.rtdwh.repository.AlertRuleRepository;
import com.rtdwh.repository.QualityAlertRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Objects;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AlertEvaluationPersistenceService {
    private static final Duration DELIVERY_LEASE = Duration.ofMinutes(5);

    private final AlertRuleRepository ruleRepository;
    private final AlertRecordRepository recordRepository;
    private final QualityAlertRepository qualityAlertRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public TransitionResult evaluate(Long ruleId, Long expectedVersion, List<Condition> conditions) {
        AlertRule rule = ruleRepository.findByIdForUpdate(ruleId)
                .orElseThrow(() -> new IllegalArgumentException("告警规则不存在: " + ruleId));
        if (!Boolean.TRUE.equals(rule.getEnabled()) || !Objects.equals(rule.getVersion(), expectedVersion)) {
            return new TransitionResult(rule, 0, 0, List.of());
        }

        LocalDateTime now = LocalDateTime.now();
        List<AlertRecord> openRecords = new ArrayList<>(recordRepository.findByRuleIdAndResolvedFalse(ruleId));
        openRecords.sort(Comparator.comparing(AlertRecord::getId,
                Comparator.nullsLast(Comparator.reverseOrder())));

        Map<String, AlertRecord> primaryByKey = new HashMap<>();
        Set<Long> duplicateIds = new HashSet<>();
        for (AlertRecord open : openRecords) {
            AlertRecord primary = primaryByKey.putIfAbsent(open.getDedupKey(), open);
            if (primary != null) {
                open.setResolved(true);
                open.setResolvedAt(now);
                open.setRecoveredAt(now);
                open.setResolutionReason("suppressed");
                open.setLastEvaluatedAt(now);
                cancelDelivery(open);
                recordRepository.save(open);
                if (open.getId() != null) duplicateIds.add(open.getId());
            }
        }

        Set<String> activeKeys = new LinkedHashSet<>();
        List<Delivery> deliveries = new ArrayList<>();
        Set<Long> scheduledRecoveryIds = new HashSet<>();
        int triggered = 0;

        for (Condition condition : conditions) {
            if (!activeKeys.add(condition.key())) continue;
            AlertRecord existing = primaryByKey.get(condition.key());
            if (existing != null) {
                boolean escalated = severityWeight(condition.level()) > severityWeight(existing.getLevel());
                existing.setMessage(condition.message());
                existing.setLevel(condition.level());
                existing.setResolutionReason(null);
                existing.setLastEvaluatedAt(now);
                if (existing.getNotificationStatus() == null) {
                    existing.setNotificationStatus("pending");
                }
                if (escalated) {
                    resetForDelivery(existing, DeliveryKind.TRIGGER);
                    deliveries.add(new Delivery(existing.getId(), DeliveryKind.TRIGGER));
                    triggered++;
                } else if (isDeliveryDue(existing, DeliveryKind.TRIGGER, now)) {
                    deliveries.add(new Delivery(existing.getId(), DeliveryKind.TRIGGER));
                }
                recordRepository.save(existing);
                continue;
            }

            AlertRecord record = new AlertRecord();
            record.setRuleId(rule.getId());
            record.setRuleType(rule.getRuleType());
            record.setDedupKey(condition.key());
            record.setMessage(condition.message());
            record.setLevel(condition.level());
            record.setResolved(false);
            record.setResolutionReason(null);
            record.setTriggeredAt(now);
            record.setLastEvaluatedAt(now);
            record.setNotificationStatus("pending");
            record.setDeliveryAttemptCount(0);
            record = recordRepository.save(record);
            deliveries.add(new Delivery(record.getId(), DeliveryKind.TRIGGER));
            triggered++;
        }

        int recovered = 0;
        for (AlertRecord open : openRecords) {
            if ((open.getId() != null && duplicateIds.contains(open.getId()))
                    || activeKeys.contains(open.getDedupKey())) continue;
            open.setResolved(true);
            open.setResolvedAt(now);
            open.setRecoveredAt(now);
            open.setResolutionReason("recovered");
            open.setLastEvaluatedAt(now);
            if (shouldNotifyRecovery(rule, open)) {
                open.setRecoveryNotificationStatus("pending");
                open.setDeliveryAttemptCount(0);
                if (!"sending".equals(open.getNotificationStatus())) {
                    clearDeliveryClaim(open);
                }
                deliveries.add(new Delivery(open.getId(), DeliveryKind.RECOVERY));
                if (open.getId() != null) scheduledRecoveryIds.add(open.getId());
            } else {
                open.setRecoveryNotificationStatus("skipped");
                if (!Set.of("sent", "partial").contains(
                        open.getNotificationStatus() == null ? "" : open.getNotificationStatus())) {
                    open.setNotificationStatus("skipped");
                    clearDeliveryClaim(open);
                }
            }
            recordRepository.save(open);
            recovered++;
        }

        for (AlertRecord pending : recordRepository
                .findByRuleIdAndResolvedTrueAndRecoveryNotificationStatusIn(
                        ruleId, List.of("pending", "sending"))) {
            if (activeKeys.contains(pending.getDedupKey())) {
                cancelDelivery(pending);
                recordRepository.save(pending);
                continue;
            }
            if (pending.getId() != null && scheduledRecoveryIds.add(pending.getId())
                    && isDeliveryDue(pending, DeliveryKind.RECOVERY, now)) {
                deliveries.add(new Delivery(pending.getId(), DeliveryKind.RECOVERY));
            }
        }
        return new TransitionResult(rule, triggered, recovered, List.copyOf(deliveries));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public DeliveryClaim claimDelivery(Long ruleId, Delivery delivery) {
        AlertRule rule = ruleRepository.findByIdForUpdate(ruleId).orElse(null);
        if (rule == null || !Boolean.TRUE.equals(rule.getEnabled())) return null;
        LocalDateTime now = LocalDateTime.now();
        String token = UUID.randomUUID().toString();
        int updated = delivery.kind() == DeliveryKind.RECOVERY
                ? recordRepository.claimRecoveryDelivery(
                        delivery.recordId(), ruleId, token, now, now.minus(DELIVERY_LEASE))
                : recordRepository.claimTriggerDelivery(
                        delivery.recordId(), ruleId, token, now, now.minus(DELIVERY_LEASE));
        if (updated != 1) return null;
        AlertRecord record = recordRepository.findById(delivery.recordId()).orElse(null);
        if (record == null) return null;
        String message = delivery.kind() == DeliveryKind.RECOVERY
                ? "告警已恢复：" + record.getMessage() : record.getMessage();
        String level = delivery.kind() == DeliveryKind.RECOVERY ? "info" : record.getLevel();
        return new DeliveryClaim(rule, token, message, level,
                record.getDeliveryAttemptCount() == null ? 1 : record.getDeliveryAttemptCount());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean recordDelivery(Long ruleId, Long recordId, DeliveryKind kind, String token,
                                  int attemptCount, AlertNotifyService.AlertDeliveryStatus result,
                                  String errorMessage) {
        ruleRepository.findByIdForUpdate(ruleId);
        AlertRecord record = recordRepository.findById(recordId).orElse(null);
        if (record == null || !Objects.equals(token, record.getDeliveryClaimToken())
                || !kind.name().equalsIgnoreCase(record.getDeliveryKind())) return false;
        String status = switch (result) {
            case SENT -> "sent";
            case PARTIAL -> "partial";
            case SKIPPED -> "skipped";
            case RETRYABLE_FAILURE -> "pending";
        };
        boolean skipRecovery = kind == DeliveryKind.TRIGGER && Boolean.TRUE.equals(record.getResolved())
                && !result.delivered();
        if (skipRecovery) {
            status = "skipped";
        }
        LocalDateTime nextAttemptAt = result == AlertNotifyService.AlertDeliveryStatus.RETRYABLE_FAILURE
                && "pending".equals(status) ? LocalDateTime.now().plusSeconds(retryDelaySeconds(attemptCount)) : null;
        String lastError = result == AlertNotifyService.AlertDeliveryStatus.RETRYABLE_FAILURE
                ? defaultText(errorMessage, "通知渠道返回可重试失败") : null;
        int updated = kind == DeliveryKind.RECOVERY
                ? recordRepository.finishRecoveryDelivery(
                        recordId, ruleId, token, status, nextAttemptAt, lastError)
                : recordRepository.finishTriggerDelivery(
                        recordId, ruleId, token, status,
                        skipRecovery ? "skipped" : record.getRecoveryNotificationStatus(),
                        nextAttemptAt, lastError);
        return updated == 1;
    }

    private boolean shouldNotifyRecovery(AlertRule rule, AlertRecord record) {
        if (!Set.of("sent", "partial", "sending").contains(
                record.getNotificationStatus() == null ? "" : record.getNotificationStatus())) return false;
        if (!"quality_failure".equals(rule.getRuleType())
                || record.getDedupKey() == null || !record.getDedupKey().startsWith("quality:")) return true;
        try {
            Long qualityAlertId = Long.valueOf(record.getDedupKey().substring("quality:".length()));
            QualityAlert qualityAlert = qualityAlertRepository.findById(qualityAlertId).orElse(null);
            return qualityAlert == null || "recovered".equals(qualityAlert.getResolutionReason());
        } catch (NumberFormatException invalidKey) {
            return true;
        }
    }

    private int severityWeight(String level) {
        return switch (level == null ? "" : level.toLowerCase()) {
            case "error", "high", "critical" -> 3;
            case "warn", "medium" -> 2;
            default -> 1;
        };
    }

    private boolean isDeliveryDue(AlertRecord record, DeliveryKind kind, LocalDateTime now) {
        String status = kind == DeliveryKind.RECOVERY
                ? record.getRecoveryNotificationStatus() : record.getNotificationStatus();
        if (record.getDeliveryNextAttemptAt() != null && record.getDeliveryNextAttemptAt().isAfter(now)) return false;
        if ("pending".equals(status)) return record.getDeliveryClaimToken() == null
                || record.getDeliveryClaimedAt() == null
                || record.getDeliveryClaimedAt().isBefore(now.minus(DELIVERY_LEASE));
        return "sending".equals(status) && (record.getDeliveryClaimedAt() == null
                || record.getDeliveryClaimedAt().isBefore(now.minus(DELIVERY_LEASE)));
    }

    private void resetForDelivery(AlertRecord record, DeliveryKind kind) {
        if (kind == DeliveryKind.RECOVERY) record.setRecoveryNotificationStatus("pending");
        else record.setNotificationStatus("pending");
        record.setDeliveryAttemptCount(0);
        clearDeliveryClaim(record);
    }

    private void clearDeliveryClaim(AlertRecord record) {
        record.setDeliveryKind(null);
        record.setDeliveryClaimToken(null);
        record.setDeliveryClaimedAt(null);
        record.setDeliveryNextAttemptAt(null);
        record.setDeliveryLastError(null);
    }

    private void cancelDelivery(AlertRecord record) {
        if (!Set.of("sent", "partial").contains(
                record.getNotificationStatus() == null ? "" : record.getNotificationStatus())) {
            record.setNotificationStatus("skipped");
        }
        record.setRecoveryNotificationStatus("skipped");
        clearDeliveryClaim(record);
    }

    private long retryDelaySeconds(int attemptCount) {
        if (attemptCount <= 1) return 5L;
        if (attemptCount == 2) return 30L;
        if (attemptCount == 3) return 120L;
        return 300L;
    }

    private String defaultText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    public record Condition(String key, String message, String level) {
    }

    public record Delivery(Long recordId, DeliveryKind kind) {
    }

    public record DeliveryClaim(AlertRule rule, String token, String message, String level, int attemptCount) {
    }

    public record TransitionResult(AlertRule rule, int triggered, int recovered, List<Delivery> deliveries) {
    }

    public enum DeliveryKind {
        TRIGGER,
        RECOVERY
    }
}
