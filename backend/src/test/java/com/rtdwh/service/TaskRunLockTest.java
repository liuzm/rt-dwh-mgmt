package com.rtdwh.service;

import com.rtdwh.entity.TaskRunInstance;
import com.rtdwh.repository.TaskRunInstanceLockRepositoryImpl;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TaskRunLockTest {
    @Test void refreshesPreloadedStateWhileHoldingWriteLock() {
        EntityManager em = mock(EntityManager.class);
        TaskRunInstance cached = TaskRunInstance.builder().id(1L).status(TaskRunInstance.RunStatus.running).build();
        when(em.find(TaskRunInstance.class, 1L, LockModeType.PESSIMISTIC_WRITE)).thenReturn(cached);
        doAnswer(call -> { cached.setStatus(TaskRunInstance.RunStatus.success); return null; })
                .when(em).refresh(cached, LockModeType.PESSIMISTIC_WRITE);
        var current = new TaskRunInstanceLockRepositoryImpl(em).findByIdForUpdate(1L).orElseThrow();
        assertEquals(TaskRunInstance.RunStatus.success, current.getStatus());
        var order = inOrder(em);
        order.verify(em).find(TaskRunInstance.class, 1L, LockModeType.PESSIMISTIC_WRITE);
        order.verify(em).refresh(cached, LockModeType.PESSIMISTIC_WRITE);
    }
}
