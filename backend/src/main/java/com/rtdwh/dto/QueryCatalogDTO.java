package com.rtdwh.dto;

import java.util.List;

public record QueryCatalogDTO(
        String catalogName,
        String catalogKey,
        List<DatabaseInfo> databases
) {
    public record DatabaseInfo(String name, List<TableInfo> tables) {}
    public record TableInfo(String name, String layer, List<ColumnInfo> columns) {}
    public record ColumnInfo(String name, String type, boolean primaryKey, boolean nullable) {}
}
