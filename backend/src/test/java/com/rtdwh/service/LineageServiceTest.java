package com.rtdwh.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rtdwh.entity.*;
import com.rtdwh.repository.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LineageServiceTest {
    private final SyncTaskRepository tasks = mock(SyncTaskRepository.class);
    private final DatasourceConfigRepository sources = mock(DatasourceConfigRepository.class);
    private final DwhTableMetaRepository tables = mock(DwhTableMetaRepository.class);
    private final DwhDataLineageRepository edges = mock(DwhDataLineageRepository.class);
    private final QueryAccessScopeService access = mock(QueryAccessScopeService.class);
    private final SyncTaskService taskService = mock(SyncTaskService.class);
    private final LineageService service = new LineageService(tasks, sources, tables, edges, new ObjectMapper(), access, taskService);

    @Test void doesNotReintroduceHiddenTablesThroughPersistedEdgesOrKeywordExpansion() {
        DwhTableMeta visible = DwhTableMeta.builder().id(1L).paimonDb("ods").paimonTable("orders").layer(DwhTableMeta.TableLayer.ods).build();
        DwhTableMeta hidden = DwhTableMeta.builder().id(2L).paimonDb("ods").paimonTable("payroll").layer(DwhTableMeta.TableLayer.ods).build();
        when(tables.findAll()).thenReturn(List.of(visible, hidden));
        when(access.allowed(7L, "rtdwh_paimon", "ods", "orders")).thenReturn(true);
        when(edges.findAll()).thenReturn(List.of(DwhDataLineage.builder().sourceTable(visible).targetTable(hidden).build()));
        var graph = service.getGraph(null, "orders", 7L);
        assertEquals(1, graph.nodes().size());
        assertEquals("orders", graph.nodes().get(0).name());
        assertTrue(graph.edges().isEmpty());
        assertTrue(service.getGraph(null, "payroll", 7L).nodes().isEmpty());
    }

    @Test void unauthorizedTasksCannotExposeSourceMetadata() {
        SyncTask hidden = SyncTask.builder().id(1L).sourceConfigId(2L).taskName("private task").build();
        when(tasks.findAll()).thenReturn(List.of(hidden));
        assertTrue(service.getGraph(null, null, 7L).nodes().isEmpty());
        verify(taskService).listTasksForUser(7L, null, null, null);
    }

    @Test void sqlCommentCannotInjectAnUnauthorizedTableIntoVisibleGraph() {
        SyncTask task = SyncTask.builder().id(1L).taskName("allowed").taskType(SyncTask.TaskType.etl)
                .status(SyncTask.TaskStatus.draft).flinkSql("select 1 /* FROM ods.payroll */").build();
        when(taskService.listTasksForUser(7L, null, null, null)).thenReturn(List.of(task));
        var graph = service.getGraph(null, null, 7L);
        assertEquals(1, graph.nodes().size());
        assertEquals("task", graph.nodes().get(0).type());
        assertTrue(graph.edges().isEmpty());
    }
    @Test void legacyGraphCannotTreatInternalViewAsPaimonEvenThroughPersistedEdges() {
        var table=DwhTableMeta.builder().id(1L).paimonDb("ods").paimonTable("base").layer(DwhTableMeta.TableLayer.ods).build();
        var view=DwhTableMeta.builder().id(2L).catalogName("internal").paimonDb("rtdwh_views").paimonTable("private_view").assetType("doris_view").layer(DwhTableMeta.TableLayer.ads).build();
        when(tables.findAll()).thenReturn(List.of(table,view));
        when(access.allowed(7L,"rtdwh_paimon","ods","base")).thenReturn(true);
        when(access.allowed(7L,"rtdwh_paimon","rtdwh_views","private_view")).thenReturn(true);
        when(edges.findAll()).thenReturn(List.of(DwhDataLineage.builder().sourceTable(table).targetTable(view).build()));
        var graph=service.getGraph(null,null,7L);
        assertEquals(1,graph.nodes().size());assertEquals("base",graph.nodes().get(0).name());assertTrue(graph.edges().isEmpty());
    }

}
