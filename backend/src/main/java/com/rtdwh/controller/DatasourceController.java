package com.rtdwh.controller;

import com.rtdwh.dto.ApiResponse;
import com.rtdwh.entity.DatasourceConfig;
import com.rtdwh.entity.DatasourceConfig.DbType;
import com.rtdwh.service.DatasourceIntrospectionService;
import com.rtdwh.service.DatasourceService;
import com.rtdwh.util.EncryptionUtil;
import com.rtdwh.util.SecurityContextUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/datasources")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('datasource:view')")
public class DatasourceController {

    private final DatasourceService datasourceService;
    private final DatasourceIntrospectionService introspectionService;
    private final EncryptionUtil encryptionUtil;
    private final SecurityContextUtil securityContextUtil;

    @GetMapping
    public ApiResponse<List<DatasourceConfig>> listDatasources() {
        return ApiResponse.success(datasourceService.listDatasources());
    }

    @PostMapping
    @PreAuthorize("hasAuthority('datasource:manage')")
    public ApiResponse<DatasourceConfig> createDatasource(@RequestBody DatasourceConfig config) {
        Long creatorId = securityContextUtil.getCurrentUserId();
        // Encrypt password before saving
        config.setPasswordEncrypted(encryptionUtil.encrypt(config.getPasswordEncrypted()));
        return ApiResponse.success("Datasource created", datasourceService.createDatasource(config, creatorId));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('datasource:manage')")
    public ApiResponse<DatasourceConfig> updateDatasource(@PathVariable Long id, @RequestBody DatasourceConfig config) {
        DatasourceConfig existing = datasourceService.getDatasource(id);
        // Preserve encrypted password if not changed
        if (config.getPasswordEncrypted() != null
                && !config.getPasswordEncrypted().isBlank()
                && !config.getPasswordEncrypted().equals(existing.getPasswordEncrypted())) {
            config.setPasswordEncrypted(encryptionUtil.encrypt(config.getPasswordEncrypted()));
        } else {
            config.setPasswordEncrypted(existing.getPasswordEncrypted());
        }
        return ApiResponse.success(datasourceService.updateDatasource(id, config));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('datasource:manage')")
    public ApiResponse<Void> deleteDatasource(@PathVariable Long id) {
        datasourceService.deleteDatasource(id);
        return ApiResponse.success("Datasource deleted", null);
    }

    @GetMapping("/{id}/test-connection")
    public ApiResponse<Map<String, Object>> testConnection(@PathVariable Long id) {
        DatasourceConfig config = datasourceService.getDatasource(id);
        if (config.getDbType() == DbType.paimon) {
            throw new IllegalArgumentException("Paimon Catalog 请在系统设置的依赖健康中检测");
        }

        try {
            String decryptedPassword = encryptionUtil.decrypt(config.getPasswordEncrypted());
            String url = switch (config.getDbType()) {
                case mysql -> "jdbc:mysql://" + config.getHost() + ":" + config.getPort() + "/" + config.getDatabase();
                case postgresql -> "jdbc:postgresql://" + config.getHost() + ":" + config.getPort() + "/" + config.getDatabase();
                case paimon -> throw new IllegalArgumentException("Paimon Catalog 请在系统设置中检测");
            };

            java.sql.Connection conn = java.sql.DriverManager.getConnection(
                    url, config.getUsername(), decryptedPassword);
            String dbVersion = conn.getMetaData().getDatabaseProductName() + " " + conn.getMetaData().getDatabaseProductVersion();
            conn.close();

            return ApiResponse.success(Map.of("success", true, "message", "Connection established", "dbVersion", dbVersion));
        } catch (Exception e) {
            return ApiResponse.error(400, "Connection failed: " + e.getMessage());
        }
    }

    /**
     * List all tables from a datasource.
     */
    @GetMapping("/{id}/tables")
    public ApiResponse<List<String>> listTables(@PathVariable Long id) {
        return ApiResponse.success(introspectionService.listTables(id));
    }

    /**
     * Get column structure of a table.
     */
    @GetMapping("/{id}/tables/{tableName}")
    public ApiResponse<Map<String, Object>> introspectTable(
            @PathVariable Long id,
            @PathVariable String tableName) {
        return ApiResponse.success(introspectionService.introspectTable(id, tableName));
    }
}
