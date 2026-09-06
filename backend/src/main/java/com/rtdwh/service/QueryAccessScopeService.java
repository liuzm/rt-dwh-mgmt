package com.rtdwh.service;

import com.alibaba.druid.DbType;
import com.alibaba.druid.sql.SQLUtils;
import com.alibaba.druid.sql.ast.SQLStatement;
import com.alibaba.druid.sql.visitor.SchemaStatVisitor;
import com.rtdwh.entity.RoleDataScope;
import com.rtdwh.entity.SysUser;
import com.rtdwh.repository.RoleDataScopeRepository;
import com.rtdwh.repository.SysUserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.function.Function;

@Service
@RequiredArgsConstructor
public class QueryAccessScopeService {
    private final SysUserRepository userRepository;
    private final RoleDataScopeRepository scopeRepository;
    private final ViewDependencyService viewDependencies;

    @Transactional(readOnly = true)
    public void assertDataServiceExecutionIdentity(Long userId) {
        SysUser user = userRepository.findById(userId).orElseThrow(() -> new IllegalArgumentException("数据 API 执行用户不存在"));
        if (user.getStatus() != SysUser.UserStatus.active) throw new IllegalArgumentException("数据 API 执行用户已停用");
        boolean permitted = user.getRoles().stream().anyMatch(role -> "ADMIN".equals(role.getRoleCode())
                || role.getPermissions().stream().anyMatch(permission -> "data-service:manage".equals(permission.getPermCode())));
        if (!permitted) throw new IllegalArgumentException("数据 API 执行用户已失去服务权限");
    }

    @Transactional(readOnly = true)
    public void validateDorisSystem(String sql, String catalog, String database) {
        viewDependencies.validateQuery(sql, catalog, database, ignored -> true);
    }

    @Transactional(readOnly = true)
    public void validateDoris(Long userId, String sql, String catalog, String database) {
        validate(userId, sql, catalog, database);
        var statement = SQLUtils.parseSingleStatement(sql, DbType.mysql);
        if (statement instanceof com.alibaba.druid.sql.ast.statement.SQLExplainStatement explain) statement = explain.getStatement();
        if (statement instanceof com.alibaba.druid.sql.ast.statement.SQLSelectStatement) {
            viewDependencies.validateQuery(SQLUtils.toSQLString(statement, DbType.mysql), catalog, database,
                    name -> allowed(userId, name.catalog(), name.database(), name.table()));
        } else {
            for (String raw : extractTables(sql)) {
                var name = qualify(raw, catalog, database);
                viewDependencies.check(new ViewSqlService.Name(name.catalog(), name.database(), name.table()),
                        ref -> allowed(userId, ref.catalog(), ref.database(), ref.table()), new java.util.HashSet<>());
            }
        }
    }

    @Transactional(readOnly = true)
    public boolean canAccessDorisSql(Long userId, String sql, String catalog, String database) {
        try { validateDoris(userId, sql, catalog, database); return true; }
        catch (IllegalArgumentException invalid) { return false; }
    }

    @Transactional(readOnly = true)
    public <T> List<T> filterAllowedDorisSql(Long userId, Collection<T> values, Function<T,String> sql, String catalog, String database) {
        return values.stream().filter(value -> canAccessDorisSql(userId, sql.apply(value), catalog, database)).toList();
    }


    @Transactional(readOnly = true)
    public void validate(Long userId, String sql, String defaultCatalog, String defaultDatabase) {
        Access access = access(userId);
        if (access.admin()) return;
        validate(access, sql, defaultCatalog, defaultDatabase);
    }

    private void validate(Access access, String sql, String defaultCatalog, String defaultDatabase) {
        List<String> tables = extractTables(sql);
        String first = sql.stripLeading().split("\\s+", 2)[0].toUpperCase(Locale.ROOT);
        if (tables.isEmpty() && Set.of("SHOW", "DESCRIBE", "DESC").contains(first)) {
            throw new IllegalArgumentException("受限角色的元数据查询必须明确指定表");
        }
        for (String table : tables) {
            QualifiedName name = qualify(table, defaultCatalog, defaultDatabase);
            if (!allowed(access.scopes(), name.catalog(), name.database(), name.table())) {
                throw new IllegalArgumentException("无权查询数据表: " + name.catalog() + "."
                        + name.database() + "." + name.table());
            }
        }
    }

    @Transactional(readOnly = true)
    public boolean canAccessSql(Long userId, String sql, String defaultCatalog, String defaultDatabase) {
        Access access = access(userId);
        if (access.admin()) return true;
        try {
            validate(access, sql, defaultCatalog, defaultDatabase);
            return true;
        } catch (IllegalArgumentException deniedOrInvalid) {
            return false;
        }
    }

    @Transactional(readOnly = true)
    public <T> List<T> filterAllowedSql(Long userId, Collection<T> values, Function<T, String> sql,
                                        String defaultCatalog, String defaultDatabase) {
        Access access = access(userId);
        if (access.admin()) return List.copyOf(values);
        return values.stream().filter(value -> {
            try {
                validate(access, sql.apply(value), defaultCatalog, defaultDatabase);
                return true;
            } catch (IllegalArgumentException deniedOrInvalid) {
                return false;
            }
        }).toList();
    }

    @Transactional(readOnly = true)
    public boolean allowedReference(Long userId, String rawTable, String defaultCatalog, String defaultDatabase) {
        Access access = access(userId);
        if (access.admin()) return true;
        QualifiedName name = qualify(rawTable, defaultCatalog, defaultDatabase);
        return allowed(access.scopes(), name.catalog(), name.database(), name.table());
    }

    @Transactional(readOnly = true)
    public boolean isAdmin(Long userId) {
        return access(userId).admin();
    }

    @Transactional(readOnly = true)
    public boolean allowed(Long userId, String catalog, String database, String table) {
        Access access = access(userId);
        return access.admin() || allowed(access.scopes(), catalog, database, table);
    }

    @Transactional(readOnly = true)
    public <T> List<T> filterAllowed(Long userId, String catalog, Collection<T> values,
                                     Function<T, String> database, Function<T, String> table) {
        return filterAllowed(userId, values, ignored -> catalog, database, table);
    }

    @Transactional(readOnly = true)
    public <T> List<T> filterAllowed(Long userId, Collection<T> values, Function<T, String> catalog,
                                     Function<T, String> database, Function<T, String> table) {
        Access access = access(userId);
        if (access.admin()) return List.copyOf(values);
        return values.stream().filter(value -> allowed(access.scopes(), catalog.apply(value),
                database.apply(value), table.apply(value))).toList();
    }

    private Access access(Long userId) {
        SysUser user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("用户不存在: " + userId));
        boolean admin = user.getRoles().stream().anyMatch(role -> "ADMIN".equals(role.getRoleCode()));
        if (admin) return new Access(true, List.of());
        Collection<Long> roleIds = user.getRoles().stream().map(role -> role.getId()).toList();
        return new Access(false, scopeRepository.findByRoleIdIn(roleIds));
    }

    private List<String> extractTables(String sql) {
        try {
            SQLStatement statement = SQLUtils.parseSingleStatement(sql, DbType.mysql);
            SchemaStatVisitor visitor = SQLUtils.createSchemaStatVisitor(DbType.mysql);
            statement.accept(visitor);
            return visitor.getTables().keySet().stream().map(name -> name.getName()).distinct().toList();
        } catch (Exception exception) {
            throw new IllegalArgumentException("无法安全解析查询涉及的数据表: " + exception.getMessage());
        }
    }

    private QualifiedName qualify(String raw, String defaultCatalog, String defaultDatabase) {
        String normalized = raw.replace("`", "").replace("\"", "").trim();
        String[] parts = normalized.split("\\.");
        if (parts.length == 1) return new QualifiedName(defaultCatalog, defaultDatabase, parts[0]);
        if (parts.length == 2) return new QualifiedName(defaultCatalog, parts[0], parts[1]);
        if (parts.length == 3) return new QualifiedName(parts[0], parts[1], parts[2]);
        throw new IllegalArgumentException("无法识别数据表名称: " + raw);
    }

    private boolean allowed(List<RoleDataScope> scopes, String catalog, String database, String table) {
        return scopes.stream().anyMatch(scope -> matches(scope.getCatalogPattern(), catalog)
                && matches(scope.getDatabasePattern(), database)
                && matches(scope.getTablePattern(), table));
    }

    private boolean matches(String glob, String value) {
        StringBuilder regex = new StringBuilder("^");
        for (char character : glob.toCharArray()) {
            if (character == '*') regex.append(".*");
            else if (character == '?') regex.append('.');
            else regex.append(Pattern.quote(String.valueOf(character)));
        }
        return Pattern.compile(regex.append('$').toString(), Pattern.CASE_INSENSITIVE)
                .matcher(value == null ? "" : value).matches();
    }

    private record Access(boolean admin, List<RoleDataScope> scopes) {}
    private record QualifiedName(String catalog, String database, String table) {}
}
