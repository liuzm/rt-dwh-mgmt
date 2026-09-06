package com.rtdwh.service;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rtdwh.entity.*;
import com.rtdwh.repository.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
class ViewDependencyServiceTest {
 final ManagedViewRepository views=mock(ManagedViewRepository.class);
 final ManagedViewVersionRepository versions=mock(ManagedViewVersionRepository.class);
 final DwhTableMetaRepository assets=mock(DwhTableMetaRepository.class);
 final DorisViewEngine engine=mock(DorisViewEngine.class);
 final ObjectMapper json=new ObjectMapper();
 final ViewDependencyService service=new ViewDependencyService(views,versions,assets,engine,mock(DorisConnectionService.class),new ViewSqlService(),json);
 final ViewSqlService.Name base=new ViewSqlService.Name("rtdwh_paimon","ods","base");
 final ViewSqlService.Name child=new ViewSqlService.Name("internal","rtdwh_views","child");
 final ViewSqlService.Name top=new ViewSqlService.Name("internal","rtdwh_views","top");
 final List<DorisViewEngine.Column> cols=List.of(new DorisViewEngine.Column("id","BIGINT(19,0)",true));
 ManagedView view(ViewSqlService.Name name, long id,ViewSqlService.Name input) throws Exception {
  var a=DwhTableMeta.builder().id(id).catalogName(name.catalog()).paimonDb(name.database()).paimonTable(name.table()).assetType("doris_view").build();
  when(assets.findByCatalogNameAndPaimonDbAndPaimonTable(name.catalog(),name.database(),name.table())).thenReturn(Optional.of(a));
  var v=new ManagedView();v.setId(id);v.setTableMetaId(id);v.setPublishedVersionId(id);
  when(views.findByTableMetaId(id)).thenReturn(Optional.of(v));
  var r=new ManagedViewVersion();r.setId(id);r.setViewId(id);r.setStatus("published");r.setEngineDefinition("DDL:"+name.key());r.setSqlContent("select id from "+input.sql());
  r.setColumnsJson(json.writeValueAsString(cols));
  r.setDependenciesJson(json.writeValueAsString(List.of(new ViewDependencyService.Dependency(input,cols))));
  when(versions.findById(id)).thenReturn(Optional.of(r));
  when(engine.inspect(name)).thenReturn(Optional.of(new DorisViewEngine.ObjectState(true,r.getEngineDefinition(),cols)));
  when(engine.inspect(base)).thenReturn(Optional.of(new DorisViewEngine.ObjectState(false,"TABLE",cols)));
  return v;
 }
 @Test void expandsNestedViewsAndRechecksRevokedBaseScope() throws Exception {
  view(child,1,base);view(top,2,child);
  assertDoesNotThrow(()->service.check(top,n->true,new HashSet<>()));
  assertThrows(IllegalArgumentException.class,()->service.check(top,n->!n.equals(base),new HashSet<>()));
 }
 @Test void rejectsCyclesMissingDefinitionsUnmanagedViewsAndUncertainPublication() throws Exception {
  view(child,1,top);var v=view(top,2,child);
  assertThrows(IllegalArgumentException.class,()->service.check(top,n->true,new HashSet<>()));
  v.setOperationState("unknown");assertThrows(IllegalArgumentException.class,()->service.check(top,n->true,new HashSet<>()));
  v.setOperationState("idle");when(versions.findById(2L)).thenReturn(Optional.empty());
  assertThrows(IllegalArgumentException.class,()->service.check(top,n->true,new HashSet<>()));
  when(views.findByTableMetaId(2L)).thenReturn(Optional.empty());
  assertThrows(IllegalArgumentException.class,()->service.check(top,n->true,new HashSet<>()));
 }
 @Test void rejectsPhysicalDriftMissingBaseAndChangedSchema() throws Exception {
  view(top,2,base);
  when(engine.inspect(base)).thenReturn(Optional.empty());
  assertThrows(IllegalArgumentException.class,()->service.check(top,n->true,new HashSet<>()));
  when(engine.inspect(base)).thenReturn(Optional.of(new DorisViewEngine.ObjectState(false,"TABLE",List.of(new DorisViewEngine.Column("changed","TEXT",true)))));
  assertThrows(IllegalArgumentException.class,()->service.check(top,n->true,new HashSet<>()));
  when(engine.inspect(top)).thenReturn(Optional.of(new DorisViewEngine.ObjectState(true,"external replacement",cols)));
  assertThrows(IllegalArgumentException.class,()->service.check(top,n->true,new HashSet<>()));
 }
}
