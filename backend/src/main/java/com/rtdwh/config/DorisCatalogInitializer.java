package com.rtdwh.config;

import com.rtdwh.service.DorisConnectionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.Statement;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "doris", name = "initialize-catalog", havingValue = "true", matchIfMissing = true)
public class DorisCatalogInitializer implements ApplicationRunner {

    private final DorisConnectionService dorisConnectionService;
    private volatile String initializedCatalog;

    @Value("${doris.paimon-warehouse-path}")
    private String warehousePath;

    @Value("${doris.paimon-jdbc-uri}")
    private String paimonJdbcUri;

    @Value("${paimon.jdbc-user}")
    private String paimonJdbcUser;

    @Value("${paimon.jdbc-password}")
    private String paimonJdbcPassword;

    @Value("${paimon.catalog-key:rtdwh}")
    private String paimonCatalogKey;

    @Value("${doris.paimon-jdbc-driver-url}")
    private String driverUrl;

    @Value("${doris.paimon-table-cache-ttl-seconds:0}")
    private long paimonTableCacheTtlSeconds;

    @Override
    public void run(ApplicationArguments args) {
        initializeIfNeeded();
    }

    @Scheduled(
            fixedDelayString = "${doris.catalog-init.retry-ms:30000}",
            initialDelayString = "${doris.catalog-init.retry-ms:30000}"
    )
    public synchronized void initializeIfNeeded() {
        if (!dorisConnectionService.isEnabled()) return;
        String catalog = dorisConnectionService.getCatalog();
        if (catalog.equals(initializedCatalog)) return;
        String sql = "CREATE CATALOG IF NOT EXISTS " + DorisConnectionService.quoteIdentifier(catalog)
                + " PROPERTIES ("
                + "\"type\"=\"paimon\","
                + "\"paimon.catalog.type\"=\"jdbc\","
                + "\"paimon.jdbc.uri\"='" + escape(paimonJdbcUri) + "',"
                + "\"paimon.jdbc.user\"='" + escape(paimonJdbcUser) + "',"
                + "\"paimon.jdbc.password\"='" + escape(paimonJdbcPassword) + "',"
                + "\"paimon.catalog-key\"='" + escape(paimonCatalogKey) + "',"
                + "\"paimon.jdbc.driver_class\"=\"com.mysql.cj.jdbc.Driver\","
                + "\"paimon.jdbc.driver_url\"='" + escape(driverUrl) + "',"
                + "\"meta.cache.paimon.table.ttl-second\"='" + paimonTableCacheTtlSeconds + "',"
                + "\"warehouse\"='" + escape(normalizeWarehouse(warehousePath)) + "'"
                + ")";
        try (Connection connection = dorisConnectionService.getConnection();
             Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(30);
            statement.execute(sql);
            // CREATE CATALOG IF NOT EXISTS does not update properties on an existing
            // catalog. Apply the snapshot-cache policy explicitly so CDC commits are
            // visible through Doris without requiring a manual REFRESH CATALOG.
            statement.execute("ALTER CATALOG " + DorisConnectionService.quoteIdentifier(catalog)
                    + " SET PROPERTIES (\"meta.cache.paimon.table.ttl-second\"='"
                    + paimonTableCacheTtlSeconds + "')");
            initializedCatalog = catalog;
            log.info("Doris Paimon Catalog [{}] is ready", catalog);
            initializeWorkloadGroup(statement);
        } catch (Exception exception) {
            // Keep the control plane available while Doris is still starting. Health check exposes the cause.
            log.warn("Doris Catalog initialization deferred and will retry: {}", exception.getMessage());
        }
    }

    private void initializeWorkloadGroup(Statement statement) {
        try {
            statement.execute("CREATE WORKLOAD GROUP IF NOT EXISTS "
                    + DorisConnectionService.quoteIdentifier(dorisConnectionService.getWorkloadGroup())
                    + " PROPERTIES ("
                    + "\"max_concurrency\"=\"10\","
                    + "\"max_queue_size\"=\"20\","
                    + "\"queue_timeout\"=\"5000\""
                    + ")");
        } catch (Exception exception) {
            log.warn("Doris workload group initialization skipped: {}", exception.getMessage());
        }
    }

    private String normalizeWarehouse(String path) {
        if (path.matches("^[a-zA-Z][a-zA-Z0-9+.-]*://.+")) return path;
        return path.startsWith("/") ? "file://" + path : path;
    }

    private String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("'", "''");
    }
}
