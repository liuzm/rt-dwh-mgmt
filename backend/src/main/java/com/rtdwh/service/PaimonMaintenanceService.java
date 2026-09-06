package com.rtdwh.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rtdwh.dto.MaintenanceRecoveryDTO;
import com.rtdwh.entity.*;
import com.rtdwh.entity.TableMaintenanceLog.Status;
import com.rtdwh.repository.*;
import com.rtdwh.util.SecurityContextUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.*;

/** Persisted intent precedes each external mutation. Missing handles never trigger resubmission. */
@Slf4j @Service @RequiredArgsConstructor
public class PaimonMaintenanceService {
    private static final List<Status> ACTIVE = List.of(Status.running, Status.unknown, Status.timed_out, Status.pending);
    private final TableMaintenanceLogRepository repository;
    private final MaintenanceRecoveryEventRepository events;
    private final MaintenancePersistenceService persistence;
    private final MaintenanceCoordinationLock locks;
    private final SqlGatewayClient gateway;
    private final FlinkClusterService runtime;
    private final ObjectMapper mapper;
    private final DwhTableMetaRepository tables;
    private final QueryAccessScopeService access;
    private final SysUserRepository users;
    private final SecurityContextUtil security;
    @Value("${paimon.jdbc-uri}") private String jdbcUri;
    @Value("${paimon.jdbc-user}") private String jdbcUser;
    @Value("${paimon.jdbc-password}") private String jdbcPassword;
    @Value("${paimon.warehouse-path}") private String warehouse;
    @Value("${paimon.catalog-key:rtdwh}") private String catalog;
    @Value("${doris.catalog:rtdwh_paimon}") private String dataCatalog = "rtdwh_paimon";
    @Value("${maintenance.timeout-seconds:1800}") private long timeoutSeconds = 1800;

    public record Environment(String jdbcUri, String jdbcUser, String warehouse, String catalog) {}
    public record Detail(TableMaintenanceLog operation, List<MaintenanceRecoveryEvent> events) {}

    public Map<String,Object> start(TableMaintenanceLog request) {
        Long actor = security.getCurrentUserId();
        return locks.withTable(request.getTableMetaId(), () -> {
            if (repository.existsByTableMetaIdAndStatusIn(request.getTableMetaId(), ACTIVE)) {
                throw new IllegalStateException("该表存在未完成或结果未知的维护操作，请先查看恢复记录，禁止重复提交");
            }
            DwhTableMeta table = tables.findById(request.getTableMetaId()).orElseThrow();
            request.setAssetId(table.getAssetId()); request.setCatalogName(table.getCatalogName() == null ? dataCatalog : table.getCatalogName());
            request.setDatabaseName(table.getPaimonDb()); request.setTableName(table.getPaimonTable()); request.setRequestedBy(actor);
            request.setContractOrigin("bound_v1"); request.setGatewayUrl(SqlGatewayClient.endpoint(runtime.getSqlGatewayUrl()));
            request.setFlinkUrl(SqlGatewayClient.endpoint(runtime.getFlinkRestUrl()));
            request.setEnvironmentJson(environmentJson());
            request.setCorrelationName("rtdwh-maintenance-" + UUID.randomUUID());
            request.setStatus(runtime.isSqlGatewayEnabled() ? Status.unknown : Status.pending);
            request.setExecutionPhase(runtime.isSqlGatewayEnabled() ? "SESSION" : "REQUESTED");
            request.setCleanupStatus("held");
            assertExecution(request);
            TableMaintenanceLog entry = persistence.create(request);
            if (runtime.isSqlGatewayEnabled()) {
                try {
                    String session = gateway.open(entry.getGatewayUrl(), Map.of("sessionName", entry.getCorrelationName()));
                    persistence.update(entry.getId(), null, "session_bound", null, value -> {
                        value.setSessionId(session); value.setExecutionPhase("SESSION_READY"); value.setStatus(Status.running);
                    });
                    advance(persistence.get(entry.getId()));
                } catch (Exception failure) { unknown(entry.getId(), "会话或提交结果无法确认；缺少句柄时不自动重试"); }
            }
            var current = persistence.get(entry.getId());
            return Map.of("operationId", current.getId().toString(), "status", current.getStatus().name(),
                    "message", "维护请求已记录，请在恢复详情查看进展");
        });
    }

    @Scheduled(fixedDelayString = "${maintenance.reconcile-ms:5000}", initialDelay = 15000)
    public void reconcile() {
        // New-submission enablement does not disable observation/cleanup of old bound operations.
        for (Long id : repository.findRecoverableIds(ACTIVE)) {
            try {
                var entry = persistence.get(id);
                locks.withTable(entry.getTableMetaId(), () -> { reconcileLocked(id); return null; });
            } catch (Exception failure) { log.warn("Maintenance {} coordination deferred ({})", id, failure.getClass().getSimpleName()); }
        }
    }

    private void reconcileLocked(Long id) {
        persistence.claim(id);
        TableMaintenanceLog entry = persistence.get(id);
        if (!"bound_v1".equals(entry.getContractOrigin())) return;
        if (terminal(entry)) { cleanup(entry, false); return; }
        if (entry.getStatus() == Status.pending) return;
        try { advance(entry); }
        catch (Exception failure) { unknown(id, "绑定环境暂不可确认或当前权限不满足，保留原句柄等待协调"); }
        var observed = persistence.get(id);
        if (terminal(observed)) cleanup(observed, false);
    }

    private void advance(TableMaintenanceLog entry) throws Exception {
        if (entry.getFlinkJobId() != null) {
            String state = gateway.job(entry.getFlinkUrl(), entry.getFlinkJobId()).path("state").asText();
            observation(entry.getId(), state);
            if ("FINISHED".equals(state)) finish(entry.getId(), Status.success, null);
            else if (List.of("FAILED", "CANCELED").contains(state)) finish(entry.getId(), Status.failed, "Flink Job: " + state);
            else if (List.of("INITIALIZING", "CREATED", "RUNNING", "RESTARTING", "RECONCILING", "CANCELLING", "FAILING", "SUSPENDED").contains(state)) progress(entry.getId());
            else unknown(entry.getId(), "Flink 返回未知状态，不能确认维护完成");
            return;
        }
        if ("SESSION_READY".equals(entry.getExecutionPhase())) {
            submit(entry, "CATALOG", catalogSql(entry)); return;
        }
        if (entry.getSessionId() == null || entry.getOperationId() == null) {
            unknown(entry.getId(), "提交缺少可核验句柄；请登记处置证据，禁止重复执行"); return;
        }
        String state = gateway.status(entry.getGatewayUrl(), entry.getSessionId(), entry.getOperationId()).path("status").asText();
        observation(entry.getId(), state);
        if (List.of("ERROR", "CANCELED").contains(state)) {
            finish(entry.getId(), Status.failed, "Gateway operation: " + state);
        } else if ("CLOSED".equals(state)) {
            unknown(entry.getId(), "操作已关闭且缺少终态证据，不能推断业务执行结果");
        } else if ("FINISHED".equals(state)) {
            switch (entry.getExecutionPhase()) {
                case "CATALOG" -> submit(entry, "USE", "USE CATALOG " + DorisConnectionService.quoteIdentifier(environment(entry).catalog()));
                case "USE" -> submit(entry, "CALL", entry.getSqlContent());
                case "CALL" -> observeResult(entry);
                default -> unknown(entry.getId(), "维护阶段未知，需要人工核对");
            }
        } else if (List.of("INITIALIZED", "PENDING", "RUNNING").contains(state)) progress(entry.getId());
        else unknown(entry.getId(), "Gateway 返回未知状态，等待后续协调");
    }

    private void observeResult(TableMaintenanceLog entry) {
        JsonNode result = gateway.result(entry.getGatewayUrl(), entry.getSessionId(), entry.getOperationId(), 0);
        if ("NOT_READY".equals(result.path("resultType").asText())) { progress(entry.getId()); return; }
        Set<String> jobIds = new LinkedHashSet<>();
        for (JsonNode row : result.path("results").path("data")) for (JsonNode field : row.path("fields")) {
            var found = java.util.regex.Pattern.compile("(?i)\\b([a-f0-9]{32})\\b").matcher(field.asText());
            if (field.isTextual() && found.find()) jobIds.add(found.group(1));
        }
        for (String field : List.of("jobID", "jobId")) {
            if (result.path(field).asText().matches("(?i)[a-f0-9]{32}")) jobIds.add(result.path(field).asText());
        }
        if (jobIds.size() > 1) { unknown(entry.getId(), "返回多个 Job，无法认定单次维护结果"); return; }
        if (jobIds.size() == 1) {
            persistence.update(entry.getId(), null, "job_bound", "Job 来自原操作结果", value -> {
                value.setFlinkJobId(jobIds.iterator().next()); value.setExecutionPhase("JOB");
            });
            progress(entry.getId()); return;
        }
        if (!result.path("results").isObject() && !"EOS".equals(result.path("resultType").asText())) {
            unknown(entry.getId(), "Gateway 未返回可验证的执行结果"); return;
        }
        finish(entry.getId(), Status.success, null);
    }

    private void submit(TableMaintenanceLog entry, String phase, String sql) {
        assertExecution(entry);
        persistence.update(entry.getId(), null, "submit_intent", "已记录 " + phase + " 提交意图", value -> {
            value.setExecutionPhase(phase); value.setOperationId(null); value.setStatus(Status.unknown);
            value.setObservedState(null); value.setErrorMsg("提交结果待确认；缺少句柄时禁止重复执行");
        });
        String operation = gateway.submit(entry.getGatewayUrl(), entry.getSessionId(), sql,
                Map.of("execution.runtime-mode", "batch", "table.dml-sync", "true", "pipeline.name", entry.getCorrelationName()));
        persistence.update(entry.getId(), null, "operation_bound", null, value -> value.setOperationId(operation));
        progress(entry.getId());
    }

    public Detail detail(Long id, Long actor) {
        var entry = persistence.get(id); assertRead(entry, actor);
        return new Detail(entry, events.findTop200ByMaintenanceIdOrderByIdDesc(id));
    }
    public List<TableMaintenanceLog> logs(Long tableId, TableMaintenanceLog.Operation operation, Status status, Long actor) {
        return repository.searchLogs(operation, status, tableId).stream().filter(value -> canRead(value, actor)).toList();
    }
    public Detail recover(Long id, MaintenanceRecoveryDTO request, Long actor) {
        var initial = persistence.get(id); assertRead(initial, actor);
        return locks.withTable(initial.getTableMetaId(), () -> {
            persistence.claim(id);
            var entry = persistence.get(id); assertRead(entry, actor); assertManager(actor);
            if (!Objects.equals(entry.getRevision(), request.getExpectedRevision())) throw new IllegalStateException("维护状态已变化，请刷新详情后重试");
            if (!List.of("note", "cancel_pending").contains(request.getAction()) && !"bound_v1".equals(entry.getContractOrigin())) {
                throw new IllegalStateException("历史操作缺少绑定环境证据，仅允许补充处置记录，不能推断执行结果");
            }
            switch (request.getAction()) {
                case "cancel_pending" -> {
                    if (entry.getStatus() != Status.pending || entry.getSessionId() != null || entry.getOperationId() != null || entry.getFlinkJobId() != null) {
                        throw new IllegalStateException("仅平台尚未提交且没有引擎句柄的待执行请求可取消");
                    }
                    persistence.update(id, actor, "pending_cancelled", request.getReason(), value -> {
                        value.setStatus(Status.failed); value.setFinishedAt(LocalDateTime.now());
                        value.setDurationMs(Duration.between(value.getStartedAt(), value.getFinishedAt()).toMillis());
                        value.setErrorMsg("人工取消平台尚未提交的请求；不认定平台外人工执行的结果"); value.setCleanupStatus("not_required");
                    });
                }
                case "note" -> persistence.update(id, actor, "manual_note", request.getReason(), value -> {});
                case "observe" -> {
                    persistence.update(id, actor, "manual_observe", request.getReason(), value -> {});
                    reconcileLocked(id);
                }
                case "retry_cleanup" -> {
                    if (!terminal(entry) || !"pending".equals(entry.getCleanupStatus())) throw new IllegalStateException("仅已确认终态且待清理的会话可重试清理");
                    persistence.update(id, actor, "manual_cleanup", request.getReason(), value -> {});
                    cleanup(persistence.get(id), true);
                }
                case "attach_job" -> {
                    if (terminal(entry) || !"CALL".equals(entry.getExecutionPhase()) || entry.getFlinkJobId() != null) {
                        throw new IllegalStateException("仅业务 CALL 结果待确认且尚无 Job 的操作可关联");
                    }
                    if (request.getJobId() == null) throw new IllegalArgumentException("请提供 Job ID");
                    JsonNode job = gateway.job(entry.getFlinkUrl(), request.getJobId());
                    if (!entry.getCorrelationName().equals(job.path("name").asText())
                            || !request.getJobId().equalsIgnoreCase(job.path("jid").asText())) {
                        throw new IllegalArgumentException("Job 与原提交关联标识不一致，不能接管");
                    }
                    persistence.update(id, actor, "manual_job_bound", request.getReason(), value -> {
                        value.setFlinkJobId(request.getJobId()); value.setExecutionPhase("JOB");
                    });
                    reconcileLocked(id);
                }
                case "cancel_preparation" -> {
                    if (terminal(entry) || !List.of("REQUESTED", "SESSION", "SESSION_READY", "CATALOG", "USE").contains(entry.getExecutionPhase())) {
                        throw new IllegalStateException("业务 CALL 可能已提交，不能取消准备或清空状态");
                    }
                    persistence.update(id, actor, "preparation_cancelled", request.getReason(), value -> {});
                    finish(id, Status.failed, "人工取消准备阶段；持久化记录证明业务 CALL 尚未提交");
                    cleanup(persistence.get(id), true);
                }
                default -> throw new IllegalArgumentException("不支持的恢复动作");
            }
            return detail(id, actor);
        });
    }

    private void cleanup(TableMaintenanceLog entry, boolean force) {
        if (!terminal(entry) || !"pending".equals(entry.getCleanupStatus()) || entry.getSessionId() == null) return;
        if (!force && entry.getCleanupNextAt() != null && entry.getCleanupNextAt().isAfter(LocalDateTime.now())) return;
        persistence.update(entry.getId(), null, "cleanup_intent", "清理原绑定环境中的已结束会话", value -> {
            value.setCleanupAttempts(value.getCleanupAttempts() + 1);
            long delay = Math.min(300, 5L << Math.min(6, value.getCleanupAttempts() - 1));
            value.setCleanupNextAt(LocalDateTime.now().plusSeconds(delay));
        });
        try {
            String evidence = gateway.close(entry.getGatewayUrl(), entry.getSessionId());
            persistence.update(entry.getId(), null, "session_cleaned", evidence, value -> {
                value.setCleanupStatus("done"); value.setCleanupNextAt(null); value.setCleanupError(null); value.setCleanedAt(LocalDateTime.now());
            });
        } catch (Exception failure) {
            persistence.update(entry.getId(), null, "cleanup_deferred", null,
                    value -> value.setCleanupError("原 Gateway 会话清理未确认，将按退避计划重试"));
        }
    }
    private void observation(Long id, String state) {
        persistence.update(id, null, "engine_observed", null, value -> {
            value.setObservedState(state.length() > 32 ? "UNRECOGNIZED" : state); value.setObservedAt(LocalDateTime.now());
        });
    }
    private void progress(Long id) {
        persistence.update(id, null, "progress", null, value -> {
            boolean overdue = value.getStartedAt() != null && Duration.between(value.getStartedAt(), LocalDateTime.now()).getSeconds() > timeoutSeconds;
            value.setStatus(overdue ? Status.timed_out : Status.running);
            value.setErrorMsg(overdue ? "超过观测时限，仍在协调；请勿重复提交" : null);
        });
    }
    private void unknown(Long id, String message) {
        persistence.update(id, null, "uncertain", null, value -> { value.setStatus(Status.unknown); value.setErrorMsg(message); });
    }
    private void finish(Long id, Status status, String message) {
        persistence.update(id, null, "terminal_observed", message, value -> {
            value.setStatus(status); value.setErrorMsg(message); value.setFinishedAt(LocalDateTime.now());
            value.setDurationMs(Duration.between(value.getStartedAt(), value.getFinishedAt()).toMillis());
            value.setCleanupStatus(value.getSessionId() == null ? "REQUESTED".equals(value.getExecutionPhase()) ? "not_required" : "unresolved" : "pending");
            value.setCleanupNextAt(value.getSessionId() == null ? null : LocalDateTime.now());
        });
    }
    private boolean terminal(TableMaintenanceLog value) { return value.getStatus() == Status.success || value.getStatus() == Status.failed; }
    private boolean canRead(TableMaintenanceLog value, Long actor) {
        if (access.isAdmin(actor)) return true;
        return "bound_v1".equals(value.getContractOrigin()) && access.allowed(actor, value.getCatalogName(), value.getDatabaseName(), value.getTableName());
    }
    private void assertRead(TableMaintenanceLog value, Long actor) { if (!canRead(value, actor)) throw new AccessDeniedException("无权访问该维护操作的历史目标"); }
    private void assertManager(Long actor) {
        SysUser user = users.findById(actor).orElseThrow(() -> new AccessDeniedException("维护执行用户不存在"));
        if (user.getStatus() != SysUser.UserStatus.active || user.getRoles().stream().noneMatch(role -> "ADMIN".equals(role.getRoleCode())
                || role.getPermissions().stream().anyMatch(permission -> "dwh:manage".equals(permission.getPermCode())))) {
            throw new AccessDeniedException("维护执行用户已停用或维护权限已撤销");
        }
    }
    private void assertExecution(TableMaintenanceLog entry) {
        assertManager(entry.getRequestedBy()); assertRead(entry, entry.getRequestedBy());
        var table = tables.findById(entry.getTableMetaId()).orElseThrow(() -> new IllegalStateException("原资产登记已不存在"));
        if (!Objects.equals(entry.getAssetId(), table.getAssetId()) || !Objects.equals(entry.getDatabaseName(), table.getPaimonDb())
                || !Objects.equals(entry.getTableName(), table.getPaimonTable())) throw new IllegalStateException("资产绑定已变化，不能继续提交原维护操作");
    }
    private String environmentJson() {
        if (jdbcUri == null || jdbcUri.matches("(?i).*(password|passwd|user)=.*") || jdbcUri.contains("@")) {
            throw new IllegalArgumentException("Paimon JDBC 地址不能嵌入凭证，请使用独立凭证配置");
        }
        try { return mapper.writeValueAsString(new Environment(jdbcUri, jdbcUser, warehouse, catalog)); }
        catch (Exception failure) { throw new IllegalStateException("无法固定维护环境", failure); }
    }
    private Environment environment(TableMaintenanceLog entry) {
        try { return mapper.readValue(entry.getEnvironmentJson(), Environment.class); }
        catch (Exception failure) { throw new IllegalStateException("维护环境契约无法读取", failure); }
    }
    private String catalogSql(TableMaintenanceLog entry) {
        if (!environment(entry).equals(new Environment(jdbcUri, jdbcUser, warehouse, catalog))) {
            throw new IllegalStateException("Catalog 配置已变化，旧准备操作不能使用新环境初始化");
        }
        Environment env = environment(entry);
        return "CREATE CATALOG " + DorisConnectionService.quoteIdentifier(env.catalog()) + " WITH ('type'='paimon', 'metastore'='jdbc', 'uri'=" + literal(env.jdbcUri())
                + ", 'jdbc.user'=" + literal(env.jdbcUser()) + ", 'jdbc.password'=" + literal(jdbcPassword)
                + ", 'catalog-key'=" + literal(env.catalog()) + ", 'warehouse'=" + literal(env.warehouse()) + ")";
    }
    private String literal(String value) { return "'" + value.replace("'", "''") + "'"; }
}
