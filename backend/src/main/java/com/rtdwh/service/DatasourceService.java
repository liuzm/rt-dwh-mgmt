package com.rtdwh.service;

import com.rtdwh.entity.DatasourceConfig;
import com.rtdwh.repository.DatasourceConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class DatasourceService {

    private final DatasourceConfigRepository datasourceConfigRepository;

    @Transactional(readOnly = true)
    public List<DatasourceConfig> listDatasources() {
        // Paimon is platform runtime configuration managed under Settings, not
        // a per-task business datasource. Keep legacy rows readable by ID so
        // historical tasks remain inspectable, but do not offer them for new work.
        return datasourceConfigRepository.findAll().stream()
                .filter(item -> item.getDbType() != DatasourceConfig.DbType.paimon)
                .toList();
    }

    @Transactional(readOnly = true)
    public DatasourceConfig getDatasource(Long id) {
        return datasourceConfigRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("数据源不存在: " + id));
    }

    @Transactional
    public DatasourceConfig createDatasource(DatasourceConfig config, Long creatorId) {
        if (config.getDbType() == DatasourceConfig.DbType.paimon) {
            throw new IllegalArgumentException("Paimon Catalog 由平台设置统一管理，无需创建数据源");
        }
        config.setId(null);
        config.setCreatorId(creatorId);
        return datasourceConfigRepository.save(config);
    }

    @Transactional
    public DatasourceConfig updateDatasource(Long id, DatasourceConfig config) {
        DatasourceConfig existing = getDatasource(id);
        if (existing.getDbType() == DatasourceConfig.DbType.paimon) {
            throw new IllegalStateException("旧版 Paimon 数据源已停用，请在系统设置中维护平台 Paimon Catalog");
        }

        // Update only user-editable fields on the managed entity. Replacing it
        // with the request body would clear creatorId/createdAt and other fields
        // that are intentionally absent from the edit form.
        if (config.getConfigName() != null && !config.getConfigName().isBlank()) {
            existing.setConfigName(config.getConfigName().trim());
        }
        if (config.getHost() != null && !config.getHost().isBlank()) {
            existing.setHost(config.getHost().trim());
        }
        if (config.getPort() != null) {
            existing.setPort(config.getPort());
        }
        if (config.getDatabase() != null && !config.getDatabase().isBlank()) {
            existing.setDatabase(config.getDatabase().trim());
        }
        if (config.getUsername() != null && !config.getUsername().isBlank()) {
            existing.setUsername(config.getUsername().trim());
        }
        if (config.getPasswordEncrypted() != null && !config.getPasswordEncrypted().isBlank()) {
            existing.setPasswordEncrypted(config.getPasswordEncrypted());
        }
        existing.setExtraParams(config.getExtraParams());

        return datasourceConfigRepository.save(existing);
    }

    @Transactional
    public void deleteDatasource(Long id) {
        getDatasource(id);
        datasourceConfigRepository.deleteById(id);
    }
}
