package com.rtdwh.service;

import com.alibaba.druid.DbType;
import com.alibaba.druid.sql.SQLUtils;
import com.alibaba.druid.sql.ast.statement.SQLSelectStatement;
import com.alibaba.druid.sql.ast.statement.SQLInsertStatement;
import org.springframework.stereotype.Service;
import java.util.*;

/** Evidence for discovery only; it does not replace execution-time authorization. */
@Service
public class SqlAssetReferenceService {
    public record Reference(String catalog, String database, String table, boolean input, boolean output) {}
    public record Result(boolean parsed, List<Reference> references) {}

    public Result inspect(String sql, String defaultCatalog, String defaultDatabase) {
        if (sql == null || sql.isBlank()) return new Result(false, List.of());
        List<Reference> result = new ArrayList<>();
        String catalog = defaultCatalog, database = defaultDatabase;
        try {
            for (String text : FlinkClusterService.splitSqlStatements(sql)) {
                var useCatalog = java.util.regex.Pattern.compile("(?i)^USE\\s+CATALOG\\s+`?([A-Za-z_][A-Za-z0-9_]*)`?$").matcher(text);
                var useDatabase = java.util.regex.Pattern.compile("(?i)^USE\\s+`?([A-Za-z_][A-Za-z0-9_]*)`?$").matcher(text);
                if (useCatalog.matches()) { catalog = useCatalog.group(1); continue; }
                if (useDatabase.matches()) { database = useDatabase.group(1); continue; }
                var statement = SQLUtils.parseSingleStatement(text, DbType.mysql);
                if (!(statement instanceof SQLSelectStatement) && !(statement instanceof SQLInsertStatement))
                    return new Result(false, List.of());
                var visitor = SQLUtils.createSchemaStatVisitor(DbType.mysql); statement.accept(visitor);
                for (var entry : visitor.getTables().entrySet()) {
                    String[] names = entry.getKey().getName().replace("`", "").split("\\.");
                    if (names.length > 3 || Arrays.stream(names).anyMatch(n -> !n.matches("[A-Za-z_][A-Za-z0-9_]*"))) return new Result(false, List.of());
                    result.add(new Reference(names.length == 3 ? names[0] : catalog,
                            names.length > 1 ? names[names.length - 2] : database, names[names.length - 1],
                            entry.getValue().getSelectCount() > 0, entry.getValue().getInsertCount() > 0));
                }
            }
            return new Result(true, result.stream().distinct().toList());
        } catch (Exception unsupported) { return new Result(false, List.of()); }
    }
}
