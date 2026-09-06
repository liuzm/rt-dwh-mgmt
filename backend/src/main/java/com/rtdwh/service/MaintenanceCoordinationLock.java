package com.rtdwh.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.util.function.Supplier;

/** The guard row is separate from evidence rows, whose intents commit before HTTP. */
@Service @RequiredArgsConstructor
public class MaintenanceCoordinationLock {
    private final JdbcTemplate jdbc;
    private final java.util.concurrent.Semaphore permits = new java.util.concurrent.Semaphore(2);
    private final ThreadLocal<String> owner = new ThreadLocal<>();

    public String token() {
        String value = owner.get();
        if (value == null) throw new IllegalStateException("维护写操作缺少协调所有权");
        return value;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 180)
    public <T> T withTable(Long tableId, Supplier<T> work) {
        // Leave pool capacity for REQUIRES_NEW evidence commits and normal application requests.
        if (!permits.tryAcquire()) throw new IllegalStateException("维护协调繁忙，请稍后重试");
        try {
            jdbc.update("INSERT IGNORE INTO maintenance_coordination_lock(table_meta_id) VALUES (?)", tableId);
            jdbc.queryForObject("SELECT table_meta_id FROM maintenance_coordination_lock WHERE table_meta_id=? FOR UPDATE", Long.class, tableId);
            owner.set(java.util.UUID.randomUUID().toString());
            return work.get();
        } finally { owner.remove(); permits.release(); }
    }
}
