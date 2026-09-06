package com.rtdwh.service;

import com.rtdwh.dto.QueryExecuteDTO;
import com.rtdwh.entity.QueryHistory;
import com.rtdwh.repository.QueryHistoryRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class QueryServiceDorisTest {

    @Test
    void rejectsDorisSelectIntoOutfile() {
        QueryService service = new QueryService(
                mock(QueryHistoryRepository.class), mock(DorisConnectionService.class),
                mock(QueryAccessScopeService.class));
        QueryExecuteDTO dto = new QueryExecuteDTO();
        dto.setSql("SELECT * FROM users INTO OUTFILE 'file:///tmp/users.csv'");

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class, () -> service.executeQuery(dto, 7L));

        assertTrue(exception.getMessage().contains("仅支持安全"));
    }

    @Test
    void executesReadOnlyQueryThroughDorisAndReturnsTraceContext() throws Exception {
        QueryHistoryRepository repository = mock(QueryHistoryRepository.class);
        DorisConnectionService connectionService = mock(DorisConnectionService.class);
        Connection connection = mock(Connection.class);
        Statement session = mock(Statement.class);
        Statement queryStatement = mock(Statement.class);
        ResultSet resultSet = mock(ResultSet.class);
        ResultSetMetaData metadata = mock(ResultSetMetaData.class);

        when(repository.save(any(QueryHistory.class))).thenAnswer(invocation -> {
            QueryHistory history = invocation.getArgument(0);
            if (history.getId() == null) history.setId(42L);
            return history;
        });
        when(connectionService.getCatalog()).thenReturn("rtdwh_paimon");
        when(connectionService.getDatabase()).thenReturn("ods");
        when(connectionService.getWorkloadGroup()).thenReturn("rtdwh_adhoc");
        when(connectionService.getExecMemLimitBytes()).thenReturn(1024L * 1024L * 1024L);
        when(connectionService.getConnection()).thenReturn(connection);
        when(connectionService.getQueryRuntimeStats("trace-123")).thenReturn(
                new DorisConnectionService.QueryRuntimeStats(
                        "doris-query-1", 10, 200, 50, 200, 100, 100, 0, "FINISHED"));
        when(connectionService.getQueryIdByTraceId("trace-123")).thenReturn("doris-query-1");
        when(connection.createStatement()).thenReturn(session);
        when(connection.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY))
                .thenReturn(queryStatement);
        when(queryStatement.executeQuery("SELECT id, name FROM users")).thenReturn(resultSet);
        when(resultSet.getMetaData()).thenReturn(metadata);
        when(metadata.getColumnCount()).thenReturn(2);
        when(metadata.getColumnLabel(1)).thenReturn("id");
        when(metadata.getColumnLabel(2)).thenReturn("name");
        when(resultSet.next()).thenReturn(true, false);
        when(resultSet.getObject(1)).thenReturn(1L);
        when(resultSet.getObject(2)).thenReturn("Alice");

        QueryAccessScopeService accessScopeService = mock(QueryAccessScopeService.class);
        QueryService service = new QueryService(repository, connectionService, accessScopeService);
        ReflectionTestUtils.setField(service, "defaultMaxRows", 1000);
        ReflectionTestUtils.setField(service, "maxExportRows", 10000);
        ReflectionTestUtils.setField(service, "defaultTimeout", 30);
        ReflectionTestUtils.setField(service, "maxConcurrentPerUser", 2);
        ReflectionTestUtils.setField(service, "queueWaitSeconds", 1);
        ReflectionTestUtils.setField(service, "scannedBytesBudget", 100L);
        ReflectionTestUtils.setField(service, "cpuMsBudget", 100L);
        ReflectionTestUtils.setField(service, "peakMemoryBudget", 100L);

        QueryExecuteDTO dto = new QueryExecuteDTO();
        dto.setSql("SELECT id, name FROM users");
        dto.setRequestId("trace-123");
        Map<String, Object> result = service.executeQuery(dto, 7L);

        assertEquals("success", result.get("status"));
        assertEquals("doris", result.get("engine"));
        assertEquals("rtdwh_paimon", result.get("catalog"));
        assertEquals("ods", result.get("database"));
        assertEquals("trace-123", result.get("traceId"));
        assertEquals(1, result.get("rowCount"));
        assertEquals(true, result.get("budgetExceeded"));
        assertEquals(150.0, result.get("costScore"));
        assertTrue(String.valueOf(result.get("budgetReason")).contains("扫描量"));
        assertTrue(result.get("queueWaitMs") instanceof Long);
        assertTrue(result.get("rows").toString().contains("Alice"));
        verify(session).execute("SWITCH `rtdwh_paimon`");
        verify(session).execute("USE `ods`");
        verify(accessScopeService).validateDoris(7L, "SELECT id, name FROM users", "rtdwh_paimon", "ods");
    }
}
