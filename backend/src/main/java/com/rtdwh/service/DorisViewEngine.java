package com.rtdwh.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.sql.*;
import java.util.*;

@Service @RequiredArgsConstructor
public class DorisViewEngine {
 private final DorisConnectionService doris;
 public record Column(String name, String type, boolean nullable) {}
 public record ObjectState(boolean view, String definition, List<Column> columns) {}
 public Optional<ObjectState> inspect(ViewSqlService.Name name) {
  try(Connection c=doris.getConnection(); Statement s=c.createStatement()) {
   s.setQueryTimeout(15);
   boolean found=false,view=false;
   try(ResultSet rs=s.executeQuery("SHOW FULL TABLES FROM `"+name.catalog()+"`.`"+name.database()+"`")) {
    while(rs.next()) if(name.table().equalsIgnoreCase(rs.getString(1))) {found=true;view="VIEW".equalsIgnoreCase(rs.getString(2));break;}
   }
   if(!found)return Optional.empty();
   String ddl;
   try(ResultSet rs=s.executeQuery("SHOW CREATE TABLE "+name.sql())) { if(!rs.next())throw new IllegalStateException("定义缺失");ddl=rs.getString(2); }
   return Optional.of(new ObjectState(view,ddl,columns(s,"SELECT * FROM "+name.sql()+" LIMIT 0")));
  }catch(SQLException e){throw new IllegalArgumentException("无法核验 Doris 对象 "+name.key()+": "+e.getMessage(),e);}
 }
 public void ensureNamespace() {
  try(Connection c=doris.getConnection();Statement s=c.createStatement()) {
   s.setQueryTimeout(15);s.execute("CREATE DATABASE IF NOT EXISTS internal.rtdwh_views");
  }catch(SQLException e){throw new IllegalArgumentException("无法初始化 View 数据库: "+e.getMessage(),e);}
 }
 public List<Column> describeQuery(String sql) {
  try(Connection c=doris.getConnection();Statement s=c.createStatement()) {
   s.setQueryTimeout(15); s.execute("SWITCH internal");s.execute("USE rtdwh_views");
   return columns(s,"SELECT * FROM ("+sql+") AS view_contract LIMIT 0");
  }catch(SQLException e){throw new IllegalArgumentException("View SQL 校验失败: "+e.getMessage(),e);}
 }
 public void publish(ViewSqlService.Name name,String sql,boolean replace) throws SQLException {
  try(Connection c=doris.getConnection();Statement s=c.createStatement()) {
   s.setQueryTimeout(30);s.execute("SWITCH internal");s.execute("USE rtdwh_views");
   s.execute((replace?"ALTER VIEW ":"CREATE VIEW ")+name.sql()+" AS "+sql);
  }
 }
 private List<Column> columns(Statement s,String sql) throws SQLException {
  try(ResultSet rs=s.executeQuery(sql)) {
   var md=rs.getMetaData();List<Column> columns=new ArrayList<>();Set<String> names=new HashSet<>();
   for(int i=1;i<=md.getColumnCount();i++) {
    String name=md.getColumnLabel(i);
    if(!names.add(name.toLowerCase(Locale.ROOT)))throw new IllegalArgumentException("输出列名重复: "+name);
    columns.add(new Column(name,md.getColumnTypeName(i)+"("+md.getPrecision(i)+","+md.getScale(i)+")",md.isNullable(i)!=ResultSetMetaData.columnNoNulls));
   }
   return columns;
  }
 }
}
