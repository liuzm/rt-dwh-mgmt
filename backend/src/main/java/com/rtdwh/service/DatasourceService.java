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
        return datasourceConfigRepository.findAll();
    }

    @Transactional(readOnly = true)
    public DatasourceConfig getDatasource(Long id) {
        return datasourceConfigRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("数据源不存在: " + id));
    }

    @Transactional
    public DatasourceConfig createDatasource(DatasourceConfig config, Long creatorId) {
        config.setId(null);
        config.setCreatorId(creatorId);
        return datasourceConfigRepository.save(config);
    }

    @Transactional
    public DatasourceConfig updateDatasource(Long id, DatasourceConfig config) {
        DatasourceConfig existing = getDatasource(id);

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
