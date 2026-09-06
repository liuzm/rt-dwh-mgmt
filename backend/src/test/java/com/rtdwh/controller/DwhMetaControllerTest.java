package com.rtdwh.controller;

import com.rtdwh.entity.*;
import com.rtdwh.repository.DwhColumnMetaRepository;
import com.rtdwh.service.*;
import com.rtdwh.util.SecurityContextUtil;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DwhMetaControllerTest {
    private final DwhMetaService service = mock(DwhMetaService.class);
    private final QueryAccessScopeService access = mock(QueryAccessScopeService.class);
    private final SecurityContextUtil security = mock(SecurityContextUtil.class);
    private final DwhColumnMetaRepository columns = mock(DwhColumnMetaRepository.class);
    private final DwhMetaController controller = new DwhMetaController(service, access, security, columns);

    @Test void tableAndColumnIdCannotBypassScope() {
        when(security.getCurrentUserId()).thenReturn(7L);
        when(service.getTableDetail(2L)).thenReturn(DwhTableMeta.builder().id(2L).paimonDb("ods").paimonTable("private").build());
        when(columns.findById(5L)).thenReturn(Optional.of(DwhColumnMeta.builder().id(5L).tableMetaId(2L).build()));
        assertThrows(AccessDeniedException.class, () -> controller.getTableDetail(2L));
        assertThrows(AccessDeniedException.class, () -> controller.triggerCompact(2L, "minor"));
        assertThrows(AccessDeniedException.class, () -> controller.updateColumnComment(5L, Map.of("comment", "changed")));
        verify(service, never()).triggerCompact(2L, "minor");
        verify(service, never()).updateColumnComment(5L, "changed");
    }

    @Test void batchRejectsUnauthorizedTableBeforeAnyMaintenanceStarts() {
        when(security.getCurrentUserId()).thenReturn(7L);
        DwhTableMeta hidden = DwhTableMeta.builder().id(2L).paimonDb("ods").paimonTable("private").build();
        when(service.listTables(null, null, null)).thenReturn(List.of(hidden));
        when(service.getTableDetail(2L)).thenReturn(hidden);
        assertThrows(AccessDeniedException.class, () -> controller.batchExpireSnapshots(Map.of("retainLast", 10)));
        verify(service, never()).batchExpireSnapshots(null, 10);
    }
}
