package com.rtdwh.controller;

import com.rtdwh.entity.*;
import com.rtdwh.repository.*;
import com.rtdwh.service.*;
import com.rtdwh.util.SecurityContextUtil;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AssetControllerTest {
    @Test void assetIdAndChildRoutesCannotBypassStoredCatalogScope() {
        var tables = mock(DwhTableMetaRepository.class); var schemas = mock(AssetSchemaService.class);
        var context = mock(AssetContextService.class); var access = mock(QueryAccessScopeService.class); var security = mock(SecurityContextUtil.class);
        var controller = new AssetController(tables, schemas, context, access, security);
        var table = DwhTableMeta.builder().id(7L).assetId("asset").catalogName("external").paimonDb("ods").paimonTable("private").build();
        when(tables.findByAssetId("asset")).thenReturn(Optional.of(table)); when(security.getCurrentUserId()).thenReturn(2L);
        assertThrows(AccessDeniedException.class, () -> controller.detail("asset"));
        assertThrows(AccessDeniedException.class, () -> controller.revisions("asset"));
        assertThrows(AccessDeniedException.class, () -> controller.context("asset"));
        verify(access, times(3)).allowed(2L,"external","ods","private"); verifyNoInteractions(schemas,context);
        when(access.allowed(2L,"external","ods","private")).thenReturn(true);
        assertEquals(table, controller.detail("asset").getData()); controller.revisions("asset"); controller.context("asset");
        verify(schemas).history(7L); verify(context).context(table,2L);
    }
}
