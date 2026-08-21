package com.rtdwh.service;

import com.rtdwh.dto.SavedQueryUpsertDTO;
import com.rtdwh.entity.SavedQuery;
import com.rtdwh.repository.SavedQueryRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SavedQueryServiceTest {

    private final SavedQueryRepository repository = mock(SavedQueryRepository.class);
    private final SavedQueryService service = new SavedQueryService(repository);

    @Test
    void createsUserOwnedSavedQuery() {
        SavedQueryUpsertDTO dto = dto("ODS 检查", "SELECT * FROM ods.t");
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        SavedQuery saved = service.create(7L, dto);

        assertEquals(7L, saved.getUserId());
        assertEquals("ODS 检查", saved.getName());
        verify(repository).existsByUserIdAndName(7L, "ODS 检查");
    }

    @Test
    void preventsUpdatingAnotherUsersQuery() {
        when(repository.findByIdAndUserId(10L, 7L)).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class,
                () -> service.update(10L, 7L, dto("名称", "SELECT 1")));
    }

    private SavedQueryUpsertDTO dto(String name, String sql) {
        SavedQueryUpsertDTO dto = new SavedQueryUpsertDTO();
        dto.setName(name);
        dto.setSqlText(sql);
        return dto;
    }
}
