package com.rtdwh.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rtdwh.dto.FoundationDTO;
import com.rtdwh.entity.*;
import com.rtdwh.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
public class FoundationService {
    private static final String DEFAULT_CATALOG = "rtdwh_paimon";
    private final DwhTableMetaRepository tableRepository;
    private final SyncTaskRepository taskRepository;
    private final ReportTemplateRepository reportRepository;
    private final DataServiceDefinitionRepository dataServiceRepository;
    private final TaskOutputDatasetRepository outputRepository;
    private final TaskScheduleRepository scheduleRepository;
    private final QualityRuleRepository qualityRuleRepository;
    private final QualityAlertRepository qualityAlertRepository;
    private final SystemHealthStatusRepository healthRepository;
    private final AlertRecordRepository alertRepository;
    private final SysUserRepository userRepository;
    private final RoleDataScopeRepository scopeRepository;
    private final OperationAuditRepository auditRepository;
    private final TaskDefinitionVersionRepository versionRepository;
    private final QueryAccessScopeService accessScopeService;
    private final ObjectMapper objectMapper;
    private final DataServiceService dataServices;

    @Transactional(readOnly = true)
    public FoundationDTO.Summary summary(Long userId, boolean canViewReports, boolean canViewDataServices,
                                          boolean canManageUsers, boolean canViewAudit) {
        SysUser user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("用户不存在: " + userId));
        List<DwhTableMeta> tables = visibleTables(userId, tableRepository.findAll());
        long reports = canViewReports ? reportRepository.findByIsPublished(true).stream()
                .filter(report -> canAccessDoris(userId, report.getSqlQuery(), report.getFilterConfig(), DEFAULT_CATALOG, "ods"))
                .count() : 0;
        long services = canViewDataServices ? dataServices.publishedDefinitions(userId).size() : 0;
        long unowned = tables.stream().filter(table -> table.getOwner() == null || table.getOwner().isBlank()).count();
        int assetScore = ratioScore(tables.size(), unowned);

        Set<SysRole> roles = user.getRoles();
        long permissions = roles.stream().filter(Objects::nonNull).flatMap(role -> role.getPermissions() == null
                ? Stream.empty() : role.getPermissions().stream()).map(SysPermission::getPermCode).distinct().count();
        List<Long> roleIds = roles.stream().map(SysRole::getId).filter(Objects::nonNull).toList();
        long scopes = scopeRepository.findByRoleIdIn(roleIds).size();
        boolean admin = roles.stream().anyMatch(role -> "ADMIN".equals(role.getRoleCode()));
        int securityRisk = roles.isEmpty() || permissions == 0 || !admin && scopes == 0 ? 1 : 0;

        List<FoundationDTO.SlaRisk> slaRisks = slaRisks(userId);
        List<QualityRule> visibleRules = visibleQualityRules(userId, qualityRuleRepository.findByEnabled(true));
        Set<Long> visibleRuleIds = visibleRules.stream().map(QualityRule::getId).collect(java.util.stream.Collectors.toSet());
        long qualityAlerts = qualityAlertRepository.findByResolvedFalseOrderByTriggeredAtDesc().stream()
                .filter(alert -> visibleRuleIds.contains(alert.getRuleId())).count();
        long qualityRules = visibleRules.size();
        int qualityRisk = safeInt(qualityAlerts + slaRisks.size());

        long unhealthy = healthRepository.findAll().stream()
                .filter(status -> !Set.of("UP", "HEALTHY", "OK").contains(status.getOverallStatus().toUpperCase(Locale.ROOT))).count();
        long openAlerts = alertRepository.countByResolvedFalse();
        List<SyncTask> visibleTasks = visibleTasks(userId, taskRepository.findAll());
        long failedTasks = visibleTasks.stream().filter(task -> task.getStatus() == SyncTask.TaskStatus.failed).count();
        int observableRisk = safeInt(unhealthy + openAlerts + failedTasks);

        LocalDateTime since = LocalDateTime.now().minusHours(24);
        long operations = canViewAudit ? auditRepository.countByCreatedAtAfter(since)
                : auditRepository.countByUsernameAndCreatedAtAfter(user.getUsername(), since);
        long failedOperations = canViewAudit ? auditRepository.countByCreatedAtAfterAndSuccessFalse(since)
                : auditRepository.countByUsernameAndCreatedAtAfterAndSuccessFalse(user.getUsername(), since);
        Set<Long> visibleTaskIds = visibleTasks.stream().map(SyncTask::getId).collect(java.util.stream.Collectors.toSet());
        long versions = versionRepository.findAll().stream()
                .filter(version -> visibleTaskIds.contains(version.getTaskId())).count();

        List<FoundationDTO.Capability> capabilities = List.of(
                capability("asset", "统一检索与资产发现", "表、任务、报表和数据服务统一检索，资产责任可追踪",
                        assetScore, safeInt(unowned), map("可见数据表", tables.size(), "已发布报表", reports,
                                "已发布接口", services, "缺少负责人", unowned), "/foundation"),
                capability("security", "权限与数据安全", "角色权限与 Catalog／Database／Table 数据范围共同生效",
                        securityRisk == 0 ? 100 : 60, securityRisk, map("当前角色", roles.size(),
                                "接口权限", permissions, "数据范围", scopes), canManageUsers ? "/system/users" : "/foundation"),
                capability("quality", "数据质量与 SLA", "质量规则、产出门禁与数据资源新鲜度统一评估",
                        riskScore(qualityRisk), qualityRisk, map("启用规则", qualityRules,
                                "质量告警", qualityAlerts, "SLA 风险", slaRisks.size()), "/quality"),
                capability("observability", "可观测与告警", "组件健康、任务失败和平台告警形成统一风险视图",
                        riskScore(observableRisk), observableRisk, map("异常组件", unhealthy,
                                "未恢复告警", openAlerts, "失败任务", failedTasks), "/system/alert"),
                capability("audit", "审计与变更追溯", "写操作、任务版本与失败变更可回查",
                        riskScore(safeInt(failedOperations)), safeInt(failedOperations), map("24h 操作", operations,
                                "失败操作", failedOperations, "任务版本", versions), canViewAudit ? "/system/audit" : "/foundation")
        );
        int overall = (int) Math.round(capabilities.stream().mapToInt(FoundationDTO.Capability::score).average().orElse(100));
        return new FoundationDTO.Summary(capabilities, slaRisks.stream().limit(20).toList(), overall, LocalDateTime.now());
    }

    @Transactional(readOnly = true)
    public List<FoundationDTO.SearchItem> search(Long userId, String keyword, int requestedLimit,
                                                  boolean canViewReports, boolean canViewDataServices) {
        String query = keyword == null ? "" : keyword.trim();
        if (query.length() < 2) throw new IllegalArgumentException("搜索关键词至少需要 2 个字符");
        int limit = Math.max(1, Math.min(requestedLimit, 50));
        List<FoundationDTO.SearchItem> items = new ArrayList<>();
        visibleTables(userId, tableRepository.searchTables(null, null, query)).stream().limit(limit).forEach(table -> items.add(
                new FoundationDTO.SearchItem("table", table.getId(), table.getPaimonTable(),
                        table.getPaimonDb() + " · " + table.getLayer() + owner(table.getOwner()),
                        table.getLifecycleStatus(), "/dwh/tables/" + table.getId())));
        visibleTasks(userId, taskRepository.searchTasks(null, null, query)).stream().limit(limit).forEach(task -> items.add(
                new FoundationDTO.SearchItem("task", task.getId(), task.getTaskName(),
                        task.getTaskType() + optional(task.getDescription()), task.getStatus().name(),
                        "/sync-task/detail/" + task.getId())));
        if (canViewReports) reportRepository.findByReportNameContainingIgnoreCase(query, PageRequest.of(0, limit)).stream()
                .filter(report -> canAccessDoris(userId, report.getSqlQuery(), report.getFilterConfig(), DEFAULT_CATALOG, "ods")).forEach(report -> items.add(
                new FoundationDTO.SearchItem("report", report.getId(), report.getReportName(),
                        report.getReportType() + " 报表", Boolean.TRUE.equals(report.getIsPublished()) ? "published" : "draft", "/query/report")));
        if (canViewDataServices) dataServiceRepository.searchByKeyword(query, PageRequest.of(0, limit)).stream()
                .filter(service -> dataServices.canReadDefinition(service, userId)).forEach(service -> items.add(
                new FoundationDTO.SearchItem("data_service", service.getId(), service.getServiceName(),
                        service.getServiceCode() + optional(service.getDescription()), service.getStatus().name(), "/query/data-service")));
        return items.stream().limit(limit).toList();
    }

    @Transactional(readOnly = true)
    public List<FoundationDTO.SlaRisk> slaRisks(Long userId) {
        LocalDateTime now = LocalDateTime.now();
        Map<Long, TaskSchedule> schedules = scheduleRepository.findByEnabledTrue().stream()
                .collect(java.util.stream.Collectors.toMap(TaskSchedule::getTaskId, value -> value));
        List<TaskOutputDataset> visibleOutputs = accessScopeService.filterAllowed(userId,
                outputRepository.findByEnabledTrueOrderByLastProducedAtAsc(), TaskOutputDataset::getCatalogName,
                TaskOutputDataset::getDatabaseName, TaskOutputDataset::getTableName);
        return visibleOutputs.stream()
                .filter(output -> schedules.containsKey(output.getTaskId()))
                .map(output -> risk(output, schedules.get(output.getTaskId()), now)).filter(Objects::nonNull)
                .sorted(Comparator.comparingLong(FoundationDTO.SlaRisk::overdueMinutes).reversed()).toList();
    }

    private FoundationDTO.SlaRisk risk(TaskOutputDataset output, TaskSchedule schedule, LocalDateTime now) {
        LocalDateTime scheduledAt = schedule.getLastRunAt() == null ? null
                : schedule.getLastRunAt().atZone(ZoneId.of(schedule.getTimezone())).toLocalDateTime();
        if (scheduledAt != null && output.getLastProducedAt() != null
                && !output.getLastProducedAt().isBefore(scheduledAt)) return null;
        LocalDateTime reference = scheduledAt;
        if (reference == null) {
            reference = output.getCreatedAt();
            if (schedule.getCreatedAt() != null && (reference == null || schedule.getCreatedAt().isAfter(reference))) {
                reference = schedule.getCreatedAt();
            }
        }
        if (reference == null) return null;
        long age = Math.max(0, ChronoUnit.MINUTES.between(reference, now));
        long overdue = age - output.getSlaMinutes();
        if (overdue <= 0) return null;
        String severity = overdue > output.getSlaMinutes() * 2L ? "critical" : overdue > output.getSlaMinutes() ? "high" : "warning";
        return new FoundationDTO.SlaRisk(output.getId(), output.getTaskId(), output.getCatalogName() + "."
                + output.getDatabaseName() + "." + output.getTableName(), output.getLayer().name(),
                output.getOwner(), output.getSlaMinutes(), output.getLastProducedAt(), overdue, severity);
    }

    private boolean canAccessDoris(Long userId, String sql, String parameters, String catalog, String database) {
        try {
            return accessScopeService.canAccessDorisSql(userId,
                    new ReportParameterRenderer(objectMapper).sqlForAccessCheck(sql, parameters), catalog, database);
        } catch (IllegalArgumentException invalid) { return false; }
    }

    private List<DwhTableMeta> visibleTables(Long userId, List<DwhTableMeta> tables) {
        return accessScopeService.filterAllowed(userId, tables,
                table -> table.getCatalogName() == null ? DEFAULT_CATALOG : table.getCatalogName(),
                DwhTableMeta::getPaimonDb, DwhTableMeta::getPaimonTable);
    }

    private List<QualityRule> visibleQualityRules(Long userId, List<QualityRule> rules) {
        return rules.stream().filter(rule -> {
            String database = rule.getLayer() == null || rule.getLayer().isBlank() ? "ods" : rule.getLayer();
            try {
                return accessScopeService.allowedReference(userId, rule.getTargetTable(), DEFAULT_CATALOG, database);
            } catch (IllegalArgumentException invalidReference) {
                return false;
            }
        }).toList();
    }

    private List<SyncTask> visibleTasks(Long userId, List<SyncTask> tasks) {
        if (accessScopeService.isAdmin(userId)) return List.copyOf(tasks);
        return tasks.stream().filter(task -> taskVisible(userId, task)).toList();
    }

    private boolean taskVisible(Long userId, SyncTask task) {
        if (task.getTaskType() != SyncTask.TaskType.cdc_sync) {
            return task.getFlinkSql() != null && accessScopeService.canAccessSql(
                    userId, task.getFlinkSql(), DEFAULT_CATALOG, "ods");
        }
        try {
            JsonNode mappings = objectMapper.readTree(task.getTableMappings());
            if (!mappings.isArray() || mappings.isEmpty()) return false;
            for (JsonNode mapping : mappings) {
                String database = mapping.path("targetDb").asText("ods");
                String table = mapping.path("targetTable").asText();
                if (table.isBlank() || !accessScopeService.allowed(userId, DEFAULT_CATALOG, database, table)) return false;
            }
            return true;
        } catch (Exception invalidMappings) {
            return false;
        }
    }
    private FoundationDTO.Capability capability(String key, String name, String description, int score,
                                                 int risks, Map<String, Long> metrics, String path) {
        String status = risks == 0 ? "healthy" : score >= 80 ? "attention" : "risk";
        return new FoundationDTO.Capability(key, name, description, status, score, risks, metrics, path);
    }
    private Map<String, Long> map(Object... values) {
        Map<String, Long> result = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i += 2) result.put(String.valueOf(values[i]), ((Number) values[i + 1]).longValue());
        return result;
    }
    private int ratioScore(long total, long risk) { return total == 0 ? 100 : Math.max(0, 100 - (int) Math.round(risk * 60.0 / total)); }
    private int riskScore(int risk) { return Math.max(0, 100 - Math.min(80, risk * 10)); }
    private int safeInt(long value) { return (int) Math.min(Integer.MAX_VALUE, value); }
    private String owner(String value) { return value == null || value.isBlank() ? " · 未指定负责人" : " · " + value; }
    private String optional(String value) { return value == null || value.isBlank() ? "" : " · " + value; }
}
