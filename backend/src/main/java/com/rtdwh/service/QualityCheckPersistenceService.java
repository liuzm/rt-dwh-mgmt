package com.rtdwh.service;

import com.rtdwh.entity.QualityAlert;
import com.rtdwh.entity.QualityCheckRun;
import com.rtdwh.entity.QualityRule;
import com.rtdwh.repository.QualityAlertRepository;
import com.rtdwh.repository.QualityCheckRunRepository;
import com.rtdwh.repository.QualityRuleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;

@Service
@RequiredArgsConstructor
public class QualityCheckPersistenceService {
    @jakarta.persistence.PersistenceContext
    private jakarta.persistence.EntityManager entityManager;

    private final QualityRuleRepository ruleRepository;
    private final QualityAlertRepository alertRepository;
    private final QualityCheckRunRepository runRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public QualityCheckRun startRun(QualityCheckRun run) {
        QualityCheckRun persisted = runRepository.saveAndFlush(run);
        // HTTP OpenEntityManagerInView can retain this object after commit. The executor
        // must mutate a detached snapshot, or auto-flush changes status before the
        // compare-and-set finalizer's WHERE status = 'running'.
        entityManager.detach(persisted);
        return persisted;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean completeRun(QualityRule ruleSnapshot, QualityCheckRun run,
                               String level, String message) {
        finalizeRun(run);
        QualityRule currentRule = ruleRepository.findByIdForUpdate(ruleSnapshot.getId()).orElse(null);
        if (currentRule == null || isSupersededOrChanged(currentRule, run)) return false;
        if ("passed".equals(run.getStatus())) {
            resolveOutstandingAlertsInternal(currentRule.getId(), run.getScopeKey(), "recovered");
            return false;
        }
        return saveOrRefreshAlertInternal(currentRule, run, run.getActualValue(), run.getThresholdValue(), level, message);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int recoverStaleRuns(LocalDateTime startedBefore) {
        List<QualityCheckRun> staleRuns = runRepository.findByStatusAndStartedAtBefore("running", startedBefore);
        if (staleRuns.isEmpty()) return 0;
        LocalDateTime recoveredAt = LocalDateTime.now();
        List<QualityCheckRun> recoveredRuns = staleRuns.stream()
                .sorted(Comparator.comparing(QualityCheckRun::getRuleId).thenComparing(QualityCheckRun::getId))
                .filter(run -> recoverRun(run, recoveredAt))
                .toList();
        recoveredRuns.stream()
                .forEach(run -> ruleRepository.findByIdForUpdate(run.getRuleId())
                        .filter(rule -> !isSupersededOrChanged(rule, run))
                        .ifPresent(rule -> saveOrRefreshAlertInternal(
                                rule, run, null, run.getThresholdValue(), "error",
                                "质量检查执行失败: 执行进程中断或超时，状态已自动收敛")));
        return recoveredRuns.size();
    }

    private boolean saveOrRefreshAlertInternal(QualityRule rule, QualityCheckRun run, Double actualValue, Double thresholdValue,
                                               String level, String message) {
        List<QualityAlert> openAlerts = alertRepository
                .findByRuleIdAndResolvedFalseOrderByTriggeredAtDesc(rule.getId()).stream()
                .filter(alert -> java.util.Objects.equals(alert.getScopeKey(), run.getScopeKey())).toList();
        boolean isNew = openAlerts.isEmpty();
        QualityAlert alert = isNew
                ? QualityAlert.builder().ruleId(rule.getId()).resolved(false).build()
                : openAlerts.get(0);
        alert.setRuleType(rule.getRuleType());
        alert.setTargetTable(run.getTargetTable());
        alert.setLayer(run.getLayer());
        alert.setScopeKey(run.getScopeKey());
        alert.setWindowStart(run.getWindowStart());
        alert.setWindowEnd(run.getWindowEnd());
        alert.setTargetColumn(rule.getTargetColumn());
        alert.setActualValue(actualValue);
        alert.setThresholdValue(thresholdValue);
        alert.setMessage(message);
        alert.setLevel(level);
        alert.setResolved(false);
        alert.setResolvedAt(null);
        alert.setResolutionReason(null);
        alert.setTriggeredAt(LocalDateTime.now());
        alertRepository.save(alert);

        if (openAlerts.size() > 1) {
            LocalDateTime resolvedAt = LocalDateTime.now();
            List<QualityAlert> duplicates = openAlerts.subList(1, openAlerts.size());
            duplicates.forEach(duplicate -> {
                duplicate.setResolved(true);
                duplicate.setResolvedAt(resolvedAt);
                duplicate.setResolutionReason("suppressed");
            });
            alertRepository.saveAll(duplicates);
        }
        return isNew;
    }

    private void resolveOutstandingAlertsInternal(Long ruleId, String scopeKey, String reason) {
        List<QualityAlert> openAlerts = alertRepository
                .findByRuleIdAndResolvedFalseOrderByTriggeredAtDesc(ruleId).stream()
                .filter(alert -> java.util.Objects.equals(alert.getScopeKey(), scopeKey)).toList();
        if (openAlerts.isEmpty()) return;
        LocalDateTime resolvedAt = LocalDateTime.now();
        openAlerts.forEach(alert -> {
            alert.setResolved(true);
            alert.setResolvedAt(resolvedAt);
            alert.setResolutionReason(reason);
        });
        alertRepository.saveAll(openAlerts);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public QualityAlert resolveAlertManually(Long alertId, Long ruleId) {
        if (ruleId != null) ruleRepository.findByIdForUpdate(ruleId);
        QualityAlert alert = alertRepository.findById(alertId)
                .orElseThrow(() -> new IllegalArgumentException("质量告警不存在: " + alertId));
        alert.setResolved(true);
        alert.setResolvedAt(LocalDateTime.now());
        alert.setResolutionReason("acknowledged");
        return alertRepository.save(alert);
    }

    private boolean isSupersededOrChanged(QualityRule currentRule, QualityCheckRun run) {
        if (!Boolean.TRUE.equals(currentRule.getEnabled())) return true;
        if (run == null || run.getId() == null) throw new IllegalArgumentException("质量检查运行 ID 不能为空");
        if (run.getRuleVersion() == null || currentRule.getVersion() == null
                || !run.getRuleVersion().equals(currentRule.getVersion())) return true;
        return runRepository.existsByRuleIdAndScopeKeyAndIdGreaterThanAndStatusNot(
                currentRule.getId(), run.getScopeKey(), run.getId(), "running");
    }

    private void finalizeRun(QualityCheckRun run) {
        if (run == null || run.getId() == null) throw new IllegalArgumentException("质量检查运行 ID 不能为空");
        int updated = runRepository.finalizeRunningRun(
                run.getId(), run.getStatus(), run.getCheckSql(), run.getActualValue(), run.getCheckedRows(), run.getViolationRows(),
                run.getDurationMs(), run.getErrorMessage(), run.getFinishedAt());
        if (updated != 1) {
            throw new IllegalStateException("质量检查运行记录已被其他执行器收敛: " + run.getId());
        }
    }

    private boolean recoverRun(QualityCheckRun run, LocalDateTime recoveredAt) {
        String error = "执行进程中断或超时，状态已自动收敛";
        long durationMs = Math.max(0L, Duration.between(run.getStartedAt(), recoveredAt).toMillis());
        int updated = runRepository.recoverRunningRun(run.getId(), durationMs, error, recoveredAt);
        if (updated != 1) return false;
        run.setStatus("error");
        run.setErrorMessage(error);
        run.setFinishedAt(recoveredAt);
        run.setDurationMs(durationMs);
        return true;
    }
}
