package com.rtdwh.service;

import com.rtdwh.dto.UserAdminDTO;
import com.rtdwh.entity.SysPermission;
import com.rtdwh.entity.SysRole;
import com.rtdwh.entity.SysUser;
import com.rtdwh.entity.RoleDataScope;
import com.rtdwh.repository.RoleDataScopeRepository;
import com.rtdwh.repository.SysPermissionRepository;
import com.rtdwh.repository.SysRoleRepository;
import com.rtdwh.repository.SysUserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class UserAdminService {
    private static final Pattern USERNAME = Pattern.compile("^[A-Za-z][A-Za-z0-9_.-]{2,63}$");
    private static final Pattern ROLE_CODE = Pattern.compile("^[A-Z][A-Z0-9_]{1,31}$");
    private static final Set<String> BUILTIN_ROLES = Set.of("ADMIN", "DEVELOPER", "VISITOR");
    private static final Pattern SCOPE_PATTERN = Pattern.compile("^[A-Za-z0-9_.?*-]{1,128}$");

    private final SysUserRepository userRepository;
    private final SysRoleRepository roleRepository;
    private final SysPermissionRepository permissionRepository;
    private final PasswordEncoder passwordEncoder;
    private final RoleDataScopeRepository scopeRepository;

    @Transactional(readOnly = true)
    public List<UserAdminDTO.UserView> users() {
        return userRepository.findAll().stream()
                .sorted(Comparator.comparing(SysUser::getId))
                .map(this::userView).toList();
    }

    @Transactional
    public UserAdminDTO.UserView create(UserAdminDTO.CreateUserRequest request) {
        String username = request.getUsername().trim();
        if (!USERNAME.matcher(username).matches()) {
            throw new IllegalArgumentException("用户名需以字母开头，只能包含字母、数字、点、横线和下划线");
        }
        if (userRepository.existsByUsername(username)) throw new IllegalStateException("用户名已存在");
        SysUser user = SysUser.builder()
                .username(username)
                .passwordHash(passwordEncoder.encode(request.getPassword()))
                .realName(trim(request.getRealName()))
                .email(trim(request.getEmail()))
                .phone(trim(request.getPhone()))
                .status(SysUser.UserStatus.active)
                .roles(resolveRoles(request.getRoleIds()))
                .build();
        return userView(userRepository.save(user));
    }

    @Transactional
    public UserAdminDTO.UserView update(Long id, UserAdminDTO.UpdateUserRequest request) {
        SysUser user = requireUser(id);
        user.setRealName(trim(request.getRealName()));
        user.setEmail(trim(request.getEmail()));
        user.setPhone(trim(request.getPhone()));
        user.setRoles(resolveRoles(request.getRoleIds()));
        return userView(userRepository.save(user));
    }

    @Transactional
    public UserAdminDTO.UserView toggleStatus(Long id, Long operatorId) {
        if (id.equals(operatorId)) throw new IllegalStateException("不能停用当前登录账号");
        SysUser user = requireUser(id);
        user.setStatus(user.getStatus() == SysUser.UserStatus.active
                ? SysUser.UserStatus.disabled : SysUser.UserStatus.active);
        return userView(userRepository.save(user));
    }

    @Transactional
    public void resetPassword(Long id, String password) {
        SysUser user = requireUser(id);
        user.setPasswordHash(passwordEncoder.encode(password));
        userRepository.save(user);
    }

    @Transactional(readOnly = true)
    public List<UserAdminDTO.RoleView> roles() {
        return roleRepository.findAll().stream()
                .sorted(Comparator.comparing(SysRole::getId)).map(this::roleView).toList();
    }

    @Transactional(readOnly = true)
    public List<UserAdminDTO.PermissionView> permissions() {
        return permissionRepository.findAll().stream()
                .sorted(Comparator.comparing(SysPermission::getSortOrder,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .map(this::permissionView).toList();
    }

    @Transactional
    public UserAdminDTO.RoleView createRole(UserAdminDTO.RoleRequest request) {
        String code = request.getRoleCode().trim().toUpperCase();
        if (!ROLE_CODE.matcher(code).matches()) throw new IllegalArgumentException("角色编码格式不正确");
        if (roleRepository.existsByRoleCode(code)) throw new IllegalStateException("角色编码已存在");
        SysRole role = SysRole.builder().roleCode(code).roleName(request.getRoleName().trim())
                .description(trim(request.getDescription()))
                .permissions(resolvePermissions(request.getPermissionIds())).build();
        role = roleRepository.save(role);
        replaceScopes(role.getId(), request.getDataScopes());
        return roleView(role);
    }

    @Transactional
    public UserAdminDTO.RoleView updateRole(Long id, UserAdminDTO.RoleRequest request) {
        SysRole role = requireRole(id);
        if (BUILTIN_ROLES.contains(role.getRoleCode())) {
            throw new IllegalStateException("内置角色不允许修改，请创建自定义角色");
        }
        role.setRoleName(request.getRoleName().trim());
        role.setDescription(trim(request.getDescription()));
        role.setPermissions(resolvePermissions(request.getPermissionIds()));
        role = roleRepository.save(role);
        replaceScopes(role.getId(), request.getDataScopes());
        return roleView(role);
    }

    @Transactional
    public void deleteRole(Long id) {
        SysRole role = requireRole(id);
        if (BUILTIN_ROLES.contains(role.getRoleCode())) throw new IllegalStateException("内置角色不能删除");
        if (userRepository.existsByRoles_Id(id)) throw new IllegalStateException("角色仍被用户使用，不能删除");
        scopeRepository.deleteByRoleId(id);
        roleRepository.delete(role);
    }

    private SysUser requireUser(Long id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("用户不存在: " + id));
    }

    private SysRole requireRole(Long id) {
        return roleRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("角色不存在: " + id));
    }

    private Set<SysRole> resolveRoles(Set<Long> ids) {
        if (ids == null || ids.isEmpty()) throw new IllegalArgumentException("至少选择一个角色");
        List<SysRole> roles = roleRepository.findAllById(ids);
        if (roles.size() != ids.size()) throw new IllegalArgumentException("包含不存在的角色");
        return new HashSet<>(roles);
    }

    private Set<SysPermission> resolvePermissions(Set<Long> ids) {
        if (ids == null || ids.isEmpty()) return new HashSet<>();
        List<SysPermission> permissions = permissionRepository.findAllById(ids);
        if (permissions.size() != ids.size()) throw new IllegalArgumentException("包含不存在的权限");
        return new HashSet<>(permissions);
    }

    private UserAdminDTO.UserView userView(SysUser user) {
        Set<UserAdminDTO.RoleSummary> roles = user.getRoles().stream()
                .map(role -> new UserAdminDTO.RoleSummary(role.getId(), role.getRoleCode(), role.getRoleName()))
                .collect(java.util.stream.Collectors.toSet());
        return new UserAdminDTO.UserView(user.getId(), user.getUsername(), user.getRealName(),
                user.getEmail(), user.getPhone(), user.getStatus().name(), roles,
                user.getCreatedAt(), user.getUpdatedAt());
    }

    private UserAdminDTO.RoleView roleView(SysRole role) {
        Set<UserAdminDTO.PermissionView> permissions = role.getPermissions() == null ? Set.of()
                : role.getPermissions().stream().map(this::permissionView)
                .collect(java.util.stream.Collectors.toSet());
        return new UserAdminDTO.RoleView(role.getId(), role.getRoleCode(), role.getRoleName(),
                role.getDescription(), permissions, scopeRepository.findByRoleIdOrderById(role.getId()).stream()
                .map(scope -> new UserAdminDTO.DataScopeView(scope.getId(), scope.getCatalogPattern(),
                        scope.getDatabasePattern(), scope.getTablePattern())).toList());
    }

    private void replaceScopes(Long roleId, List<UserAdminDTO.DataScopeRequest> requests) {
        scopeRepository.deleteByRoleId(roleId);
        // Execute removals before inserting retained patterns with the same unique key.
        scopeRepository.flush();
        if (requests == null || requests.isEmpty()) return;
        Set<String> unique = new HashSet<>();
        List<RoleDataScope> scopes = requests.stream().map(request -> {
            String catalog = scope(request.getCatalogPattern());
            String database = scope(request.getDatabasePattern());
            String table = scope(request.getTablePattern());
            if (!unique.add(catalog + "\u0000" + database + "\u0000" + table)) {
                throw new IllegalArgumentException("数据范围不能重复");
            }
            return RoleDataScope.builder().roleId(roleId).catalogPattern(catalog)
                    .databasePattern(database).tablePattern(table).build();
        }).toList();
        scopeRepository.saveAll(scopes);
    }

    private String scope(String value) {
        String normalized = value == null ? "" : value.trim();
        if (!SCOPE_PATTERN.matcher(normalized).matches()) {
            throw new IllegalArgumentException("数据范围仅允许字母、数字、点、横线、下划线及 * ? 通配符");
        }
        return normalized;
    }

    private UserAdminDTO.PermissionView permissionView(SysPermission permission) {
        return new UserAdminDTO.PermissionView(permission.getId(), permission.getPermCode(),
                permission.getPermName(), permission.getResourceType().name());
    }

    private String trim(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
