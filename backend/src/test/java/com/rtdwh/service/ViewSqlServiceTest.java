package com.rtdwh.service;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ViewSqlServiceTest {
 final ViewSqlService sql=new ViewSqlService();
 @Test void readsJoinsSubqueriesUnionAndIgnoresLiteralText() {
  var parsed=sql.parse("SELECT a.id, 'from private.data.secret' AS label FROM rtdwh_paimon.ods.a a JOIN (SELECT id FROM rtdwh_paimon.ods.b) b ON a.id=b.id UNION ALL SELECT id, 'x' FROM rtdwh_paimon.ods.c","internal","rtdwh_views",true);
  assertEquals(3,parsed.dependencies().size());
  assertEquals("rtdwh_paimon.ods.a",parsed.dependencies().get(0).key());
 }
 @Test void rejectsIncompleteDynamicOrWritableDefinitions() {
  for(String value:new String[]{"select * from a", "select 1", "select * from rtdwh_paimon.ods.a; drop view x", "delete from rtdwh_paimon.ods.a", "select * from numbers('number'='10')", "WITH x AS (SELECT * FROM rtdwh_paimon.ods.a) SELECT * FROM x", "SELECT * INTO OUTFILE '/tmp/x' FROM rtdwh_paimon.ods.a", "select * from rtdwh_paimon.ods.a for update"})
   assertThrows(IllegalArgumentException.class,()->sql.parse(value,"internal","rtdwh_views",true),value);
 }
 @Test void queryCteRetainsEveryRealDependency() {
  var p=sql.parse("WITH x AS (SELECT * FROM internal.rtdwh_views.a) SELECT * FROM x JOIN rtdwh_paimon.ods.b b ON x.id=b.id","rtdwh_paimon","ods",false);
  assertEquals(2,p.dependencies().size());
 }
}
