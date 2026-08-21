package com.rtdwh.service;

import com.rtdwh.entity.DwhColumnMeta;
import com.rtdwh.entity.DwhTableMeta;
import com.rtdwh.dto.DwhSnapshotDTO;
import com.rtdwh.dto.QueryCatalogDTO;
import com.rtdwh.entity.DwhTableMeta.TableLayer;
import com.rtdwh.entity.TableMaintenanceLog;
import com.rtdwh.entity.TableMaintenanceLog.Operation;
import com.rtdwh.entity.TableMaintenanceLog.Status;
import com.rtdwh.entity.TableMaintenanceLog.TriggerType;
import com.rtdwh.repository.DwhColumnMetaRepository;
import com.rtdwh.repository.DwhTableMetaRepository;
import com.rtdwh.repository.TableMaintenanceLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.sql.*;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class DwhMetaService {

    private final DwhTableMetaRepository tableMetaRepository;
    private final DwhColumnMetaRepository columnMetaRepository;
    private final TableMaintenanceLogRepository maintenanceLogRepository;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Value("${paimon.jdbc-uri}")
    private String paimonJdbcUri;

    @Value("${paimon.jdbc-user}")
    private String paimonJdbcUser;

    @Value("${paimon.jdbc-password}")
    private String paimonJdbcPassword;

    @Value("${paimon.warehouse-path}")
    private String paimonWarehousePath;

    @Value("${paimon.catalog-key}")
    private String paimonCatalogKey;

    @Value("${flink.sql-gateway.enabled:false}")
    private boolean sqlGatewayEnabled;

    @Value("${flink.sql-gateway.url:http://localhost:9083}")
    private String sqlGatewayUrl;

    @Value("${flink.rest-api.url:http://localhost:8081}")
    private String flinkRestUrl;

    public List<DwhTableMeta> listTables(TableLayer layer, String database, String keyword) {
        return tableMetaRepository.searchTables(layer, database, keyword);
    }

    public DwhTableMeta getTableDetail(Long id) {
        return tableMetaRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Table not found: " + id));
    }

    public List<DwhColumnMeta> getTableColumns(Long tableMetaId) {
        return columnMetaRepository.findByTableMetaIdOrderBySortOrder(tableMetaId);
    }

    @Transactional(readOnly = true)
    public QueryCatalogDTO getQueryCatalog() {
        Map<String, List<QueryCatalogDTO.TableInfo>> grouped = new TreeMap<>();
        for (DwhTableMeta table : tableMetaRepository.findAll()) {
            List<QueryCatalogDTO.ColumnInfo> columns = getTableColumns(table.getId()).stream()
                    .map(column -> new QueryCatalogDTO.ColumnInfo(
                            column.getColumnName(),
                            column.getColumnType(),
                            Boolean.TRUE.equals(column.getIsPk()),
                            Boolean.TRUE.equals(column.getIsNullable())))
                    .toList();
            grouped.computeIfAbsent(table.getPaimonDb(), ignored -> new ArrayList<>())
                    .add(new QueryCatalogDTO.TableInfo(
                            table.getPaimonTable(), table.getLayer().name(), columns));
        }
        List<QueryCatalogDTO.DatabaseInfo> databases = grouped.entrySet().stream()
                .map(entry -> new QueryCatalogDTO.DatabaseInfo(
                        entry.getKey(),
                        entry.getValue().stream()
                                .sorted(Comparator.comparing(QueryCatalogDTO.TableInfo::name))
                                .toList()))
                .toList();
        return new QueryCatalogDTO("paimon", paimonCatalogKey, databases);
    }

    @Transactional
    public DwhTableMeta updateBusinessDesc(Long id, String businessDesc) {
        DwhTableMeta table = getTableDetail(id);
        table.setBusinessDesc(businessDesc);
        return tableMetaRepository.save(table);
    }

    @Transactional
    public DwhColumnMeta updateColumnComment(Long columnId, String comment) {
        DwhColumnMeta column = columnMetaRepository.findById(columnId)
                .orElseThrow(() -> new RuntimeException("Column not found: " + columnId));
        column.setBusinessComment(comment);
        return columnMetaRepository.save(column);
    }

    /**
     * Sync metadata from Paimon JDBC metastore.
     *
     * Paimon JDBC metastore stores catalog metadata in MySQL tables:
     * - paimon_catalog_{catalogKey}_database: databases
     * - paimon_catalog_{catalogKey}_table: tables with schema, partition/primary keys, options
     * - paimon_catalog_{catalogKey}_table_column: column definitions
     * - paimon_catalog_{catalogKey}_table_option: table options (snapshot-related)
     *
     * Strategy:
     * 1. Query Paimon metastore MySQL for all databases and tables
     * 2. Parse schema JSON, partition keys, primary keys
     * 3. Upsert into dwh_table_meta (infer layer from db name convention)
     * 4. Upsert into dwh_column_meta
     * 5. Also try to get snapshot metrics from Paimon table option records
     */
    @Transactional
    public int syncMetadataFromPaimon() {
        log.info("Starting Paimon metadata sync from metastore: {}", paimonJdbcUri);

        int syncedCount = 0;

        try (Connection conn = DriverManager.getConnection(paimonJdbcUri, paimonJdbcUser, paimonJdbcPassword)) {
            if (tableExists(conn, "paimon_tables")) {
                return syncPaimonUnifiedMetastore(conn);
            }

            // Step 1: Get all databases from Paimon metastore
            String dbTable = "paimon_catalog_" + paimonCatalogKey + "_database";
            List<String> databases = new ArrayList<>();

            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT `database_name` FROM " + dbTable)) {
                while (rs.next()) {
                    databases.add(rs.getString("database_name"));
                }
            } catch (SQLException e) {
                log.warn("Cannot query Paimon database table ({}), metastore may not be initialized yet: {}", dbTable, e.getMessage());
                // Fallback: try scanning all tables in the metastore MySQL
                databases = discoverPaimonDatabasesFallback(conn);
            }

            log.info("Found {} Paimon databases: {}", databases.size(), databases);

            // Step 2: For each database, get all tables
            String tableMetaTable = "paimon_catalog_" + paimonCatalogKey + "_table";
            String colMetaTable = "paimon_catalog_" + paimonCatalogKey + "_table_column";
            String tableOptTable = "paimon_catalog_" + paimonCatalogKey + "_table_option";

            for (String db : databases) {
                // Infer layer from database name convention: ods_db → ods, dwd_db → dwd, etc.
                TableLayer layer = inferLayer(db);

                // Query tables in this database
                List<PaimonTableInfo> paimonTables = new ArrayList<>();
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT `table_id`, `table_name`, `schema`, `partition_keys`, `primary_keys`, `options` FROM " + tableMetaTable + " WHERE `database_name` = ?")) {
                    ps.setString(1, db);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            PaimonTableInfo info = new PaimonTableInfo();
                            info.tableId = rs.getString("table_id");
                            info.tableName = rs.getString("table_name");
                            info.schemaJson = rs.getString("schema");
                            info.partitionKeys = rs.getString("partition_keys");
                            info.primaryKeys = rs.getString("primary_keys");
                            info.options = rs.getString("options");
                            paimonTables.add(info);
                        }
                    }
                } catch (SQLException e) {
                    log.warn("Cannot query tables for database '{}': {}", db, e.getMessage());
                    continue;
                }

                // Step 3: Upsert into dwh_table_meta
                for (PaimonTableInfo pt : paimonTables) {
                    Optional<DwhTableMeta> existing = tableMetaRepository.findByPaimonDbAndPaimonTable(db, pt.tableName);

                    DwhTableMeta tableMeta;
                    if (existing.isPresent()) {
                        tableMeta = existing.get();
                    } else {
                        tableMeta = new DwhTableMeta();
                        tableMeta.setPaimonDb(db);
                        tableMeta.setPaimonTable(pt.tableName);
                        tableMeta.setLayer(layer);
                    }

                    // Update fields from Paimon metastore
                    tableMeta.setSchemaJson(pt.schemaJson);
                    tableMeta.setPartitionKeys(pt.partitionKeys != null ? pt.partitionKeys : "");
                    tableMeta.setPrimaryKeys(pt.primaryKeys != null ? pt.primaryKeys : "");

                    // Parse snapshot metrics from options (Paimon stores snapshot.count etc. as options)
                    parseSnapshotMetrics(tableMeta, pt.options);

                    // Upsert columns
                    upsertColumns(conn, colMetaTable, db, pt.tableName, tableMeta, pt.schemaJson);

                    tableMetaRepository.save(tableMeta);
                    syncedCount++;
                    log.debug("Synced table: {}.{} (layer={})", db, pt.tableName, layer);
                }
            }

            // Step 4: Remove stale entries (tables in our DB but no longer in Paimon)
            removeStaleTables(databases, conn, tableMetaTable);

            log.info("Paimon metadata sync completed. {} tables processed.", syncedCount);

        } catch (SQLException e) {
            log.error("Failed to connect to Paimon metastore: {}", e.getMessage());
            throw new RuntimeException("Paimon metastore connection failed: " + e.getMessage(), e);
        }

        return syncedCount;
    }

    /** Sync Paimon 2.x JDBC catalog metadata. */
    private int syncPaimonUnifiedMetastore(Connection conn) throws SQLException {
        List<String[]> catalogTables = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT `database_name`, `table_name` FROM `paimon_tables` "
                        + "WHERE `catalog_key` = ? ORDER BY `database_name`, `table_name`")) {
            ps.setString(1, paimonCatalogKey);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    catalogTables.add(new String[]{rs.getString(1), rs.getString(2)});
                }
            }
        }

        Set<String> activeTables = new HashSet<>();
        for (String[] catalogTable : catalogTables) {
            String database = catalogTable[0];
            String tableName = catalogTable[1];
            activeTables.add(database + "." + tableName);

            DwhTableMeta tableMeta = tableMetaRepository
                    .findByPaimonDbAndPaimonTable(database, tableName)
                    .orElseGet(DwhTableMeta::new);
            tableMeta.setPaimonDb(database);
            tableMeta.setPaimonTable(tableName);
            tableMeta.setLayer(inferLayer(database));

            Map<String, String> properties = loadUnifiedTableProperties(conn, database, tableName);
            if (!properties.isEmpty()) {
                tableMeta.setPrimaryKeys(properties.getOrDefault("primary-key", ""));
                tableMeta.setPartitionKeys(properties.getOrDefault("partition", ""));
                try {
                    parseSnapshotMetrics(tableMeta, objectMapper.writeValueAsString(properties));
                } catch (Exception e) {
                    log.debug("Unable to serialize Paimon properties for {}.{}", database, tableName);
                }
            }

            enrichTableFromWarehouse(tableMeta);
            DwhTableMeta saved = tableMetaRepository.save(tableMeta);
            upsertColumns(conn, null, database, tableName, saved, saved.getSchemaJson());
        }

        removeStaleUnifiedTables(activeTables);
        log.info("Paimon 2.x metadata sync completed. {} tables processed.", catalogTables.size());
        return catalogTables.size();
    }

    private Map<String, String> loadUnifiedTableProperties(Connection conn, String database,
                                                            String tableName) throws SQLException {
        Map<String, String> properties = new LinkedHashMap<>();
        if (!tableExists(conn, "paimon_table_properties")) return properties;
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT `property_key`, `property_value` FROM `paimon_table_properties` "
                        + "WHERE `catalog_key` = ? AND `database_name` = ? AND `table_name` = ?")) {
            ps.setString(1, paimonCatalogKey);
            ps.setString(2, database);
            ps.setString(3, tableName);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) properties.put(rs.getString(1), rs.getString(2));
            }
        }
        return properties;
    }

    private boolean tableExists(Connection conn, String tableName) throws SQLException {
        try (ResultSet rs = conn.getMetaData().getTables(conn.getCatalog(), null, tableName,
                new String[]{"TABLE"})) {
            return rs.next();
        }
    }

    private void enrichTableFromWarehouse(DwhTableMeta tableMeta) {
        Optional<Path> tablePath = resolveLocalTablePath(tableMeta);
        if (tablePath.isEmpty()) {
            log.debug("Warehouse is not a local filesystem path; skipping file enrichment for {}.{}",
                    tableMeta.getPaimonDb(), tableMeta.getPaimonTable());
            return;
        }
        Path root = tablePath.get();
        try {
            latestNumberedFile(root.resolve("schema"), "schema-").ifPresent(schemaFile -> {
                try {
                    var schema = objectMapper.readTree(schemaFile.toFile());
                    tableMeta.setSchemaJson(schema.toString());
                    tableMeta.setPartitionKeys(joinJsonArray(schema.path("partitionKeys")));
                    tableMeta.setPrimaryKeys(joinJsonArray(schema.path("primaryKeys")));
                    String comment = schema.path("comment").asText("").trim();
                    if ((tableMeta.getBusinessDesc() == null || tableMeta.getBusinessDesc().isBlank())
                            && !comment.isBlank()) {
                        tableMeta.setBusinessDesc(comment);
                    }
                } catch (IOException e) {
                    log.warn("Failed to read Paimon schema {}: {}", schemaFile, e.getMessage());
                }
            });

            List<DwhSnapshotDTO> snapshots = readSnapshots(root);
            tableMeta.setSnapshotCount(snapshots.size());
            if (!snapshots.isEmpty()) {
                DwhSnapshotDTO latest = snapshots.get(0);
                tableMeta.setLatestSnapshotId(latest.snapshotId());
                tableMeta.setLatestCommitTime(latest.commitTime());
                tableMeta.setRecordCount(latest.recordCount());
            } else {
                tableMeta.setLatestSnapshotId(null);
                tableMeta.setLatestCommitTime(null);
                tableMeta.setRecordCount(0L);
            }

            long fileCount = 0;
            long totalSize = 0;
            if (Files.isDirectory(root)) {
                try (var paths = Files.walk(root)) {
                    for (Path path : paths.filter(Files::isRegularFile).toList()) {
                        String name = path.getFileName().toString();
                        if (name.startsWith("data-") || name.startsWith("changelog-")) {
                            fileCount++;
                            totalSize += Files.size(path);
                        }
                    }
                }
            }
            tableMeta.setFileCount(Math.toIntExact(Math.min(fileCount, Integer.MAX_VALUE)));
            tableMeta.setTotalSizeBytes(totalSize);
        } catch (IOException e) {
            log.warn("Failed to inspect Paimon warehouse table {}: {}", root, e.getMessage());
        }
    }

    public List<DwhSnapshotDTO> getTableSnapshots(Long tableMetaId) {
        DwhTableMeta table = getTableDetail(tableMetaId);
        return resolveLocalTablePath(table).map(this::readSnapshots).orElseGet(List::of);
    }

    private List<DwhSnapshotDTO> readSnapshots(Path tablePath) {
        Path snapshotDir = tablePath.resolve("snapshot");
        if (!Files.isDirectory(snapshotDir)) return List.of();
        List<DwhSnapshotDTO> result = new ArrayList<>();
        try (var paths = Files.list(snapshotDir)) {
            for (Path file : paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().matches("snapshot-\\d+"))
                    .toList()) {
                try {
                    var node = objectMapper.readTree(file.toFile());
                    long timeMillis = node.path("timeMillis").asLong(0);
                    LocalDateTime commitTime = timeMillis > 0
                            ? LocalDateTime.ofInstant(Instant.ofEpochMilli(timeMillis), ZoneId.systemDefault())
                            : null;
                    result.add(new DwhSnapshotDTO(
                            node.path("id").asLong(),
                            node.path("schemaId").asLong(),
                            node.path("commitKind").asText("UNKNOWN"),
                            commitTime,
                            node.path("totalRecordCount").asLong(),
                            node.path("deltaRecordCount").asLong(),
                            node.path("baseManifestListSize").asLong()
                                    + node.path("deltaManifestListSize").asLong()));
                } catch (IOException e) {
                    log.debug("Skip unreadable snapshot {}: {}", file, e.getMessage());
                }
            }
        } catch (IOException e) {
            log.warn("Failed to list snapshots in {}: {}", snapshotDir, e.getMessage());
        }
        result.sort(Comparator.comparingLong(DwhSnapshotDTO::snapshotId).reversed());
        return result;
    }

    private Optional<Path> resolveLocalTablePath(DwhTableMeta table) {
        try {
            String location = paimonWarehousePath.trim();
            URI uri = URI.create(location);
            if (uri.getScheme() != null && !"file".equalsIgnoreCase(uri.getScheme())) {
                return Optional.empty();
            }
            Path warehouse = uri.getScheme() == null ? Path.of(location) : Path.of(uri);
            Path normalizedWarehouse = warehouse.toAbsolutePath().normalize();
            Path tablePath = normalizedWarehouse
                    .resolve(table.getPaimonDb() + ".db")
                    .resolve(table.getPaimonTable())
                    .normalize();
            return tablePath.startsWith(normalizedWarehouse) ? Optional.of(tablePath) : Optional.empty();
        } catch (Exception e) {
            log.debug("Unsupported warehouse path {}: {}", paimonWarehousePath, e.getMessage());
            return Optional.empty();
        }
    }

    private Optional<Path> latestNumberedFile(Path directory, String prefix) throws IOException {
        if (!Files.isDirectory(directory)) return Optional.empty();
        try (var paths = Files.list(directory)) {
            return paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().matches(prefix + "\\d+"))
                    .max(Comparator.comparingLong(path -> Long.parseLong(
                            path.getFileName().toString().substring(prefix.length()))));
        }
    }

    private String joinJsonArray(com.fasterxml.jackson.databind.JsonNode node) {
        if (!node.isArray()) return "";
        List<String> values = new ArrayList<>();
        node.forEach(value -> values.add(value.asText()));
        return String.join(",", values);
    }

    private void removeStaleUnifiedTables(Set<String> activeTables) {
        for (DwhTableMeta existing : tableMetaRepository.findAll()) {
            String key = existing.getPaimonDb() + "." + existing.getPaimonTable();
            if (!activeTables.contains(key)) {
                columnMetaRepository.deleteAll(
                        columnMetaRepository.findByTableMetaIdOrderBySortOrder(existing.getId()));
                tableMetaRepository.delete(existing);
            }
        }
    }

    /**
     * Fallback: discover Paimon databases by scanning metastore table names
     */
    private List<String> discoverPaimonDatabasesFallback(Connection conn) throws SQLException {
        List<String> databases = new ArrayList<>();
        DatabaseMetaData meta = conn.getMetaData();
        try (ResultSet rs = meta.getTables(null, null, "paimon_catalog_" + paimonCatalogKey + "_database", null)) {
            if (rs.next()) {
                // Table exists, try querying with different column names
                try (Statement stmt = conn.createStatement();
                     ResultSet dbRs = stmt.executeQuery("SELECT * FROM paimon_catalog_" + paimonCatalogKey + "_database")) {
                    while (dbRs.next()) {
                        // Try different column name conventions
                        String dbName = dbRs.getString("database_name");
                        if (dbName == null) dbName = dbRs.getString("DATABASE_NAME");
                        if (dbName == null) dbName = dbRs.getString("name");
                        if (dbName != null) databases.add(dbName);
                    }
                }
            }
        }
        return databases;
    }

    /**
     * Infer ODS/DWD/DWS/ADS layer from Paimon database name convention.
     * Convention: ods_{name} → ods, dwd_{name} → dwd, dws_{name} → dws, ads_{name} → ads
     * Default: ods (raw layer)
     */
    private TableLayer inferLayer(String dbName) {
        String lower = dbName.toLowerCase();
        if (lower.startsWith("ods") || lower.equals("ods")) return TableLayer.ods;
        if (lower.startsWith("dwd") || lower.equals("dwd")) return TableLayer.dwd;
        if (lower.startsWith("dws") || lower.equals("dws")) return TableLayer.dws;
        if (lower.startsWith("ads") || lower.equals("ads")) return TableLayer.ads;
        // Default: try to match by common naming
        if (lower.contains("raw")) return TableLayer.ods;
        if (lower.contains("detail")) return TableLayer.dwd;
        if (lower.contains("summary") || lower.contains("service")) return TableLayer.dws;
        if (lower.contains("report") || lower.contains("app")) return TableLayer.ads;
        // Fallback to ods
        log.info("Cannot infer layer for database '{}', defaulting to ods", dbName);
        return TableLayer.ods;
    }

    /**
     * Parse snapshot-related metrics from Paimon table options JSON.
     * Paimon stores these as options: snapshot.count, snapshot.latest-id, etc.
     */
    private void parseSnapshotMetrics(DwhTableMeta tableMeta, String optionsJson) {
        if (optionsJson == null || optionsJson.isEmpty()) return;
        try {
            // Paimon options is stored as a JSON map: {"key1":"value1","key2":"value2"}
            // Try to parse key metrics
            Map<String, String> options = parseSimpleJsonMap(optionsJson);

            if (options.containsKey("snapshot.count")) {
                tableMeta.setSnapshotCount(Integer.parseInt(options.get("snapshot.count")));
            }
            if (options.containsKey("snapshot.latest-id")) {
                tableMeta.setLatestSnapshotId(Long.parseLong(options.get("snapshot.latest-id")));
            }
            if (options.containsKey("file.count")) {
                tableMeta.setFileCount(Integer.parseInt(options.get("file.count")));
            }
            if (options.containsKey("total-size")) {
                tableMeta.setTotalSizeBytes(Long.parseLong(options.get("total-size")));
            }
        } catch (Exception e) {
            log.debug("Failed to parse Paimon options for table: {}", e.getMessage());
        }
    }

    /**
     * Parse a simple JSON map like {"k1":"v1","k2":"v2"} without Jackson overhead.
     */
    private Map<String, String> parseSimpleJsonMap(String json) {
        Map<String, String> map = new HashMap<>();
        if (json == null || json.trim().isEmpty()) return map;
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            @SuppressWarnings("unchecked")
            Map<String, Object> raw = mapper.readValue(json, Map.class);
            for (Map.Entry<String, Object> entry : raw.entrySet()) {
                map.put(entry.getKey(), String.valueOf(entry.getValue()));
            }
        } catch (Exception e) {
            log.debug("JSON parse failed for options, skipping: {}", e.getMessage());
        }
        return map;
    }

    /**
     * Upsert columns for a Paimon table into dwh_column_meta.
     * Two sources: 1) Paimon metastore column table, 2) Schema JSON fallback.
     */
    private void upsertColumns(Connection conn, String colMetaTable, String db, String tableName,
                               DwhTableMeta tableMeta, String schemaJson) {
        // First try Paimon metastore column table
        List<DwhColumnMeta> newColumns = new ArrayList<>();
        boolean fromMetastore = false;

        if (colMetaTable != null) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT `column_name`, `column_type`, `comment`, `is_nullable`, `is_primary_key`, `default_value`, `sort_order` FROM " + colMetaTable + " WHERE `database_name` = ? AND `table_name` = ? ORDER BY `sort_order`")) {
                ps.setString(1, db);
                ps.setString(2, tableName);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        DwhColumnMeta col = new DwhColumnMeta();
                        col.setColumnName(rs.getString("column_name"));
                        col.setColumnType(rs.getString("column_type"));
                        col.setBusinessComment(rs.getString("comment"));
                        col.setIsNullable(parseBooleanSafe(rs.getString("is_nullable"), true));
                        col.setIsPk(parseBooleanSafe(rs.getString("is_primary_key"), false));
                        col.setDefaultValue(rs.getString("default_value"));
                        col.setSortOrder(rs.getInt("sort_order"));
                        newColumns.add(col);
                        fromMetastore = true;
                    }
                }
            } catch (SQLException e) {
                log.debug("Paimon column metastore query failed for {}.{}: {}", db, tableName, e.getMessage());
            }
        }

        // Fallback: parse columns from schema JSON
        if (!fromMetastore && schemaJson != null && !schemaJson.isEmpty()) {
            newColumns = parseColumnsFromSchemaJson(schemaJson, tableMeta.getPrimaryKeys());
        }

        if (newColumns.isEmpty()) return;

        // Save table first (may create new row with generated ID)
        DwhTableMeta saved = tableMetaRepository.save(tableMeta);
        Long tableMetaId = saved.getId();

        // Delete existing columns and re-insert (full replace strategy)
        List<DwhColumnMeta> existing = tableMetaId == null ? List.of()
                : columnMetaRepository.findByTableMetaIdOrderBySortOrder(tableMetaId);
        Map<String, DwhColumnMeta> existingByName = existing.stream().collect(Collectors.toMap(
                DwhColumnMeta::getColumnName, column -> column, (left, right) -> left));
        columnMetaRepository.deleteAll(existing);

        for (DwhColumnMeta col : newColumns) {
            DwhColumnMeta previous = existingByName.get(col.getColumnName());
            if (previous != null) {
                if (col.getBusinessComment() == null || col.getBusinessComment().isBlank()) {
                    col.setBusinessComment(previous.getBusinessComment());
                }
                col.setSourceColumn(previous.getSourceColumn());
            }
            col.setTableMetaId(tableMetaId);
            columnMetaRepository.save(col);
        }
    }

    /**
     * Parse columns from Paimon schema JSON.
     * Paimon schema JSON format: [{"name":"col1","type":"INT","comment":"desc","nullable":true}, ...]
     */
    List<DwhColumnMeta> parseColumnsFromSchemaJson(String schemaJson, String primaryKeys) {
        List<DwhColumnMeta> columns = new ArrayList<>();
        Set<String> pkSet = new HashSet<>();
        if (primaryKeys != null && !primaryKeys.isEmpty()) {
            pkSet = Arrays.stream(primaryKeys.split("[,;]"))
                    .map(String::trim)
                    .collect(Collectors.toSet());
        }

        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(schemaJson);
            com.fasterxml.jackson.databind.JsonNode fieldsNode = root.isArray() ? root : root.path("fields");
            if (!fieldsNode.isArray()) return columns;

            int sortOrder = 0;
            for (com.fasterxml.jackson.databind.JsonNode field : fieldsNode) {
                DwhColumnMeta col = new DwhColumnMeta();
                col.setColumnName(field.path("name").asText());
                String rawType = field.path("type").asText("STRING");
                boolean notNull = rawType.toUpperCase(Locale.ROOT).endsWith(" NOT NULL");
                col.setColumnType(rawType.replaceFirst("(?i)\\s+NOT NULL$", ""));
                col.setBusinessComment(field.path("comment").asText(null));
                col.setIsNullable(field.has("nullable") ? field.path("nullable").asBoolean(true) : !notNull);
                col.setIsPk(pkSet.contains(col.getColumnName()));
                col.setDefaultValue(field.hasNonNull("default") ? field.path("default").asText() : null);
                col.setSortOrder(sortOrder++);
                columns.add(col);
            }
        } catch (Exception e) {
            log.warn("Failed to parse Paimon schema JSON: {}", e.getMessage());
        }
        return columns;
    }

    private boolean parseBooleanSafe(String value, boolean defaultVal) {
        if (value == null) return defaultVal;
        return "true".equalsIgnoreCase(value) || "1".equals(value) || "yes".equalsIgnoreCase(value);
    }

    /**
     * Remove stale table entries: tables that exist in our management DB but no longer in Paimon.
     */
    private void removeStaleTables(List<String> paimonDatabases, Connection conn, String tableMetaTable) {
        List<DwhTableMeta> allExisting = tableMetaRepository.findAll();
        Set<String> activePaimonTables = new HashSet<>();

        for (String db : paimonDatabases) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT `table_name` FROM " + tableMetaTable + " WHERE `database_name` = ?")) {
                ps.setString(1, db);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        activePaimonTables.add(db + "." + rs.getString("table_name"));
                    }
                }
            } catch (SQLException e) {
                log.debug("Skip stale check for database '{}': {}", db, e.getMessage());
            }
        }

        for (DwhTableMeta existing : allExisting) {
            String key = existing.getPaimonDb() + "." + existing.getPaimonTable();
            if (!activePaimonTables.contains(key)) {
                log.info("Removing stale table entry: {}", key);
                // Delete columns first
                columnMetaRepository.findByTableMetaIdOrderBySortOrder(existing.getId())
                        .forEach(columnMetaRepository::delete);
                tableMetaRepository.delete(existing);
            }
        }
    }

    /**
     * Trigger table compaction via Flink SQL or Paimon API.
     * Uses SQL Gateway if available, otherwise records the request as a pending maintenance operation.
     */
    public Map<String, Object> triggerCompact(Long tableMetaId, String compactStrategy) {
        DwhTableMeta table = getTableDetail(tableMetaId);
        log.info("Triggering {} compact on table: {}.{}", compactStrategy, table.getPaimonDb(), table.getPaimonTable());

        String sql = String.format("CALL sys.compact('%s.%s', '%s')",
                table.getPaimonDb(), table.getPaimonTable(), compactStrategy);

        // Record maintenance log
        TableMaintenanceLog logEntry = TableMaintenanceLog.builder()
                .tableMetaId(tableMetaId)
                .operation(Operation.compact)
                .triggerType(TriggerType.manual)
                .status(Status.running)
                .sqlContent(sql)
                .startedAt(java.time.LocalDateTime.now())
                .build();
        maintenanceLogRepository.save(logEntry);

        Map<String, Object> result = executePaimonCall(sql, table, "compact", compactStrategy);

        // Update log status based on result
        String statusStr = (String) result.get("status");
        if ("pending".equals(statusStr)) {
            logEntry.setStatus(Status.pending);
        }
        logEntry.setOperationId((String) result.get("operationId"));
        maintenanceLogRepository.save(logEntry);

        return result;
    }

    /**
     * Trigger snapshot expiration via Paimon CALL procedure.
     */
    public Map<String, Object> triggerExpireSnapshots(Long tableMetaId, int retainLast) {
        DwhTableMeta table = getTableDetail(tableMetaId);
        log.info("Triggering expire snapshots on table: {}.{}, retainLast: {}", table.getPaimonDb(), table.getPaimonTable(), retainLast);

        // Paimon expire_snapshots: CALL sys.expire_snapshots('db.table', retainLast)
        String sql = String.format("CALL sys.expire_snapshots('%s.%s', %d)",
                table.getPaimonDb(), table.getPaimonTable(), retainLast);

        // Record maintenance log
        TableMaintenanceLog logEntry = TableMaintenanceLog.builder()
                .tableMetaId(tableMetaId)
                .operation(Operation.expire_snapshots)
                .triggerType(TriggerType.manual)
                .status(Status.running)
                .sqlContent(sql)
                .startedAt(java.time.LocalDateTime.now())
                .build();
        maintenanceLogRepository.save(logEntry);

        Map<String, Object> result = executePaimonCall(sql, table, "expire_snapshots", "retainLast=" + retainLast);

        String statusStr = (String) result.get("status");
        if ("pending".equals(statusStr)) {
            logEntry.setStatus(Status.pending);
        }
        logEntry.setOperationId((String) result.get("operationId"));
        maintenanceLogRepository.save(logEntry);

        return result;
    }

    /**
     * Trigger orphan file cleanup via Paimon CALL procedure.
     */
    public Map<String, Object> triggerOrphanCleanup(Long tableMetaId) {
        DwhTableMeta table = getTableDetail(tableMetaId);
        log.info("Triggering orphan cleanup on table: {}.{}", table.getPaimonDb(), table.getPaimonTable());

        String sql = String.format("CALL sys.remove_orphan_files('%s.%s')",
                table.getPaimonDb(), table.getPaimonTable());

        // Record maintenance log
        TableMaintenanceLog logEntry = TableMaintenanceLog.builder()
                .tableMetaId(tableMetaId)
                .operation(Operation.orphan_cleanup)
                .triggerType(TriggerType.manual)
                .status(Status.running)
                .sqlContent(sql)
                .startedAt(java.time.LocalDateTime.now())
                .build();
        maintenanceLogRepository.save(logEntry);

        Map<String, Object> result = executePaimonCall(sql, table, "orphan_cleanup", "");

        String statusStr = (String) result.get("status");
        if ("pending".equals(statusStr)) {
            logEntry.setStatus(Status.pending);
        }
        logEntry.setOperationId((String) result.get("operationId"));
        maintenanceLogRepository.save(logEntry);

        return result;
    }

    /** Trigger compaction for all matching tables. Individual failures do not stop the batch. */
    public Map<String, Object> batchCompact(TableLayer layer, int fileCountThreshold) {
        List<DwhTableMeta> candidates = listTables(layer, null, null).stream()
                .filter(table -> table.getFileCount() != null && table.getFileCount() >= fileCountThreshold)
                .toList();
        return runBatchMaintenance(candidates, table -> triggerCompact(table.getId(), "minor"));
    }

    /** Trigger snapshot expiration for all tables in the selected layer. */
    public Map<String, Object> batchExpireSnapshots(TableLayer layer, int retainLast) {
        return runBatchMaintenance(
                listTables(layer, null, null),
                table -> triggerExpireSnapshots(table.getId(), retainLast));
    }

    /** Trigger orphan-file cleanup for one table, or for all tables when the id is omitted. */
    public Map<String, Object> batchOrphanCleanup(Long tableMetaId) {
        List<DwhTableMeta> candidates = tableMetaId == null
                ? listTables(null, null, null)
                : List.of(getTableDetail(tableMetaId));
        return runBatchMaintenance(candidates, table -> triggerOrphanCleanup(table.getId()));
    }

    private Map<String, Object> runBatchMaintenance(
            List<DwhTableMeta> tables,
            java.util.function.Function<DwhTableMeta, Map<String, Object>> operation) {
        int triggered = 0;
        List<Map<String, Object>> failures = new ArrayList<>();
        for (DwhTableMeta table : tables) {
            try {
                operation.apply(table);
                triggered++;
            } catch (RuntimeException exception) {
                failures.add(Map.of(
                        "tableId", table.getId(),
                        "table", table.getPaimonDb() + "." + table.getPaimonTable(),
                        "message", exception.getMessage() == null ? "操作失败" : exception.getMessage()
                ));
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("triggered", triggered);
        result.put("failed", failures.size());
        result.put("failures", failures);
        return result;
    }

    /**
     * Get maintenance logs for a table or all tables.
     */
    public List<TableMaintenanceLog> getMaintenanceLogs(Long tableMetaId, Operation operation, Status status) {
        return maintenanceLogRepository.searchLogs(operation, status, tableMetaId);
    }

    /**
     * Execute a Paimon CALL procedure via Flink SQL Gateway.
     * If SQL Gateway is not available, creates a maintenance log entry with status=pending
     * that needs to be manually executed.
     */
    private Map<String, Object> executePaimonCall(String sql, DwhTableMeta table, String operation, String detail) {
        if (sqlGatewayEnabled) {
            try {
                // Submit via SQL Gateway using FlinkClusterService
                Map<String, Object> result = submitPaimonCallViaSqlGateway(sql);
                String operationId = (String) result.getOrDefault("operationId", String.valueOf(System.currentTimeMillis()));

                log.info("Paimon CALL submitted via SQL Gateway: operation={}, operationId={}", operation, operationId);
                return Map.of(
                        "operationId", operationId,
                        "status", "running",
                        "operation", operation,
                        "table", table.getPaimonDb() + "." + table.getPaimonTable(),
                        "detail", detail,
                        "message", operation + " triggered for " + table.getPaimonDb() + "." + table.getPaimonTable()
                );
            } catch (Exception e) {
                log.error("SQL Gateway submission failed for {}: {}", operation, e.getMessage());
                return Map.of(
                        "operationId", String.valueOf(System.currentTimeMillis()),
                        "status", "pending",
                        "operation", operation,
                        "table", table.getPaimonDb() + "." + table.getPaimonTable(),
                        "detail", detail,
                        "message", operation + " requires manual execution (SQL Gateway unavailable: " + e.getMessage() + ")"
                );
            }
        }

        // No SQL Gateway: log the operation as pending, return info for manual execution
        log.info("No SQL Gateway available. Paimon {} operation for {}.{} recorded as pending.",
                operation, table.getPaimonDb(), table.getPaimonTable());

        return Map.of(
                "operationId", String.valueOf(System.currentTimeMillis()),
                "status", "pending",
                "operation", operation,
                "table", table.getPaimonDb() + "." + table.getPaimonTable(),
                "detail", detail,
                "sql", sql,
                "message", operation + " operation recorded. Enable Flink SQL Gateway for automatic execution, or run manually: " + sql
        );
    }

    /**
     * Submit a Paimon CALL statement via Flink SQL Gateway.
     */
    private Map<String, Object> submitPaimonCallViaSqlGateway(String sql) {
        // Create session
        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);

        Map<String, Object> sessionPayload = Map.of("sessionConfig", Map.of());
        org.springframework.http.HttpEntity<Map<String, Object>> sessionReq =
                new org.springframework.http.HttpEntity<>(sessionPayload, headers);

        org.springframework.http.ResponseEntity<String> sessionResp = restTemplate.postForEntity(
                sqlGatewayUrl + "/v1/sessions", sessionReq, String.class);

        String sessionId = null;
        if (sessionResp.getStatusCode().is2xxSuccessful() && sessionResp.getBody() != null) {
            try {
                com.fasterxml.jackson.databind.JsonNode json = objectMapper.readTree(sessionResp.getBody());
                sessionId = json.path("sessionId").asText();
            } catch (Exception e) {
                throw new RuntimeException("Failed to parse SQL Gateway session response: " + e.getMessage());
            }
        }

        if (sessionId == null) {
            throw new RuntimeException("Failed to create SQL Gateway session");
        }

        try {
            // Execute CALL statement
            Map<String, Object> stmtPayload = new LinkedHashMap<>();
            stmtPayload.put("statement", sql);

            org.springframework.http.HttpEntity<Map<String, Object>> stmtReq =
                    new org.springframework.http.HttpEntity<>(stmtPayload, headers);

            org.springframework.http.ResponseEntity<String> stmtResp = restTemplate.postForEntity(
                    sqlGatewayUrl + "/v1/sessions/" + sessionId + "/statements",
                    stmtReq, String.class);

            if (stmtResp.getStatusCode().is2xxSuccessful() && stmtResp.getBody() != null) {
                try {
                    com.fasterxml.jackson.databind.JsonNode json = objectMapper.readTree(stmtResp.getBody());
                    String operationId = json.path("operationId").asText();
                    return Map.of("sessionId", sessionId, "operationId", operationId);
                } catch (Exception e) {
                    throw new RuntimeException("Failed to parse SQL Gateway statement response: " + e.getMessage());
                }
            }
            throw new RuntimeException("SQL Gateway statement submission failed: HTTP " + stmtResp.getStatusCode());
        } finally {
            // Clean up session
            try {
                restTemplate.delete(sqlGatewayUrl + "/v1/sessions/" + sessionId);
                log.debug("SQL Gateway session {} cleaned up after CALL", sessionId);
            } catch (Exception cleanupEx) {
                log.warn("Failed to clean up SQL Gateway session {}: {}", sessionId, cleanupEx.getMessage());
            }
        }
    }

    // Helper class for Paimon table info from metastore
    private static class PaimonTableInfo {
        String tableId;
        String tableName;
        String schemaJson;
        String partitionKeys;
        String primaryKeys;
        String options;
    }
}
