package com.rtdwh.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rtdwh.entity.*;
import com.rtdwh.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Consumer;

@Service @RequiredArgsConstructor
public class MaintenancePersistenceService {
    private final TableMaintenanceLogRepository logs;
    private final MaintenanceRecoveryEventRepository events;
    private final ObjectMapper mapper;
    private final MaintenanceCoordinationLock locks;

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public TableMaintenanceLog get(Long id) { return logs.findById(id).orElseThrow(() -> new IllegalArgumentException("维护操作不存在")); }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public TableMaintenanceLog create(TableMaintenanceLog value) {
        value.setCoordinationToken(locks.token());
        var saved = logs.saveAndFlush(value); event(saved, saved.getRequestedBy(), "requested", "维护意图已记录"); return saved;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void claim(Long id) {
        var entry = logs.findByIdForUpdate(id).orElseThrow();
        entry.setCoordinationToken(locks.token()); logs.saveAndFlush(entry);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public TableMaintenanceLog update(Long id, Long actor, String action, String reason, Consumer<TableMaintenanceLog> change) {
        var entry = logs.findByIdForUpdate(id).orElseThrow();
        if (!Objects.equals(entry.getCoordinationToken(), locks.token())) {
            throw new IllegalStateException("维护协调所有权已变化，拒绝旧协调器回写");
        }
        String before = signature(entry);
        change.accept(entry);
        boolean changed = !before.equals(signature(entry));
        if (changed || actor != null) entry.setRevision(entry.getRevision() + 1);
        logs.saveAndFlush(entry);
        if (changed || actor != null) event(entry, actor, action, reason);
        return entry;
    }
    private String signature(TableMaintenanceLog e) {
        return Arrays.asList(e.getStatus(), e.getExecutionPhase(), e.getSessionId(), e.getOperationId(), e.getFlinkJobId(),
                e.getCleanupStatus(), e.getCleanupAttempts(), e.getErrorMsg(), e.getObservedState()).toString();
    }
    private void event(TableMaintenanceLog e, Long actor, String action, String reason) {
        try {
            Map<String,Object> data = new LinkedHashMap<>();
            data.put("revision", e.getRevision()); data.put("status", e.getStatus()); data.put("phase", e.getExecutionPhase());
            data.put("sessionId", e.getSessionId()); data.put("operationId", e.getOperationId()); data.put("flinkJobId", e.getFlinkJobId());
            data.put("observedState", e.getObservedState()); data.put("cleanupStatus", e.getCleanupStatus());
            data.put("cleanupAttempts", e.getCleanupAttempts()); data.put("contractOrigin", e.getContractOrigin());
            events.save(MaintenanceRecoveryEvent.builder().maintenanceId(e.getId()).actorId(actor).action(action).reason(reason)
                    .evidenceJson(mapper.writeValueAsString(data)).createdAt(LocalDateTime.now()).build());
        } catch (java.io.IOException failure) { throw new IllegalStateException("维护证据无法持久化", failure); }
    }
}
