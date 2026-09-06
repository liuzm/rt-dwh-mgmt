package com.rtdwh.service;

import com.rtdwh.entity.RoleDataScope;
import com.rtdwh.entity.SysRole;
import com.rtdwh.entity.SysUser;
import com.rtdwh.repository.RoleDataScopeRepository;
import com.rtdwh.repository.SysUserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class QueryAccessScopeServiceTest {
    @Test void disabledOwnerCannotKeepServingThroughApplicationCredentials() {
        var admin = SysRole.builder().id(1L).roleCode("ADMIN").build();
        when(userRepository.findById(1L)).thenReturn(Optional.of(SysUser.builder().id(1L)
                .status(SysUser.UserStatus.disabled).roles(Set.of(admin)).build()));
        assertThrows(IllegalArgumentException.class, () -> service.assertDataServiceExecutionIdentity(1L));
    }
    @Test void apiOwnerMustRetainTheServicePermission() {
        var permission = com.rtdwh.entity.SysPermission.builder().permCode("data-service:manage").build();
        var role = SysRole.builder().id(2L).roleCode("DEVELOPER").permissions(Set.of(permission)).build();
        var owner = SysUser.builder().id(7L).status(SysUser.UserStatus.active).roles(Set.of(role)).build();
        when(userRepository.findById(7L)).thenReturn(Optional.of(owner));
        assertDoesNotThrow(() -> service.assertDataServiceExecutionIdentity(7L));
        role.setPermissions(Set.of());
        assertThrows(IllegalArgumentException.class, () -> service.assertDataServiceExecutionIdentity(7L));
    }
    private final SysUserRepository userRepository = mock(SysUserRepository.class);
    private final RoleDataScopeRepository scopeRepository = mock(RoleDataScopeRepository.class);
    private final QueryAccessScopeService service = new QueryAccessScopeService(userRepository, scopeRepository, mock(ViewDependencyService.class));

    @BeforeEach
    void setUp() {
        SysRole developer = SysRole.builder().id(2L).roleCode("CUSTOM_ANALYST").build();
        SysUser user = SysUser.builder().id(7L).roles(Set.of(developer)).build();
        when(userRepository.findById(7L)).thenReturn(Optional.of(user));
        when(scopeRepository.findByRoleIdIn(List.of(2L))).thenReturn(List.of(RoleDataScope.builder()
                .roleId(2L).catalogPattern("rtdwh_paimon").databasePattern("ods")
                .tablePattern("ods_order_*").build()));
    }

    @Test
    void permitsOnlyTablesMatchingRoleScope() {
        assertDoesNotThrow(() -> service.validate(7L,
                "SELECT * FROM rtdwh_paimon.ods.ods_order_detail", "rtdwh_paimon", "ods"));
        assertThrows(IllegalArgumentException.class, () -> service.validate(7L,
                "SELECT * FROM rtdwh_paimon.ads.ads_revenue", "rtdwh_paimon", "ods"));
    }

    @Test
    void validatesEveryPhysicalTableInJoinAndCte() {
        assertDoesNotThrow(() -> service.validate(7L,
                "WITH recent AS (SELECT * FROM ods_order_detail) "
                        + "SELECT * FROM recent JOIN ods_order_header h ON recent.id=h.id",
                "rtdwh_paimon", "ods"));
        assertThrows(IllegalArgumentException.class, () -> service.validate(7L,
                "SELECT * FROM ods_order_detail d JOIN secret_user u ON d.user_id=u.id",
                "rtdwh_paimon", "ods"));
    }

    @Test
    void adminBypassesTableScope() {
        SysRole admin = SysRole.builder().id(1L).roleCode("ADMIN").build();
        when(userRepository.findById(1L)).thenReturn(Optional.of(
                SysUser.builder().id(1L).roles(Set.of(admin)).build()));
        assertDoesNotThrow(() -> service.validate(1L, "SELECT * FROM any_db.any_table",
                "rtdwh_paimon", "ods"));
    }

    @Test
    void filtersFoundationAssetsWithTheSameDataScope() {
        record Asset(String catalog, String database, String table) {}
        List<Asset> filtered = service.filterAllowed(7L, List.of(
                        new Asset("rtdwh_paimon", "ods", "ods_order_detail"),
                        new Asset("rtdwh_paimon", "ads", "ads_revenue")),
                Asset::catalog, Asset::database, Asset::table);

        assertEquals(List.of(new Asset("rtdwh_paimon", "ods", "ods_order_detail")), filtered);
    }
}
