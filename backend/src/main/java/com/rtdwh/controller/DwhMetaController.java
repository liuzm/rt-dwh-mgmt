package com.rtdwh.controller;

import com.rtdwh.dto.ApiResponse;
import com.rtdwh.dto.DwhSnapshotDTO;
import com.rtdwh.dto.DwhMetadataUpdateDTO;
import com.rtdwh.entity.DwhColumnMeta;
import com.rtdwh.entity.DwhTableMeta;
import com.rtdwh.entity.DwhTableMeta.TableLayer;
import com.rtdwh.entity.TableMaintenanceLog;
import com.rtdwh.entity.TableMaintenanceLog.Operation;
import com.rtdwh.entity.TableMaintenanceLog.Status;
import com.rtdwh.service.DwhMetaService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/dwh")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('dwh:view')")
public class DwhMetaController {

    private final DwhMetaService dwhMetaService;
    private final com.rtdwh.service.QueryAccessScopeService access;
    private final com.rtdwh.util.SecurityContextUtil security;
    private final com.rtdwh.repository.DwhColumnMetaRepository columns;
    @org.springframework.beans.factory.annotation.Value("${doris.catalog:rtdwh_paimon}")
    private String catalog = "rtdwh_paimon";

    @GetMapping("/tables")
    public ApiResponse<List<DwhTableMeta>> listTables(
            @RequestParam(required = false) String layer,
            @RequestParam(required = false) String database,
            @RequestParam(required = false) String keyword) {

        TableLayer layerEnum = layer != null ? TableLayer.valueOf(layer) : null;
        return ApiResponse.success(access.filterAllowed(security.getCurrentUserId(),
                dwhMetaService.listTables(layerEnum, database, keyword), this::catalogFor, DwhTableMeta::getPaimonDb, DwhTableMeta::getPaimonTable));
    }

    @GetMapping("/tables/{id}")
    public ApiResponse<DwhTableMeta> getTableDetail(@PathVariable Long id) {
        assertTableAccess(id);
        return ApiResponse.success(dwhMetaService.getTableDetail(id));
    }

    @GetMapping("/tables/{id}/columns")
    public ApiResponse<List<DwhColumnMeta>> getTableColumns(@PathVariable Long id) {
        assertTableAccess(id);
        return ApiResponse.success(dwhMetaService.getTableColumns(id));
    }

    @GetMapping("/tables/{id}/snapshots")
    public ApiResponse<List<DwhSnapshotDTO>> getTableSnapshots(@PathVariable Long id) {
        assertTableAccess(id);
        return ApiResponse.success(dwhMetaService.getTableSnapshots(id));
    }

    @PutMapping("/columns/{id}/comment")
    @PreAuthorize("hasAuthority('dwh:manage')")
    public ApiResponse<DwhColumnMeta> updateColumnComment(
            @PathVariable Long id,
            @RequestBody Map<String, String> body) {
        var column = columns.findById(id).orElseThrow(() -> new IllegalArgumentException("字段不存在"));
        assertTableAccess(column.getTableMetaId());
        return ApiResponse.success("字段注释已更新",
                dwhMetaService.updateColumnComment(id, body.get("comment")));
    }

    @PutMapping("/tables/{id}/metadata")
    @PreAuthorize("hasAuthority('dwh:manage')")
    public ApiResponse<DwhTableMeta> updateMetadata(
            @PathVariable Long id,
            @RequestBody DwhMetadataUpdateDTO body) {
        assertTableAccess(id);
        return ApiResponse.success("表治理信息已更新", dwhMetaService.updateMetadata(id, body));
    }

    @PostMapping("/sync-metadata")
    @PreAuthorize("hasAuthority('dwh:manage')")
    public ApiResponse<Integer> syncMetadataFromPaimon() {
        if (!access.isAdmin(security.getCurrentUserId())) throw new org.springframework.security.access.AccessDeniedException("全局元数据同步需要管理员权限");
        return ApiResponse.success("Metadata synced", dwhMetaService.syncMetadataFromPaimon());
    }

    @PostMapping("/tables/{id}/compact")
    @PreAuthorize("hasAuthority('dwh:manage')")
    public ApiResponse<Map<String, Object>> triggerCompact(
            @PathVariable Long id,
            @RequestParam(defaultValue = "minor") String compactStrategy) {
        assertTableAccess(id);
        return ApiResponse.success(dwhMetaService.triggerCompact(id, compactStrategy));
    }

    @PostMapping("/tables/{id}/expire-snapshots")
    @PreAuthorize("hasAuthority('dwh:manage')")
    public ApiResponse<Map<String, Object>> triggerExpireSnapshots(
            @PathVariable Long id,
            @RequestParam(defaultValue = "10") int retainLast) {
        assertTableAccess(id);
        return ApiResponse.success(dwhMetaService.triggerExpireSnapshots(id, retainLast));
    }

    @PostMapping("/tables/{id}/orphan-cleanup")
    @PreAuthorize("hasAuthority('dwh:manage')")
    public ApiResponse<Map<String, Object>> triggerOrphanCleanup(@PathVariable Long id) {
        assertTableAccess(id);
        return ApiResponse.success(dwhMetaService.triggerOrphanCleanup(id));
    }

    // ======== Maintenance Logs ========

    @GetMapping("/maintenance/logs")
    public ApiResponse<List<TableMaintenanceLog>> getMaintenanceLogs(
            @RequestParam(required = false) Long tableMetaId,
            @RequestParam(required = false) String operation,
            @RequestParam(required = false) String status) {
        Operation opEnum = operation != null ? Operation.valueOf(operation) : null;
        Status statusEnum = status != null ? Status.valueOf(status) : null;
        if (tableMetaId != null) assertTableAccess(tableMetaId);
        return ApiResponse.success(dwhMetaService.getMaintenanceLogs(tableMetaId, opEnum, statusEnum, security.getCurrentUserId()));
    }

    @PostMapping("/maintenance/batch-compact")
    @PreAuthorize("hasAuthority('dwh:manage')")
    public ApiResponse<Map<String, Object>> batchCompact(@RequestBody Map<String, Object> body) {
        TableLayer layer = parseLayer(body.get("layer"));
        int threshold = body.get("fileCountThreshold") instanceof Number number ? number.intValue() : 200;
        assertBatchAccess(layer);
        return ApiResponse.success(dwhMetaService.batchCompact(layer, Math.max(1, threshold)));
    }

    @PostMapping("/maintenance/batch-expire")
    @PreAuthorize("hasAuthority('dwh:manage')")
    public ApiResponse<Map<String, Object>> batchExpireSnapshots(@RequestBody Map<String, Object> body) {
        TableLayer layer = parseLayer(body.get("layer"));
        int retainLast = body.get("retainLast") instanceof Number number ? number.intValue() : 10;
        assertBatchAccess(layer);
        return ApiResponse.success(dwhMetaService.batchExpireSnapshots(layer, Math.max(1, retainLast)));
    }

    @PostMapping("/maintenance/clean-orphan")
    @PreAuthorize("hasAuthority('dwh:manage')")
    public ApiResponse<Map<String, Object>> cleanOrphanFiles(@RequestBody(required = false) Map<String, Object> body) {
        Long tableId = null;
        if (body != null && body.get("tableId") instanceof Number number) {
            tableId = number.longValue();
        }
        if (tableId == null) assertBatchAccess(null); else assertTableAccess(tableId);
        return ApiResponse.success(dwhMetaService.batchOrphanCleanup(tableId));
    }

    private String catalogFor(DwhTableMeta table) { return table.getCatalogName() == null ? catalog : table.getCatalogName(); }

    private void assertTableAccess(Long id) {
        DwhTableMeta table = dwhMetaService.getTableDetail(id);
        if (!access.allowed(security.getCurrentUserId(), catalogFor(table), table.getPaimonDb(), table.getPaimonTable())) {
            throw new org.springframework.security.access.AccessDeniedException("无权访问该数据表");
        }
    }

    private void assertBatchAccess(TableLayer layer) {
        for (DwhTableMeta table : dwhMetaService.listTables(layer, null, null)) assertTableAccess(table.getId());
    }

    private TableLayer parseLayer(Object value) {
        if (value == null || value.toString().isBlank() || "all".equalsIgnoreCase(value.toString())) {
            return null;
        }
        return TableLayer.valueOf(value.toString().toLowerCase());
    }
}
