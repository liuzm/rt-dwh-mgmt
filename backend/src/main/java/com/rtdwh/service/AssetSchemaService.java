package com.rtdwh.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rtdwh.entity.*;
import com.rtdwh.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.*;

@Service @RequiredArgsConstructor
public class AssetSchemaService {
    private final DwhColumnMetaRepository columns;
    private final AssetSchemaRevisionRepository revisions;
    private final ObjectMapper mapper;

    public record Field(Long engineId, String name, String type, boolean nullable, boolean primaryKey, String defaultValue) {}
    public record Schema(List<Field> fields, String partitionKeys, String primaryKeys) {}
    public record Change(String severity, String kind, String field, String detail) {}

    public List<AssetSchemaRevision> history(Long tableId) {
        return revisions.findByTableMetaIdOrderByRevisionNoDesc(tableId, PageRequest.of(0, 100));
    }

    @Transactional
    public void observe(DwhTableMeta table, List<DwhColumnMeta> observed, String source) {
        if (observed.isEmpty()) throw new IllegalArgumentException("字段观测为空，保留原字段契约");
        Schema schema = new Schema(observed.stream().map(c -> new Field(c.getEngineFieldId(), c.getColumnName(),
                normalizeType(c.getColumnType()), !Boolean.FALSE.equals(c.getIsNullable()), Boolean.TRUE.equals(c.getIsPk()), c.getDefaultValue())).toList(),
                Objects.toString(table.getPartitionKeys(), ""), Objects.toString(table.getPrimaryKeys(), ""));
        if (schema.fields().stream().map(Field::name).distinct().count() != schema.fields().size()
                || schema.fields().stream().anyMatch(f -> f.name() == null || f.name().isBlank()))
            throw new IllegalArgumentException("字段观测包含空名称或重名，保留原字段契约");
        var ids = schema.fields().stream().map(Field::engineId).filter(Objects::nonNull).toList();
        if (ids.stream().distinct().count() != ids.size()) throw new IllegalArgumentException("引擎字段 ID 重复");
        try {
            String json = mapper.writeValueAsString(schema);
            String fingerprint = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.getBytes(StandardCharsets.UTF_8)));
            var previous = revisions.findFirstByTableMetaIdOrderByRevisionNoDesc(table.getId()).orElse(null);
            if (previous == null || !fingerprint.equals(previous.getFingerprint())) {
                List<Change> changes = previous == null ? List.of(new Change("baseline", "baseline", null, "首次观测，不推断历史变更"))
                        : compare(mapper.readValue(previous.getAfterSchema(), Schema.class), schema);
                revisions.save(AssetSchemaRevision.builder().tableMetaId(table.getId()).revisionNo(previous == null ? 1 : previous.getRevisionNo() + 1)
                        .severity(severity(changes)).evidenceSource(source).fingerprint(fingerprint)
                        .beforeSchema(previous == null ? null : previous.getAfterSchema()).afterSchema(json)
                        .changesJson(mapper.writeValueAsString(changes)).observedAt(LocalDateTime.now()).build());
            }
            mergeColumns(table.getId(), observed);
            table.setAssetType(table.getPrimaryKeys() == null || table.getPrimaryKeys().isBlank() ? "paimon_append_table" : "paimon_primary_key_table");
            table.setSchemaStatus("observed"); table.setSchemaObservedAt(LocalDateTime.now());
        } catch (RuntimeException e) { throw e; }
        catch (Exception e) { throw new IllegalStateException("Schema 观测无法保存", e); }
    }

    private void mergeColumns(Long tableId, List<DwhColumnMeta> observed) {
        List<DwhColumnMeta> existing = columns.findByTableMetaIdOrderBySortOrder(tableId);
        Set<Long> retained = new HashSet<>();
        for (DwhColumnMeta next : observed) {
            DwhColumnMeta old = existing.stream().filter(c -> matches(c.getEngineFieldId(), c.getColumnName(), next.getEngineFieldId(), next.getColumnName()))
                    .findFirst().orElse(null);
            if (old != null) {
                retained.add(old.getId()); next.setId(old.getId());
                // Business annotations belong to the platform, even after a proven field rename.
                if (old.getBusinessComment() != null) next.setBusinessComment(old.getBusinessComment());
                next.setSourceColumn(old.getSourceColumn());
            }
            next.setTableMetaId(tableId); columns.save(next);
        }
        columns.deleteAll(existing.stream().filter(c -> !retained.contains(c.getId())).toList());
    }

    public List<Change> compare(Schema before, Schema after) {
        List<Change> result = new ArrayList<>();
        Set<Field> matched = new HashSet<>();
        for (Field next : after.fields()) {
            Field old = before.fields().stream().filter(f -> matches(f.engineId(), f.name(), next.engineId(), next.name())).findFirst().orElse(null);
            if (old == null) { result.add(new Change(next.nullable() ? "compatible" : "breaking", "add_column", next.name(), next.type())); continue; }
            matched.add(old);
            if (!old.name().equals(next.name())) result.add(new Change("breaking", "rename_column", next.name(), old.name() + " → " + next.name()));
            if (!old.type().equals(next.type())) result.add(new Change(typeSeverity(old.type(), next.type()), "alter_type", next.name(), old.type() + " → " + next.type()));
            if (old.nullable() != next.nullable()) result.add(new Change(next.nullable() ? "risk" : "breaking", "alter_nullable", next.name(), next.nullable() ? "允许 NULL" : "禁止 NULL"));
            if (old.primaryKey() != next.primaryKey()) result.add(new Change("breaking", "alter_column_key", next.name(), "字段主键属性发生变化"));
            if (!Objects.equals(old.defaultValue(), next.defaultValue())) result.add(new Change("risk", "alter_default", next.name(), "默认值发生变化"));
        }
        before.fields().stream().filter(f -> !matched.contains(f)).forEach(f -> result.add(new Change("breaking", "drop_column", f.name(), f.type())));
        if (!Objects.equals(before.primaryKeys(), after.primaryKeys())) result.add(new Change("breaking", "alter_keys", null, "主键发生变化"));
        if (!Objects.equals(before.partitionKeys(), after.partitionKeys())) result.add(new Change("breaking", "alter_partition", null, "分区键发生变化"));
        if (result.isEmpty()) result.add(new Change("risk", "metadata_changed", null, "字段顺序或引擎标识变化，需要核对"));
        return result;
    }

    private static boolean matches(Long oldId, String oldName, Long nextId, String nextName) {
        return oldId != null && nextId != null ? oldId.equals(nextId) : Objects.equals(oldName, nextName);
    }
    private String normalizeType(String type) {
        if (type == null || type.isBlank()) throw new IllegalArgumentException("字段类型为空");
        return type.startsWith("{") ? type : type.trim().replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
    }
    private String typeSeverity(String from, String to) {
        List<String> integers = List.of("TINYINT", "SMALLINT", "INT", "BIGINT");
        if (integers.contains(from) && integers.contains(to)) return integers.indexOf(to) > integers.indexOf(from) ? "compatible" : "breaking";
        var a = java.util.regex.Pattern.compile("VARCHAR\\((\\d+)\\)").matcher(from);
        var b = java.util.regex.Pattern.compile("VARCHAR\\((\\d+)\\)").matcher(to);
        if (a.matches() && b.matches()) return Long.parseLong(b.group(1)) >= Long.parseLong(a.group(1)) ? "compatible" : "breaking";
        if (from.startsWith("VARCHAR(") && to.equals("STRING")) return "compatible";
        if (from.equals("STRING") && to.startsWith("VARCHAR(")) return "breaking";
        return "risk"; // An unfamiliar/nested type is never declared compatible by guesswork.
    }
    private String severity(List<Change> changes) {
        return List.of("breaking", "risk", "compatible", "baseline").stream()
                .filter(s -> changes.stream().anyMatch(c -> c.severity().equals(s))).findFirst().orElse("risk");
    }
}
