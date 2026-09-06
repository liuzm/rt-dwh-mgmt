package com.rtdwh.service;

import com.rtdwh.dto.UserAdminDTO;
import com.rtdwh.entity.SysRole;
import com.rtdwh.entity.SysUser;
import com.rtdwh.repository.SysPermissionRepository;
import com.rtdwh.repository.SysRoleRepository;
import com.rtdwh.repository.SysUserRepository;
import com.rtdwh.repository.RoleDataScopeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserAdminServiceTest {
    @Mock SysUserRepository userRepository;
    @Mock SysRoleRepository roleRepository;
    @Mock SysPermissionRepository permissionRepository;
    @Mock PasswordEncoder passwordEncoder;
    @Mock RoleDataScopeRepository scopeRepository;
    UserAdminService service;

    @BeforeEach
    void setUp() {
        service = new UserAdminService(userRepository, roleRepository, permissionRepository,
                passwordEncoder, scopeRepository);
    }

    @Test
    void createsUserWithHashedPasswordAndResolvedRole() {
        SysRole role = SysRole.builder().id(2L).roleCode("DEVELOPER").roleName("开发者").build();
        when(roleRepository.findAllById(Set.of(2L))).thenReturn(List.of(role));
        when(passwordEncoder.encode("strong-password")).thenReturn("bcrypt-hash");
        when(userRepository.save(any())).thenAnswer(invocation -> {
            SysUser user = invocation.getArgument(0);
            user.setId(10L);
            return user;
        });
        UserAdminDTO.CreateUserRequest request = new UserAdminDTO.CreateUserRequest();
        request.setUsername("data.dev");
        request.setPassword("strong-password");
        request.setRealName(" 数据开发 ");
        request.setRoleIds(Set.of(2L));

        UserAdminDTO.UserView created = service.create(request);

        assertThat(created.id()).isEqualTo(10L);
        assertThat(created.realName()).isEqualTo("数据开发");
        verify(passwordEncoder).encode("strong-password");
        verify(userRepository).save(argThat(user -> "bcrypt-hash".equals(user.getPasswordHash())));
    }

    @Test
    void preventsDisablingCurrentOperator() {
        assertThatThrownBy(() -> service.toggleStatus(7L, 7L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("当前登录账号");
        verifyNoInteractions(userRepository);
    }

    @Test
    void protectsBuiltInRoleFromDeletion() {
        SysRole admin = SysRole.builder().id(1L).roleCode("ADMIN").roleName("管理员").build();
        when(roleRepository.findById(1L)).thenReturn(Optional.of(admin));

        assertThatThrownBy(() -> service.deleteRole(1L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("内置角色");
        verify(roleRepository, never()).delete(any());
    }
}
