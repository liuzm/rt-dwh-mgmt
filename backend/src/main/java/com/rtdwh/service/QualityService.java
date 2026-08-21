package com.rtdwh.service;

import com.rtdwh.entity.QualityRule;
import com.rtdwh.entity.QualityAlert;
import com.rtdwh.repository.QualityRuleRepository;
import com.rtdwh.repository.QualityAlertRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class QualityService {

    private final QualityRuleRepository ruleRepository;
    private final QualityAlertRepository alertRepository;

    @Transactional(readOnly = true)
    public List<QualityRule> listRules(String layer, String ruleType) {
        return ruleRepository.searchRules(layer, ruleType, null);
    }

    @Transactional
    public QualityRule createRule(QualityRule rule) {
        rule.setId(null);
        LocalDateTime now = LocalDateTime.now();
        rule.setCreatedAt(now);
        rule.setUpdatedAt(now);
        return ruleRepository.save(rule);
    }

    @Transactional
    public QualityRule updateRule(Long id, QualityRule input) {
        QualityRule rule = ruleRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("质量规则不存在: " + id));
        rule.setRuleName(input.getRuleName());
        rule.setRuleType(input.getRuleType());
        rule.setLayer(input.getLayer());
        rule.setTargetTable(input.getTargetTable());
        rule.setTargetColumn(input.getTargetColumn());
        rule.setThreshold(input.getThreshold());
        rule.setExpression(input.getExpression());
        if (input.getEnabled() != null) {
            rule.setEnabled(input.getEnabled());
        }
        rule.setUpdatedAt(LocalDateTime.now());
        return ruleRepository.save(rule);
    }

    @Transactional
    public QualityRule setRuleEnabled(Long id, boolean enabled) {
        QualityRule rule = ruleRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("质量规则不存在: " + id));
        rule.setEnabled(enabled);
        rule.setUpdatedAt(LocalDateTime.now());
        return ruleRepository.save(rule);
    }

    @Transactional
    public void deleteRule(Long id) {
        if (!ruleRepository.existsById(id)) {
            throw new IllegalArgumentException("质量规则不存在: " + id);
        }
        ruleRepository.deleteById(id);
    }

    @Transactional(readOnly = true)
    public List<QualityAlert> listAlerts(String level, Boolean resolved) {
        return alertRepository.searchAlerts(level, resolved, null);
    }

    @Transactional
    public QualityAlert resolveAlert(Long id) {
        QualityAlert alert = alertRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("质量告警不存在: " + id));
        alert.setResolved(true);
        alert.setResolvedAt(LocalDateTime.now());
        return alertRepository.save(alert);
    }
}
