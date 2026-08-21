package com.rtdwh.service;

import com.rtdwh.dto.SavedQueryUpsertDTO;
import com.rtdwh.entity.SavedQuery;
import com.rtdwh.repository.SavedQueryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class SavedQueryService {

    private final SavedQueryRepository repository;

    @Transactional(readOnly = true)
    public List<SavedQuery> list(Long userId) {
        return repository.findByUserIdOrderByUpdatedAtDesc(userId);
    }

    @Transactional
    public SavedQuery create(Long userId, SavedQueryUpsertDTO dto) {
        String name = dto.getName().trim();
        if (repository.existsByUserIdAndName(userId, name)) {
            throw new IllegalArgumentException("已存在同名 SQL，请选择覆盖保存");
        }
        return repository.save(SavedQuery.builder()
                .userId(userId)
                .name(name)
                .sqlText(dto.getSqlText())
                .description(trimToNull(dto.getDescription()))
                .tags(trimToNull(dto.getTags()))
                .build());
    }

    @Transactional
    public SavedQuery update(Long id, Long userId, SavedQueryUpsertDTO dto) {
        SavedQuery saved = getOwned(id, userId);
        String name = dto.getName().trim();
        if (repository.existsByUserIdAndNameAndIdNot(userId, name, id)) {
            throw new IllegalArgumentException("已存在同名 SQL");
        }
        saved.setName(name);
        saved.setSqlText(dto.getSqlText());
        saved.setDescription(trimToNull(dto.getDescription()));
        saved.setTags(trimToNull(dto.getTags()));
        return repository.save(saved);
    }

    @Transactional
    public void delete(Long id, Long userId) {
        repository.delete(getOwned(id, userId));
    }

    private SavedQuery getOwned(Long id, Long userId) {
        return repository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new IllegalArgumentException("SQL 记录不存在或无权访问"));
    }

    private String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
