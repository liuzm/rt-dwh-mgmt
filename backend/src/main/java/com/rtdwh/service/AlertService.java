package com.rtdwh.service;

import com.rtdwh.entity.AlertRule;
import com.rtdwh.entity.AlertRecord;
import com.rtdwh.repository.AlertRuleRepository;
import com.rtdwh.repository.AlertRecordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AlertService {

    private static final Set<String> RULE_TYPES = Set.of("task_failure", "data_delay", "quality_failure");
    private static final Set<String> CHANNELS = Set.of("dingtalk", "wecom", "email");

    private final AlertRuleRepository ruleRepository;
    private final AlertRecordRepository recordRepository;

    @Transactional(readOnly = true)
    public List<AlertRule> listRules() {
        return ruleRepository.findAll();
    }

    @Transactional
    public AlertRule createRule(AlertRule rule) {
        validateAndNormalize(rule);
        rule.setId(null);
        rule.setVersion(null);
        LocalDateTime now = LocalDateTime.now();
        rule.setCreatedAt(now);
        rule.setUpdatedAt(now);
        return ruleRepository.save(rule);
    }

    @Transactional
    public AlertRule updateRule(Long id, AlertRule rule) {
        Boolean requestedEnabled = rule.getEnabled();
        validateAndNormalize(rule);
        AlertRule existing = ruleRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new IllegalArgumentException("告警规则不存在: " + id));
        Boolean nextEnabled = requestedEnabled == null ? existing.getEnabled() : rule.getEnabled();
        boolean semanticChange = !Objects.equals(existing.getRuleType(), rule.getRuleType())
                || !Objects.equals(existing.getExpression(), rule.getExpression())
                || !Objects.equals(existing.getEnabled(), nextEnabled);
        boolean channelActivated = !Objects.equals(existing.getNotifyChannel(), rule.getNotifyChannel())
                && rule.getNotifyChannel() != null && !rule.getNotifyChannel().isBlank();
        existing.setRuleName(rule.getRuleName());
        existing.setRuleType(rule.getRuleType());
        existing.setExpression(rule.getExpression());
        existing.setEnabled(nextEnabled);
        existing.setNotifyChannel(rule.getNotifyChannel());
        if (semanticChange || !Boolean.TRUE.equals(existing.getEnabled())) {
            suppressOpenRecords(id);
        } else if (channelActivated) {
            requeueSkippedNotifications(id);
        }
        existing.setUpdatedAt(LocalDateTime.now());
        return ruleRepository.save(existing);
    }

    @Transactional
    public void deleteRule(Long id) {
        AlertRule rule = ruleRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new IllegalArgumentException("告警规则不存在: " + id));
        suppressOpenRecords(id);
        ruleRepository.delete(rule);
    }

    @Transactional
    public AlertRule toggleRule(Long id) {
        AlertRule rule = ruleRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new IllegalArgumentException("告警规则不存在: " + id));
        rule.setEnabled(!Boolean.TRUE.equals(rule.getEnabled()));
        if (!Boolean.TRUE.equals(rule.getEnabled())) suppressOpenRecords(id);
        rule.setUpdatedAt(LocalDateTime.now());
        return ruleRepository.save(rule);
    }

    @Transactional(readOnly = true)
    public List<AlertRecord> listRecords(String level, Boolean resolved) {
        return recordRepository.searchRecords(level, resolved, null);
    }

    @Transactional
    public AlertRecord resolveRecord(Long id) {
        AlertRecord snapshot = recordRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("告警记录不存在: " + id));
        if (snapshot.getRuleId() != null) ruleRepository.findByIdForUpdate(snapshot.getRuleId());
        AlertRecord record = recordRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("告警记录不存在: " + id));
        if (Boolean.TRUE.equals(record.getResolved())) return record;
        record.setResolved(true);
        record.setResolvedAt(LocalDateTime.now());
        record.setRecoveredAt(null);
        record.setResolutionReason("acknowledged");
        record.setRecoveryNotificationStatus("skipped");
        cancelDelivery(record);
        return recordRepository.save(record);
    }

    private void suppressOpenRecords(Long ruleId) {
        LocalDateTime now = LocalDateTime.now();
        List<AlertRecord> openRecords = recordRepository.findByRuleIdAndResolvedFalse(ruleId);
        openRecords.forEach(record -> {
            record.setResolved(true);
            record.setResolvedAt(now);
            record.setRecoveredAt(null);
            record.setLastEvaluatedAt(now);
            record.setResolutionReason("suppressed");
            record.setRecoveryNotificationStatus("skipped");
            cancelDelivery(record);
        });
        if (!openRecords.isEmpty()) recordRepository.saveAll(openRecords);
        List<AlertRecord> pendingRecoveries = recordRepository
                .findByRuleIdAndResolvedTrueAndRecoveryNotificationStatusIn(
                        ruleId, List.of("pending", "sending"));
        pendingRecoveries.forEach(record -> {
            record.setRecoveryNotificationStatus("skipped");
            record.setResolutionReason("suppressed");
            if (record.getDeliveryKind() != null) {
                if ("trigger".equals(record.getDeliveryKind())) record.setNotificationStatus("skipped");
                clearDeliveryClaim(record);
            }
        });
        if (!pendingRecoveries.isEmpty()) recordRepository.saveAll(pendingRecoveries);
    }

    private void requeueSkippedNotifications(Long ruleId) {
        List<AlertRecord> openRecords = recordRepository.findByRuleIdAndResolvedFalse(ruleId);
        openRecords.stream()
                .filter(record -> "skipped".equals(record.getNotificationStatus()))
                .forEach(record -> {
                    record.setNotificationStatus("pending");
                    record.setDeliveryAttemptCount(0);
                    record.setDeliveryNextAttemptAt(null);
                    record.setDeliveryLastError(null);
                });
        if (!openRecords.isEmpty()) recordRepository.saveAll(openRecords);
    }

    private void cancelDelivery(AlertRecord record) {
        if (!Set.of("sent", "partial").contains(
                record.getNotificationStatus() == null ? "" : record.getNotificationStatus())) {
            record.setNotificationStatus("skipped");
        }
        record.setRecoveryNotificationStatus("skipped");
        clearDeliveryClaim(record);
    }

    private void clearDeliveryClaim(AlertRecord record) {
        record.setDeliveryKind(null);
        record.setDeliveryClaimToken(null);
        record.setDeliveryClaimedAt(null);
        record.setDeliveryNextAttemptAt(null);
        record.setDeliveryLastError(null);
    }

    private void validateAndNormalize(AlertRule rule) {
        if (rule.getRuleName() == null || rule.getRuleName().isBlank()) {
            throw new IllegalArgumentException("规则名称不能为空");
        }
        rule.setRuleName(rule.getRuleName().trim());
        if (rule.getRuleName().length() > 100) throw new IllegalArgumentException("规则名称不能超过 100 个字符");
        String ruleType = rule.getRuleType() == null ? "" : rule.getRuleType().trim().toLowerCase(Locale.ROOT);
        if (!RULE_TYPES.contains(ruleType)) {
            throw new IllegalArgumentException("不支持的告警类型: " + ruleType);
        }
        rule.setRuleType(ruleType);
        if ("data_delay".equals(ruleType)) {
            String expression = rule.getExpression() == null ? "" : rule.getExpression().trim();
            AlertRuleExpressionParser.delayThresholdMs(expression);
            rule.setExpression(expression);
        } else {
            rule.setExpression(null);
        }
        if (rule.getEnabled() == null) rule.setEnabled(true);
        String normalizedChannels = Arrays.stream(
                        (rule.getNotifyChannel() == null ? "" : rule.getNotifyChannel()).split(","))
                .map(String::trim)
                .map(value -> value.toLowerCase(Locale.ROOT))
                .filter(value -> !value.isEmpty())
                .peek(value -> {
                    if (!CHANNELS.contains(value)) {
                        throw new IllegalArgumentException("不支持的通知渠道: " + value);
                    }
                })
                .distinct()
                .collect(Collectors.joining(","));
        rule.setNotifyChannel(normalizedChannels);
    }
}
