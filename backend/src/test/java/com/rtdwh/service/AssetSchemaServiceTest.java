package com.rtdwh.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rtdwh.entity.*;
import com.rtdwh.repository.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.rtdwh.service.AssetSchemaService.*;

class AssetSchemaServiceTest {
    final DwhColumnMetaRepository columns = mock(DwhColumnMetaRepository.class);
    final AssetSchemaRevisionRepository revisions = mock(AssetSchemaRevisionRepository.class);
    final AssetSchemaService service = new AssetSchemaService(columns, revisions, new ObjectMapper());
    final DwhTableMeta table = DwhTableMeta.builder().id(1L).paimonDb("ods").paimonTable("events").build();
    Field field(long id, String name, String type, boolean nullable) { return new Field(id, name, type, nullable, false, null); }
    Schema schema(Field... fields) { return new Schema(List.of(fields), "", ""); }
    DwhColumnMeta column(long id, String name) { return DwhColumnMeta.builder().engineFieldId(id).columnName(name).columnType("STRING").isNullable(true).build(); }

    @Test void baselineAndUnchangedSchemaAreNotInventedChanges() {
        service.observe(table, List.of(column(1, "name")), "paimon_schema");
        var captured = ArgumentCaptor.forClass(AssetSchemaRevision.class);
        verify(revisions).save(captured.capture());
        var baseline = captured.getValue();
        assertEquals("baseline", baseline.getSeverity()); assertNull(baseline.getBeforeSchema());
        when(revisions.findFirstByTableMetaIdOrderByRevisionNoDesc(1L)).thenReturn(Optional.of(baseline));
        var changedComment = column(1, "name"); changedComment.setBusinessComment("annotation only");
        service.observe(table, List.of(changedComment), "paimon_schema");
        verify(revisions, times(1)).save(any());
        assertEquals("observed", table.getSchemaStatus()); assertEquals("paimon_append_table", table.getAssetType());
    }
    @Test void renamePreservesPlatformColumnIdAndAnnotations() {
        var old = column(3, "old_name"); old.setId(12L); old.setBusinessComment("business meaning"); old.setSourceColumn("origin.name");
        when(columns.findByTableMetaIdOrderBySortOrder(1L)).thenReturn(List.of(old));
        var renamed = column(3, "new_name");
        service.observe(table, List.of(renamed), "paimon_schema");
        assertEquals(12L, renamed.getId()); assertEquals("business meaning", renamed.getBusinessComment());
        assertEquals("origin.name", renamed.getSourceColumn()); verify(columns).deleteAll(List.of());
        var diff = service.compare(schema(field(3, "old_name", "STRING", true)), schema(field(3, "new_name", "STRING", true)));
        assertEquals("rename_column", diff.get(0).kind()); assertEquals("breaking", diff.get(0).severity());
    }
    @Test void replacedSameNameFieldDoesNotInheritIdentityOrAnnotations() {
        var old = column(3, "name"); old.setId(12L); old.setBusinessComment("old meaning");
        when(columns.findByTableMetaIdOrderBySortOrder(1L)).thenReturn(List.of(old));
        var replacement = column(4, "name"); service.observe(table, List.of(replacement), "paimon_schema");
        assertNull(replacement.getId()); assertNull(replacement.getBusinessComment()); verify(columns).deleteAll(List.of(old));
        var changes = service.compare(schema(field(3, "name", "STRING", true)), schema(field(4, "name", "STRING", true)));
        assertEquals(List.of("add_column", "drop_column"), changes.stream().map(Change::kind).toList());
    }
    @Test void legacyColumnAdoptsEngineIdWithoutLosingAnnotations() {
        var old = column(1, "name"); old.setEngineFieldId(null); old.setId(12L); old.setBusinessComment("legacy");
        when(columns.findByTableMetaIdOrderBySortOrder(1L)).thenReturn(List.of(old));
        var next = column(3, "name"); service.observe(table, List.of(next), "paimon_schema");
        assertEquals(12L, next.getId()); assertEquals("legacy", next.getBusinessComment());
    }
    @Test void incompatibleChangesAreNeverMarkedCompatible() {
        assertEquals("compatible", service.compare(schema(field(1,"n","INT",true)), schema(field(1,"n","BIGINT",true))).get(0).severity());
        assertEquals("breaking", service.compare(schema(field(1,"n","BIGINT",true)), schema(field(1,"n","INT",true))).get(0).severity());
        assertEquals("breaking", service.compare(schema(field(1,"n","STRING",true)), schema(field(1,"n","STRING",false))).get(0).severity());
        assertEquals("risk", service.compare(schema(field(1,"n","ROW<a INT>",true)), schema(field(1,"n","ROW<a BIGINT>",true))).get(0).severity());
        assertEquals("compatible", service.compare(schema(), schema(field(1,"n","STRING",true))).get(0).severity());
        assertEquals("breaking", service.compare(schema(), schema(field(1,"n","STRING",false))).get(0).severity());
    }
    @Test void keyAndPartitionChangesAreBreaking() {
        var before = schema(field(1, "id", "INT", false));
        var diff = service.compare(before, new Schema(before.fields(), "id", "id"));
        assertEquals(List.of("alter_keys", "alter_partition"), diff.stream().map(Change::kind).toList());
        assertTrue(diff.stream().allMatch(c -> c.severity().equals("breaking")));
    }
    @Test void invalidObservationsNeverErasePreviousColumns() {
        assertThrows(IllegalArgumentException.class, () -> service.observe(table, List.of(), "paimon_schema"));
        assertThrows(IllegalArgumentException.class, () -> service.observe(table, List.of(column(1,"a"),column(1,"b")), "paimon_schema"));
        assertThrows(IllegalArgumentException.class, () -> service.observe(table, List.of(column(1,"a"),column(2,"a")), "paimon_schema"));
        verifyNoInteractions(columns, revisions);
    }
}
