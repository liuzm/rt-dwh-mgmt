package com.rtdwh.service;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rtdwh.entity.*;
import com.rtdwh.repository.*;
import org.junit.jupiter.api.*;
import org.springframework.transaction.support.*;
import java.sql.SQLException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
class ManagedViewServiceTest {
 final ManagedViewRepository views=mock(ManagedViewRepository.class);
 final ManagedViewVersionRepository versions=mock(ManagedViewVersionRepository.class);
 final DwhTableMetaRepository assets=mock(DwhTableMetaRepository.class);
 final ViewDependencyService deps=mock(ViewDependencyService.class);
 final DorisViewEngine engine=mock(DorisViewEngine.class);
 final QueryAccessScopeService access=mock(QueryAccessScopeService.class);
 final TransactionTemplate tx=mock(TransactionTemplate.class);
 final ObjectMapper json=new ObjectMapper();
 final ManagedViewService service=new ManagedViewService(views,versions,assets,new ViewSqlService(),deps,engine,access,json,tx);
 final ManagedView view=new ManagedView();
 final DwhTableMeta asset=DwhTableMeta.builder().id(1L).assetId("asset").catalogName("internal").paimonDb("rtdwh_views").paimonTable("example").assetType("doris_view").build();
 final ViewSqlService.Name name=new ViewSqlService.Name("internal","rtdwh_views","example");
 final List<DorisViewEngine.Column> cols=List.of(new DorisViewEngine.Column("id","BIGINT(19,0)",true));
 final List<ManagedViewVersion> history=new ArrayList<>();
 @BeforeEach void setup() {
  when(tx.execute(any())).thenAnswer(i->((TransactionCallback<?>)i.getArgument(0)).doInTransaction(new SimpleTransactionStatus()));
  doAnswer(i->{((java.util.function.Consumer<org.springframework.transaction.TransactionStatus>)i.getArgument(0)).accept(new SimpleTransactionStatus());return null;}).when(tx).executeWithoutResult(any());
  view.setId(1L);view.setTableMetaId(1L);view.setVersion(0L);view.setDraftSql("SELECT id FROM rtdwh_paimon.ods.base");
  when(assets.findByAssetId("asset")).thenReturn(Optional.of(asset));when(views.findByTableMetaId(1L)).thenReturn(Optional.of(view));when(views.lockById(1L)).thenReturn(Optional.of(view));
  when(access.allowed(anyLong(),anyString(),anyString(),anyString())).thenReturn(true);
  when(versions.findByViewIdOrderByVersionNoDesc(1L)).thenAnswer(i->List.copyOf(history));
  when(versions.saveAndFlush(any())).thenAnswer(i->{ManagedViewVersion v=i.getArgument(0);v.setId(1L);history.add(v);return v;});
  when(versions.findById(1L)).thenAnswer(i->history.stream().findFirst());
  when(deps.capture(anyList(),any())).thenReturn(List.of(new ViewDependencyService.Dependency(new ViewSqlService.Name("rtdwh_paimon","ods","base"),cols)));
  when(engine.describeQuery(anyString())).thenReturn(cols);
 }
 @Test void recordsIntentBeforeDdlAndDoesNotReplayAmbiguousResult() throws Exception {
  doAnswer(i->{assertEquals("applying",view.getOperationState());assertEquals(1L,view.getPendingVersionId());assertEquals(1,history.size());throw new SQLException("timeout");}).when(engine).publish(any(),anyString(),anyBoolean());
  service.publish("asset",new ManagedViewService.Publication(0L),7L);
  assertEquals("unknown",view.getOperationState());assertNull(view.getPublishedVersionId());assertEquals("unknown",history.get(0).getStatus());
  assertThrows(IllegalArgumentException.class,()->service.publish("asset",new ManagedViewService.Publication(0L),7L));
  verify(engine,times(1)).publish(any(),anyString(),anyBoolean());
 }
 @Test void rejectsStaleEditorsAndUnmanagedObjectBeforeDdl() throws Exception {
  assertThrows(IllegalArgumentException.class,()->service.publish("asset",new ManagedViewService.Publication(9L),7L));
  when(engine.inspect(name)).thenReturn(Optional.of(new DorisViewEngine.ObjectState(true,"foreign",cols)));
  assertThrows(IllegalArgumentException.class,()->service.publish("asset",new ManagedViewService.Publication(0L),7L));
  verify(engine,never()).publish(any(),anyString(),anyBoolean());verify(versions,never()).saveAndFlush(any());
 }
 @Test void verifiesPhysicalResultAndRejectsContractChanges() throws Exception {
  when(deps.required(name)).thenReturn(new DorisViewEngine.ObjectState(true,"verified DDL",cols));
  service.publish("asset",new ManagedViewService.Publication(0L),7L);
  assertEquals("idle",view.getOperationState());assertEquals(1L,view.getPublishedVersionId());assertEquals("verified DDL",history.get(0).getEngineDefinition());
  when(engine.inspect(name)).thenReturn(Optional.of(new DorisViewEngine.ObjectState(true,"verified DDL",cols)));
  history.get(0).setColumnsJson("[{\"nullable\": true, \"type\": \"BIGINT(19,0)\", \"name\": \"id\"}]");
  when(deps.read(anyString())).thenReturn(List.of(new ViewDependencyService.Dependency(new ViewSqlService.Name("rtdwh_paimon","ods","base"),cols)));
  assertTrue(service.preview("asset",7L).publishable()); // MySQL JSON normalizes whitespace/key order.
  when(engine.describeQuery(anyString())).thenReturn(List.of(new DorisViewEngine.Column("renamed","BIGINT(19,0)",true)));
  assertFalse(service.preview("asset",7L).publishable());
  assertThrows(IllegalArgumentException.class,()->service.publish("asset",new ManagedViewService.Publication(0L),7L));
  verify(engine,times(1)).publish(any(),anyString(),anyBoolean());
 }
}
