package com.rtdwh.service;

import com.rtdwh.dto.QueryCatalogDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class DorisCatalogService {

    private final DorisConnectionService dorisConnectionService;
    private final QueryAccessScopeService accessScopeService;
    private volatile QueryCatalogDTO cachedCatalog;
    private volatile long cachedAt;

    public synchronized QueryCatalogDTO getQueryCatalog(Long userId) {
        String catalog = dorisConnectionService.getCatalog();
        QueryCatalogDTO fullCatalog = loadCatalog(catalog);
        List<QueryCatalogDTO.DatabaseInfo> visible = fullCatalog.databases().stream()
                .map(database -> new QueryCatalogDTO.DatabaseInfo(database.name(), database.tables().stream()
                        .filter(table -> accessScopeService.allowed(userId, catalog, database.name(), table.name()))
                        .toList()))
                .filter(database -> !database.tables().isEmpty())
                .toList();
        return new QueryCatalogDTO(catalog, fullCatalog.catalogKey(), visible);
    }

    private synchronized QueryCatalogDTO loadCatalog(String catalog) {
        if (cachedCatalog != null && catalog.equals(cachedCatalog.catalogName())
                && System.currentTimeMillis() - cachedAt < 60_000L) return cachedCatalog;
        List<QueryCatalogDTO.DatabaseInfo> databases = new ArrayList<>();
        try (Connection connection = dorisConnectionService.getConnection();
             Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(20);
            statement.execute("SWITCH " + DorisConnectionService.quoteIdentifier(catalog));
            for (String database : readFirstColumn(statement, "SHOW DATABASES")) {
                if (isSystemDatabase(database)) continue;
                List<QueryCatalogDTO.TableInfo> tables = new ArrayList<>();
                String qualifiedDatabase = DorisConnectionService.quoteIdentifier(catalog) + "."
                        + DorisConnectionService.quoteIdentifier(database);
                for (String table : readFirstColumn(statement, "SHOW TABLES FROM " + qualifiedDatabase)) {
                    tables.add(new QueryCatalogDTO.TableInfo(
                            table,
                            inferLayer(database, table),
                            describeTable(statement, catalog, database, table)
                    ));
                }
                databases.add(new QueryCatalogDTO.DatabaseInfo(database, tables));
            }
            QueryCatalogDTO result = new QueryCatalogDTO(catalog, "doris", databases);
            cachedCatalog = result;
            cachedAt = System.currentTimeMillis();
            return result;
        } catch (Exception exception) {
            throw new IllegalStateException("读取 Doris Catalog 失败: " + safeMessage(exception), exception);
        }
    }

    private List<QueryCatalogDTO.ColumnInfo> describeTable(
            Statement statement,
            String catalog,
            String database,
            String table
    ) throws Exception {
        String qualifiedTable = DorisConnectionService.quoteIdentifier(catalog) + "."
                + DorisConnectionService.quoteIdentifier(database) + "."
                + DorisConnectionService.quoteIdentifier(table);
        List<QueryCatalogDTO.ColumnInfo> columns = new ArrayList<>();
        try (ResultSet resultSet = statement.executeQuery("DESCRIBE " + qualifiedTable)) {
            ResultSetMetaData metadata = resultSet.getMetaData();
            int fieldIndex = findColumn(metadata, "Field", 1);
            int typeIndex = findColumn(metadata, "Type", Math.min(2, metadata.getColumnCount()));
            int nullIndex = findColumn(metadata, "Null", -1);
            int keyIndex = findColumn(metadata, "Key", -1);
            while (resultSet.next()) {
                String name = resultSet.getString(fieldIndex);
                String type = resultSet.getString(typeIndex);
                boolean nullable = nullIndex < 0 || !"NO".equalsIgnoreCase(resultSet.getString(nullIndex));
                boolean primaryKey = keyIndex > 0 && "PRI".equalsIgnoreCase(resultSet.getString(keyIndex));
                columns.add(new QueryCatalogDTO.ColumnInfo(name, type, primaryKey, nullable));
            }
        }
        return columns;
    }

    private List<String> readFirstColumn(Statement statement, String sql) throws Exception {
        List<String> values = new ArrayList<>();
        try (ResultSet resultSet = statement.executeQuery(sql)) {
            while (resultSet.next()) values.add(resultSet.getString(1));
        }
        return values;
    }

    private int findColumn(ResultSetMetaData metadata, String label, int fallback) throws Exception {
        for (int index = 1; index <= metadata.getColumnCount(); index++) {
            if (label.equalsIgnoreCase(metadata.getColumnLabel(index))) return index;
        }
        return fallback;
    }

    private boolean isSystemDatabase(String database) {
        String normalized = database.toLowerCase(Locale.ROOT);
        return normalized.equals("information_schema") || normalized.equals("mysql");
    }

    private String inferLayer(String database, String table) {
        String normalizedDatabase = database.toLowerCase(Locale.ROOT);
        if (List.of("ods", "dwd", "dws", "ads", "dim").contains(normalizedDatabase)) {
            return normalizedDatabase;
        }
        String normalizedTable = table.toLowerCase(Locale.ROOT);
        for (String layer : List.of("ods", "dwd", "dws", "ads", "dim")) {
            if (normalizedTable.startsWith(layer + "_")) return layer;
        }
        return "other";
    }

    private String safeMessage(Exception exception) {
        return exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
    }
}
