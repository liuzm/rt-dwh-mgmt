package com.rtdwh.service;

import com.alibaba.druid.DbType;
import com.alibaba.druid.sql.SQLUtils;
import com.alibaba.druid.sql.ast.*;
import com.alibaba.druid.sql.ast.expr.*;
import com.alibaba.druid.sql.ast.statement.*;
import com.alibaba.druid.sql.visitor.SQLASTVisitorAdapter;
import org.springframework.stereotype.Service;
import java.util.*;

/** Closed SELECT subset used for managed definitions and Doris query authorization. */
@Service
public class ViewSqlService {
 public record Name(String catalog, String database, String table) {
  public Name { catalog=identifier(catalog); database=identifier(database); table=identifier(table); }
  public String key() { return (catalog+"."+database+"."+table).toLowerCase(Locale.ROOT); }
  public String sql() { return "`"+catalog+"`.`"+database+"`.`"+table+"`"; }
 }
 public record Parsed(String sql, List<Name> dependencies) {}
 public Parsed parse(String sql, String catalog, String database, boolean definition) {
  try {
   var stmt=SQLUtils.parseSingleStatement(sql,DbType.mysql);
   if (!(stmt instanceof SQLSelectStatement)) throw new IllegalArgumentException("View 只支持单条 SELECT");
   Set<String> ctes=new HashSet<>();
   stmt.accept(new SQLASTVisitorAdapter() {
    @Override public boolean visit(SQLWithSubqueryClause.Entry entry) {
     if(definition) throw new IllegalArgumentException("当前 View 定义暂不支持 CTE，请使用子查询");
     ctes.add(identifier(entry.getAlias()).toLowerCase(Locale.ROOT)); return true;
    }
   });
   List<Name> refs=new ArrayList<>();
   stmt.accept(new SQLASTVisitorAdapter() {
    @Override public void preVisit(SQLObject node) {
     if (node instanceof SQLTableSource && !(node instanceof SQLExprTableSource)
       && !(node instanceof SQLJoinTableSource) && !(node instanceof SQLSubqueryTableSource)
       && !(node instanceof SQLUnionQueryTableSource) && !(node instanceof SQLWithSubqueryClause.Entry))
      throw new IllegalArgumentException("不支持的表来源，无法完整核验依赖");
     if(node instanceof SQLSelectQueryBlock block && (block.getInto()!=null || block.isForUpdate()))
      throw new IllegalArgumentException("只允许只读 SELECT");
    }
    @Override public boolean visit(SQLExprTableSource source) {
     List<String> parts=new ArrayList<>(); names(source.getExpr(),parts);
     if(parts.size()==1 && ctes.contains(parts.get(0).toLowerCase(Locale.ROOT))) return true;
     if(definition && parts.size()!=3) throw new IllegalArgumentException("View 依赖必须使用 catalog.database.table 三段名称");
     if(parts.size()<1 || parts.size()>3) throw new IllegalArgumentException("无效依赖名称");
     refs.add(new Name(parts.size()==3?parts.get(0):catalog,
       parts.size()>1?parts.get(parts.size()-2):database,parts.get(parts.size()-1)));return true;
    }
   });
   if (!definition) {
    var visitor=SQLUtils.createSchemaStatVisitor(DbType.mysql);stmt.accept(visitor);
    for(var entry:visitor.getTables().keySet()) {
     var parts=entry.getName().replace("`","").split("\\.");
     if(parts.length>3)throw new IllegalArgumentException("不支持的表名称");
     refs.add(new Name(parts.length==3?parts[0]:catalog,parts.length>1?parts[parts.length-2]:database,parts[parts.length-1]));
    }
   }
   if(definition && refs.size()>64)throw new IllegalArgumentException("View 直接依赖不能超过 64 个");
   if(definition && refs.isEmpty()) throw new IllegalArgumentException("View 必须引用至少一个已登记资产");
   return new Parsed(SQLUtils.toSQLString(stmt,DbType.mysql).replaceFirst(";\\s*$",""),refs.stream().distinct().toList());
  } catch (Exception e) { throw new IllegalArgumentException("无法安全解析 View/查询 SQL: "+e.getMessage()); }
 }
 private static void names(SQLExpr expr,List<String> parts) {
  if(expr instanceof SQLIdentifierExpr id) parts.add(identifier(id.getName()));
  else if(expr instanceof SQLPropertyExpr p) { names(p.getOwner(),parts);parts.add(identifier(p.getName())); }
  else throw new IllegalArgumentException("不支持表函数或动态表来源");
 }
 static String identifier(String raw) {
  if(raw==null) throw new IllegalArgumentException("标识符不能为空");
  String text=raw.replace("`","");
  if(!text.matches("[A-Za-z_][A-Za-z0-9_]*")) throw new IllegalArgumentException("不支持的标识符: "+raw);
  return text;
 }
}
