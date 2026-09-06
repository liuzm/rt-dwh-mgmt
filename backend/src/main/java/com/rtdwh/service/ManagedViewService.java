package com.rtdwh.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rtdwh.entity.*;
import com.rtdwh.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.LocalDateTime;
import java.util.*;

@Service @RequiredArgsConstructor
public class ManagedViewService {
 private final ManagedViewRepository views;
 private final ManagedViewVersionRepository versions;
 private final DwhTableMetaRepository assets;
 private final ViewSqlService parser;
 private final ViewDependencyService dependencies;
 private final DorisViewEngine engine;
 private final QueryAccessScopeService access;
 private final ObjectMapper mapper;
 private final TransactionTemplate transaction;
 public record Draft(String name, String sql, String description, Long expectedVersion) {}
 public record Detail(DwhTableMeta asset, ManagedView definition, List<ManagedViewVersion> versions) {}
 public record Preview(String sql, List<ViewDependencyService.Dependency> dependencies,
   List<DorisViewEngine.Column> columns, boolean publishable, String message) {}
 public record Publication(Long expectedVersion) {}

 public Detail create(Draft request,Long actor) {
  var name=new ViewSqlService.Name("internal","rtdwh_views",request.name());
  authorize(name,actor);
  var parsed=parser.parse(request.sql(),"internal","rtdwh_views",true);
  authorizeDependencies(parsed.dependencies(),actor);
  engine.ensureNamespace();
  return transaction.execute(tx->{
   if(assets.findByCatalogNameAndPaimonDbAndPaimonTable(name.catalog(),name.database(),name.table()).isPresent())
    throw new IllegalArgumentException("资产名称已登记");
   var asset=assets.saveAndFlush(DwhTableMeta.builder().catalogName("internal").paimonDb("rtdwh_views")
      .paimonTable(name.table()).assetType("doris_view").layer(DwhTableMeta.TableLayer.ads).businessDesc(request.description()).build());
   var view=new ManagedView();view.setTableMetaId(asset.getId());view.setDraftSql(parsed.sql());views.saveAndFlush(view);
   return new Detail(asset,view,List.of());
  });
 }
 public Detail detail(String assetId,Long actor) {
  var asset=asset(assetId);authorize(name(asset),actor);
  var view=views.findByTableMetaId(asset.getId()).orElseThrow(()->new IllegalArgumentException("View 定义缺失"));
  authorizeDependencies(parser.parse(view.getDraftSql(),"internal","rtdwh_views",true).dependencies(),actor);
  var history=versions.findByViewIdOrderByVersionNoDesc(view.getId());
  for(var version:history) authorizeDependencies(dependencies.read(version.getDependenciesJson()).stream().map(ViewDependencyService.Dependency::name).toList(),actor);
  return new Detail(asset,view,history);
 }
 public Detail save(String assetId,Draft request,Long actor) {
  var current=detail(assetId,actor);
  var parsed=parser.parse(request.sql(),"internal","rtdwh_views",true);
  authorizeDependencies(parsed.dependencies(),actor);
  return transaction.execute(tx->{
   var view=locked(current.definition().getId(),request.expectedVersion());
   view.setDraftSql(parsed.sql());views.saveAndFlush(view);
   var asset=asset(assetId);asset.setBusinessDesc(request.description());assets.save(asset);
   return new Detail(asset,view,versions.findByViewIdOrderByVersionNoDesc(view.getId()));
  });
 }
 public Preview preview(String assetId,Long actor) {
  var detail=detail(assetId,actor);return preview(detail.asset(),detail.definition(),actor);
 }
 private Preview preview(DwhTableMeta asset,ManagedView view,Long actor) {
  if(!"idle".equals(view.getOperationState()))throw new IllegalArgumentException("上次发布结果待核对，禁止继续发布");
  var name=name(asset);
  var parsed=parser.parse(view.getDraftSql(),"internal","rtdwh_views",true);
  if(parsed.dependencies().stream().anyMatch(n->n.key().equals(name.key())))throw new IllegalArgumentException("View 不能依赖自身");
  var captured=dependencies.capture(parsed.dependencies(), n->allowed(n,actor));
  var columns=engine.describeQuery(parsed.sql());
  var physical=engine.inspect(name);
  if(view.getPublishedVersionId()==null) {
   if(physical.isPresent())throw new IllegalArgumentException("同名 Doris 对象已存在，禁止覆盖未托管对象");
  } else {
   var previous=versions.findById(view.getPublishedVersionId()).orElseThrow(()->new IllegalArgumentException("发布定义缺失"));
   if(physical.isEmpty() || !physical.get().view() || !Objects.equals(previous.getEngineDefinition(),physical.get().definition()))
    throw new IllegalArgumentException("Doris View 已删除或被外部修改，请先核对");
   if(!sameJson(previous.getColumnsJson(),json(columns)))
    return new Preview(parsed.sql(),captured,columns,false,"输出列契约发生变化；当前阶段请使用新 View 名称迁移消费者");
   var oldNames=dependencies.read(previous.getDependenciesJson()).stream().map(d->d.name().key()).collect(java.util.stream.Collectors.toSet());
   var newNames=parsed.dependencies().stream().map(ViewSqlService.Name::key).collect(java.util.stream.Collectors.toSet());
   if(!oldNames.equals(newNames))return new Preview(parsed.sql(),captured,columns,false,"依赖集合发生变化；当前阶段请使用新 View 名称迁移消费者");
  }
  return new Preview(parsed.sql(),captured,columns,true,"可发布：已核验依赖权限与 Doris 输出列；发布时将再次核验");
 }
 public Detail publish(String assetId,Publication request,Long actor) {
  var current=detail(assetId,actor);
  // Commit durable intent before external DDL. A crash or ambiguous response stays blocked.
  var pending=transaction.execute(tx->{
   var view=locked(current.definition().getId(),request.expectedVersion());
   var checked=preview(current.asset(),view,actor);
   if(!checked.publishable())throw new IllegalArgumentException(checked.message());
   var version=new ManagedViewVersion();version.setViewId(view.getId());
   version.setVersionNo(versions.findByViewIdOrderByVersionNoDesc(view.getId()).stream().mapToInt(ManagedViewVersion::getVersionNo).max().orElse(0)+1);
   version.setSqlContent(checked.sql());version.setDependenciesJson(json(checked.dependencies()));version.setColumnsJson(json(checked.columns()));
   version.setStatus("applying");version.setCreatedBy(actor);version.setCreatedAt(LocalDateTime.now());versions.saveAndFlush(version);
   view.setPendingVersionId(version.getId());view.setOperationState("applying");view.setLastError(null);views.save(view);
   return version;
  });
  try {
   engine.publish(name(current.asset()),pending.getSqlContent(),current.definition().getPublishedVersionId()!=null);
   var state=dependencies.required(name(current.asset()));
   if(!state.view() || !sameJson(json(state.columns()),pending.getColumnsJson()))throw new IllegalStateException("DDL 后输出列核对失败");
   transaction.executeWithoutResult(tx->{
    var view=views.lockById(pending.getViewId()).orElseThrow();
    if(!Objects.equals(view.getPendingVersionId(),pending.getId()))throw new IllegalStateException("发布意图已变化");
    var version=versions.findById(pending.getId()).orElseThrow();version.setEngineDefinition(state.definition());version.setStatus("published");versions.save(version);
    view.setPublishedVersionId(version.getId());view.setPendingVersionId(null);view.setOperationState("idle");view.setLastError(null);views.save(view);
    var asset=asset(assetId);asset.setDiscoveryStatus("observed");asset.setSchemaStatus("observed");asset.setSchemaJson(pending.getColumnsJson());asset.setLastSeenAt(LocalDateTime.now());asset.setSchemaObservedAt(LocalDateTime.now());assets.save(asset);
   });
  } catch(Exception error) {
   transaction.executeWithoutResult(tx->{
    var view=views.lockById(pending.getViewId()).orElseThrow();
    if(Objects.equals(view.getPendingVersionId(),pending.getId())) {
     view.setOperationState("unknown");view.setLastError("Doris 发布结果待人工核对："+Objects.toString(error.getMessage(),error.getClass().getSimpleName()));views.save(view);
     var version=versions.findById(pending.getId()).orElseThrow();version.setStatus("unknown");versions.save(version);
    }
   });
  }
  return detail(assetId,actor);
 }
 public Map<String,Object> health(String assetId,Long actor) {
  var detail=detail(assetId,actor);
  try { dependencies.check(name(detail.asset()),n->allowed(n,actor),new HashSet<>());return Map.of("valid",true,"message","已发布定义、依赖与输出字段核验通过"); }
  catch(IllegalArgumentException e){return Map.of("valid",false,"message",e.getMessage());}
 }
 private ManagedView locked(Long id,Long expected) {
  var view=views.lockById(id).orElseThrow();
  if(expected==null || !expected.equals(view.getVersion()))throw new IllegalArgumentException("View 已被修改，请刷新后重试");
  if(!"idle".equals(view.getOperationState()))throw new IllegalArgumentException("发布结果待核对，禁止修改或重发");
  return view;
 }
 private DwhTableMeta asset(String id) {return assets.findByAssetId(id).filter(a->"doris_view".equals(a.getAssetType())).orElseThrow(()->new IllegalArgumentException("View 资产不存在"));}
 private ViewSqlService.Name name(DwhTableMeta a){return new ViewSqlService.Name(a.getCatalogName(),a.getPaimonDb(),a.getPaimonTable());}
 private boolean allowed(ViewSqlService.Name n,Long actor){return access.allowed(actor,n.catalog(),n.database(),n.table());}
 private void authorize(ViewSqlService.Name n,Long actor){if(!allowed(n,actor))throw new org.springframework.security.access.AccessDeniedException("无权管理该 View 或读取依赖");}
 private void authorizeDependencies(List<ViewSqlService.Name> names,Long actor){names.forEach(n->authorize(n,actor));}
 private boolean sameJson(String left,String right) {
  try{return mapper.readTree(left).equals(mapper.readTree(right));}
  catch(Exception e){throw new IllegalArgumentException("View 输出契约不可解析",e);}
 }
 private String json(Object value){try{return mapper.writeValueAsString(value);}catch(Exception e){throw new IllegalArgumentException("View 契约无法序列化",e);}}
}
