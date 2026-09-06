package com.rtdwh.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rtdwh.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.function.Predicate;

@Service @RequiredArgsConstructor
public class ViewDependencyService {
 private final ManagedViewRepository views;
 private final ManagedViewVersionRepository versions;
 private final DwhTableMetaRepository assets;
 private final DorisViewEngine engine;
 private final DorisConnectionService doris;
 private final ViewSqlService parser;
 private final ObjectMapper mapper;
 public record Dependency(ViewSqlService.Name name, List<DorisViewEngine.Column> columns) {}
 public List<Dependency> capture(List<ViewSqlService.Name> names, Predicate<ViewSqlService.Name> allowed) {
  List<Dependency> result=new ArrayList<>();
  for(var name:names) {
   var asset=assets.findByCatalogNameAndPaimonDbAndPaimonTable(name.catalog(),name.database(),name.table())
     .orElseThrow(()->new IllegalArgumentException("View 依赖必须是已登记资产: "+name.key()));
   if(!"doris_view".equals(asset.getAssetType()) && (!name.catalog().equalsIgnoreCase(doris.getCatalog())
     || !asset.getAssetType().startsWith("paimon") || !"observed".equals(asset.getDiscoveryStatus())))
    throw new IllegalArgumentException("依赖尚未核验或类型不受支持: "+name.key());
   check(name,allowed,new HashSet<>());
   result.add(new Dependency(name,required(name).columns()));
  }
  return result;
 }
 public void validateQuery(String sql,String catalog,String database,Predicate<ViewSqlService.Name> allowed) {
  for(var name:parser.parse(sql,catalog,database,false).dependencies()) check(name,allowed,new HashSet<>());
 }
 public void check(ViewSqlService.Name name,Predicate<ViewSqlService.Name> allowed,Set<String> path) {
  check(name,allowed,path,new java.util.concurrent.atomic.AtomicInteger());
 }
 private void check(ViewSqlService.Name name,Predicate<ViewSqlService.Name> allowed,Set<String> path,java.util.concurrent.atomic.AtomicInteger visited) {
  if(visited.incrementAndGet()>256)throw new IllegalArgumentException("View 依赖展开超过 256 个节点");
  if(!allowed.test(name))throw new IllegalArgumentException("无权访问 View 或其依赖: "+name.key());
  if(path.size()>=32 || !path.add(name.key()))throw new IllegalArgumentException("View 依赖存在循环或超过 32 层");
  try {
   var asset=assets.findByCatalogNameAndPaimonDbAndPaimonTable(name.catalog(),name.database(),name.table()).orElse(null);
   // Managed namespace is reserved. An unregistered object can never become a trusted View.
   var view=asset==null?null:views.findByTableMetaId(asset.getId()).orElse(null);
   var physical=engine.inspect(name);
   if(view==null) {
    if(physical.map(DorisViewEngine.ObjectState::view).orElse(false) || ("internal".equalsIgnoreCase(name.catalog()) && "rtdwh_views".equalsIgnoreCase(name.database())))
     throw new IllegalArgumentException("View 定义未托管，无法展开依赖: "+name.key());
    return;
   }
   var state=physical.orElseThrow(()->new IllegalArgumentException("View 已不存在: "+name.key()));
   if(!"idle".equals(view.getOperationState()) || view.getPublishedVersionId()==null)
    throw new IllegalArgumentException("View 尚未发布或发布结果待核对: "+name.key());
   var version=versions.findById(view.getPublishedVersionId()).filter(v->v.getViewId().equals(view.getId()) && "published".equals(v.getStatus()))
     .orElseThrow(()->new IllegalArgumentException("View 发布定义缺失"));
   if(!state.view() || !Objects.equals(state.definition(),version.getEngineDefinition()))
    throw new IllegalArgumentException("View 已失效：Doris 定义与发布版本不一致: "+name.key());
   try {
    if(!mapper.valueToTree(state.columns()).equals(mapper.readTree(version.getColumnsJson())))
     throw new IllegalArgumentException("View 已失效：输出字段与发布契约不一致: "+name.key());
   } catch(java.io.IOException invalid) {throw new IllegalArgumentException("View 输出契约不可解析",invalid);}
   var dependencies=read(version.getDependenciesJson());
   if(dependencies.isEmpty())throw new IllegalArgumentException("View 依赖记录缺失");
   // Reparse frozen SQL as well; corrupted or unsupported stored definitions fail closed.
   var parsed=parser.parse(version.getSqlContent(),"internal","rtdwh_views",true).dependencies();
   if(!new HashSet<>(parsed).equals(new HashSet<>(dependencies.stream().map(Dependency::name).toList())))
    throw new IllegalArgumentException("View 依赖记录与定义不一致");
   for(var dep:dependencies) {
    check(dep.name(),allowed,path,visited);
    if(!dep.columns().equals(required(dep.name()).columns()))
     throw new IllegalArgumentException("View 已失效：上游字段发生变化: "+dep.name().key());
   }
  }finally{path.remove(name.key());}
 }
 public DorisViewEngine.ObjectState required(ViewSqlService.Name name) {
  return engine.inspect(name).orElseThrow(()->new IllegalArgumentException("View 或依赖已不存在: "+name.key()));
 }
 public List<Dependency> read(String json) {
  try{return mapper.readValue(json,new TypeReference<List<Dependency>>(){});}
  catch(Exception e){throw new IllegalArgumentException("View 依赖记录不可解析",e);}
 }
}
