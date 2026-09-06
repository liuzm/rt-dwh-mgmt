package com.rtdwh.service;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DorisCatalogServiceTest {

    @Test
    void readsDatabasesTablesAndColumnsFromConfiguredDorisCatalog() throws Exception {
        DorisConnectionService connectionService = mock(DorisConnectionService.class);
        QueryAccessScopeService accessScopeService = mock(QueryAccessScopeService.class);
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        when(connectionService.getCatalog()).thenReturn("rtdwh_paimon");
        when(connectionService.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(accessScopeService.allowed(7L, "rtdwh_paimon", "ods", "ods_quality_rule")).thenReturn(true);

        ResultSet databases = singleColumnRows("ods", "information_schema");
        ResultSet tables = singleColumnRows("ods_quality_rule");
        ResultSet columns = mock(ResultSet.class);
        ResultSetMetaData metadata = mock(ResultSetMetaData.class);
        when(columns.getMetaData()).thenReturn(metadata);
        when(metadata.getColumnCount()).thenReturn(4);
        when(metadata.getColumnLabel(1)).thenReturn("Field");
        when(metadata.getColumnLabel(2)).thenReturn("Type");
        when(metadata.getColumnLabel(3)).thenReturn("Null");
        when(metadata.getColumnLabel(4)).thenReturn("Key");
        when(columns.next()).thenReturn(true, false);
        when(columns.getString(1)).thenReturn("id");
        when(columns.getString(2)).thenReturn("BIGINT");
        when(columns.getString(3)).thenReturn("NO");
        when(columns.getString(4)).thenReturn("PRI");

        when(statement.executeQuery("SHOW DATABASES")).thenReturn(databases);
        when(statement.executeQuery("SHOW TABLES FROM `rtdwh_paimon`.`ods`")).thenReturn(tables);
        when(statement.executeQuery("DESCRIBE `rtdwh_paimon`.`ods`.`ods_quality_rule`")).thenReturn(columns);

        var catalog = new DorisCatalogService(connectionService, accessScopeService).getQueryCatalog(7L);

        assertEquals("rtdwh_paimon", catalog.catalogName());
        assertEquals("doris", catalog.catalogKey());
        assertEquals(1, catalog.databases().size());
        assertEquals("ods_quality_rule", catalog.databases().get(0).tables().get(0).name());
        assertEquals("id", catalog.databases().get(0).tables().get(0).columns().get(0).name());
    }

    @Test
    void rejectsUnsafeDorisIdentifiers() {
        assertEquals("`ods`", DorisConnectionService.quoteIdentifier("ods"));
        assertThrows(IllegalArgumentException.class,
                () -> DorisConnectionService.quoteIdentifier("ods; DROP CATALOG internal"));
    }

    private ResultSet singleColumnRows(String... values) throws Exception {
        ResultSet resultSet = mock(ResultSet.class);
        Boolean[] sequence = new Boolean[values.length + 1];
        for (int index = 0; index < values.length; index++) sequence[index] = true;
        sequence[values.length] = false;
        when(resultSet.next()).thenReturn(sequence[0], java.util.Arrays.copyOfRange(sequence, 1, sequence.length));
        if (values.length > 0) {
            when(resultSet.getString(1)).thenReturn(values[0], java.util.Arrays.copyOfRange(values, 1, values.length));
        }
        return resultSet;
    }
}
