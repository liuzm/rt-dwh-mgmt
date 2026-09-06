package com.rtdwh.config;

import com.rtdwh.entity.DatasourceConfig;
import com.rtdwh.entity.DatasourceConfig.DbType;
import com.rtdwh.entity.SysRole;
import com.rtdwh.entity.SysPermission;
import com.rtdwh.entity.SysUser;
import com.rtdwh.entity.SysUser.UserStatus;
import com.rtdwh.repository.DatasourceConfigRepository;
import com.rtdwh.repository.SysRoleRepository;
import com.rtdwh.repository.SysPermissionRepository;
import com.rtdwh.repository.SysUserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class DataInitializer implements CommandLineRunner {

    private final SysUserRepository userRepo;
    private final SysRoleRepository roleRepo;
    private final SysPermissionRepository permissionRepo;
    private final DatasourceConfigRepository datasourceRepo;
    private final PasswordEncoder passwordEncoder;

    @Value("${app.init-users.enabled:false}")
    private boolean initUsersEnabled;

    @Value("${app.init-users.admin-password:}")
    private String adminDefaultPassword;

    @Value("${app.init-users.dev-password:}")
    private String devDefaultPassword;

    @Value("${app.init-users.guest-password:}")
    private String guestDefaultPassword;

    @Override
    @Transactional
    public void run(String... args) {
        initRoles();
        initPermissions();
        if (initUsersEnabled) {
            initUsers();
            initDatasources();
        } else {
            if (userRepo.count() == 0) {
                log.error("数据库中没有用户：admin 登录不可用。首次部署请设置 INIT_USERS_ENABLED=true、INIT_ADMIN_PASSWORD（以及 dev/guest 密码）后重启。");
            } else {
                log.info("初始化用户/数据源已禁用（app.init-users.enabled=false），跳过");
            }
        }
    }

    private void initRoles() {
        log.info("初始化角色数据...");
        saveRoleIfMissing("ADMIN", "管理员", "拥有全部权限");
        saveRoleIfMissing("DEVELOPER", "开发者", "可创建/管理任务和报表");
        saveRoleIfMissing("VISITOR", "访客", "仅可查看数据和报表");
        log.info("角色初始化完成：ADMIN, DEVELOPER, VISITOR");
    }

    private void saveRoleIfMissing(String code, String name, String description) {
        if (!roleRepo.existsByRoleCode(code)) {
            roleRepo.save(SysRole.builder().roleCode(code).roleName(name).description(description).build());
        }
    }

    private void initPermissions() {
        Map<String, String> definitions = new LinkedHashMap<>();
        definitions.put("task:view", "查看同步任务");
        definitions.put("task:create", "创建同步任务");
        definitions.put("task:manage", "管理同步任务");
        definitions.put("datasource:view", "查看数据源");
        definitions.put("datasource:manage", "管理数据源");
        definitions.put("dwh:view", "查看数仓元数据");
        definitions.put("dwh:manage", "管理数仓元数据");
        definitions.put("query:adhoc", "执行即席查询");
        definitions.put("report:view", "查看报表");
        definitions.put("report:create", "管理报表");
        definitions.put("quality:view", "查看数据质量");
        definitions.put("quality:manage", "管理数据质量");
        definitions.put("lineage:view", "查看数据血缘");
        definitions.put("alert:view", "查看告警");
        definitions.put("alert:manage", "管理告警");
        definitions.put("settings:view", "查看系统状态");
        definitions.put("settings:manage", "管理系统设置");
        definitions.put("audit:view", "查看操作审计");
        definitions.put("user:manage", "管理用户与角色");
        definitions.put("data-service:view", "查看数据服务");
        definitions.put("data-service:manage", "管理数据服务");
        definitions.put("foundation:view", "查看公共能力治理中心");
        int order = 1;
        for (Map.Entry<String, String> definition : definitions.entrySet()) {
            if (!permissionRepo.existsByPermCode(definition.getKey())) {
                permissionRepo.save(SysPermission.builder().permCode(definition.getKey())
                        .permName(definition.getValue()).resourceType(SysPermission.ResourceType.api)
                        .sortOrder(order).build());
            }
            order++;
        }
        Map<String, SysPermission> all = permissionRepo.findAll().stream()
                .collect(Collectors.toMap(SysPermission::getPermCode, permission -> permission));
        assignPermissions("ADMIN", all.keySet(), all);
        assignPermissions("DEVELOPER", Set.of("task:view", "task:create", "task:manage",
                "datasource:view", "datasource:manage", "dwh:view", "dwh:manage", "query:adhoc",
                "report:view", "report:create", "quality:view", "quality:manage", "lineage:view",
                "alert:view", "settings:view", "data-service:view", "data-service:manage", "foundation:view"), all);
        assignPermissions("VISITOR", Set.of("task:view", "datasource:view", "dwh:view",
                "query:adhoc", "report:view", "quality:view", "lineage:view", "alert:view",
                "settings:view", "foundation:view"), all);
    }

    private void assignPermissions(String roleCode, Set<String> codes, Map<String, SysPermission> all) {
        SysRole role = roleRepo.findByRoleCode(roleCode).orElseThrow();
        role.setPermissions(codes.stream().map(all::get).filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet()));
        roleRepo.save(role);
    }

    private void initUsers() {
        if (userRepo.existsByUsername("admin")) {
            log.info("用户数据已存在，跳过初始化");
            return;
        }

        log.info("初始化用户数据...");

        SysRole adminRole = roleRepo.findByRoleCode("ADMIN").orElseThrow();
        SysRole devRole = roleRepo.findByRoleCode("DEVELOPER").orElseThrow();
        SysRole visitorRole = roleRepo.findByRoleCode("VISITOR").orElseThrow();

        String adminPwd = validateInitPassword(adminDefaultPassword, "admin");
        String devPwd = validateInitPassword(devDefaultPassword, "dev01");
        String guestPwd = validateInitPassword(guestDefaultPassword, "guest");

        // admin
        SysUser admin = SysUser.builder()
                .username("admin")
                .passwordHash(passwordEncoder.encode(adminPwd))
                .realName("管理员")
                .status(UserStatus.active)
                .roles(Set.of(adminRole))
                .build();
        userRepo.save(admin);

        // dev01
        SysUser dev01 = SysUser.builder()
                .username("dev01")
                .passwordHash(passwordEncoder.encode(devPwd))
                .realName("开发者01")
                .status(UserStatus.active)
                .roles(Set.of(devRole))
                .build();
        userRepo.save(dev01);

        // guest
        SysUser guest = SysUser.builder()
                .username("guest")
                .passwordHash(passwordEncoder.encode(guestPwd))
                .realName("访客")
                .status(UserStatus.active)
                .roles(Set.of(visitorRole))
                .build();
        userRepo.save(guest);

        log.info("用户初始化完成（请使用环境变量配置密码）");
    }

    private String validateInitPassword(String pwd, String username) {
        if (pwd == null || pwd.isBlank()) {
            throw new IllegalStateException("已启用用户初始化，但未配置 " +
                    "INIT_" + username.toUpperCase() + "_PASSWORD；请设置密码后重启");
        }
        return pwd;
    }

    private void initDatasources() {
        if (datasourceRepo.count() > 0) {
            log.info("数据源数据已存在，跳过初始化");
            return;
        }

        log.info("初始化数据源数据...");

        // Use placeholder values — real credentials should be configured via frontend
        datasourceRepo.save(DatasourceConfig.builder()
                .creatorId(1L)
                .configName("MySQL-示例库")
                .dbType(DbType.mysql)
                .host("localhost")
                .port(3306)
                .database("example_db")
                .username("root")
                .passwordEncrypted("")
                .extraParams("{\"useSSL\":false,\"serverTimezone\":\"Asia/Shanghai\"}")
                .build());

        log.info("数据源初始化完成（示例数据，请通过前端配置真实数据源）");
    }
}
