package com.rtdwh.controller;

import com.rtdwh.dto.ApiResponse;
import com.rtdwh.dto.QueryExecuteDTO;
import com.rtdwh.dto.QueryCatalogDTO;
import com.rtdwh.dto.SavedQueryUpsertDTO;
import com.rtdwh.entity.QueryHistory;
import com.rtdwh.entity.SavedQuery;
import com.rtdwh.service.DorisCatalogService;
import com.rtdwh.service.QueryService;
import com.rtdwh.service.ReportService;
import com.rtdwh.service.ReportParameterRenderer;
import com.rtdwh.service.SavedQueryService;
import com.rtdwh.util.SecurityContextUtil;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/query")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('query:adhoc')")
public class QueryController {

    private final QueryService queryService;
    private final ReportService reportService;
    private final DorisCatalogService dorisCatalogService;
    private final SavedQueryService savedQueryService;
    private final SecurityContextUtil securityContextUtil;
    private final ReportParameterRenderer parameterRenderer;

    /**
     * Execute an ad-hoc SQL query.
     */
    @PostMapping("/execute")
    public ApiResponse<Map<String, Object>> executeQuery(@Valid @RequestBody QueryExecuteDTO dto) {
        Long userId = securityContextUtil.getCurrentUserId();
        Map<String, Object> result = queryService.executeQuery(dto, userId);
        return ApiResponse.success(result);
    }

    /**
     * Export query results to CSV.
     */
    @PostMapping("/export")
    public ResponseEntity<byte[]> exportQuery(@Valid @RequestBody QueryExecuteDTO dto) {
        Long userId = securityContextUtil.getCurrentUserId();
        byte[] csv = queryService.exportToCsv(dto, userId).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=query-result.csv")
                .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
                .body(csv);
    }

    /**
     * Cancel a running query.
     */
    @PostMapping("/cancel/{historyId}")
    public ApiResponse<Void> cancelQuery(@PathVariable Long historyId) {
        Long userId = securityContextUtil.getCurrentUserId();
        queryService.cancelQuery(historyId, userId);
        return ApiResponse.success("查询已取消", null);
    }

    @PostMapping("/cancel-request/{requestId}")
    public ApiResponse<Void> cancelQueryByRequestId(@PathVariable String requestId) {
        Long userId = securityContextUtil.getCurrentUserId();
        queryService.cancelQueryByRequestId(requestId, userId);
        return ApiResponse.success("查询已取消", null);
    }

    /**
     * Get paginated query history.
     */
    @GetMapping("/history")
    public ApiResponse<Page<QueryHistory>> getQueryHistory(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Long userId = securityContextUtil.getCurrentUserId();
        return ApiResponse.success(queryService.getQueryHistoryPage(userId, page, size));
    }

    @GetMapping("/history/{historyId}/profile")
    public ApiResponse<Map<String, Object>> getQueryProfile(@PathVariable Long historyId) {
        return ApiResponse.success(queryService.getQueryProfile(
                historyId, securityContextUtil.getCurrentUserId()));
    }

    @GetMapping("/catalog")
    public ApiResponse<QueryCatalogDTO> getCatalog() {
        return ApiResponse.success(dorisCatalogService.getQueryCatalog(securityContextUtil.getCurrentUserId()));
    }

    @GetMapping("/governance/stats")
    public ApiResponse<Map<String, Object>> getGovernanceStats() {
        return ApiResponse.success(queryService.getGovernanceStats(securityContextUtil.getCurrentUserId()));
    }

    @GetMapping("/saved")
    public ApiResponse<List<SavedQuery>> listSavedQueries() {
        return ApiResponse.success(savedQueryService.list(securityContextUtil.getCurrentUserId()));
    }

    @PostMapping("/saved")
    public ApiResponse<SavedQuery> createSavedQuery(@Valid @RequestBody SavedQueryUpsertDTO dto) {
        return ApiResponse.success("SQL 已保存到服务端",
                savedQueryService.create(securityContextUtil.getCurrentUserId(), dto));
    }

    @PutMapping("/saved/{id}")
    public ApiResponse<SavedQuery> updateSavedQuery(@PathVariable Long id,
                                                    @Valid @RequestBody SavedQueryUpsertDTO dto) {
        return ApiResponse.success("SQL 已更新",
                savedQueryService.update(id, securityContextUtil.getCurrentUserId(), dto));
    }

    @DeleteMapping("/saved/{id}")
    public ApiResponse<Void> deleteSavedQuery(@PathVariable Long id) {
        savedQueryService.delete(id, securityContextUtil.getCurrentUserId());
        return ApiResponse.success("SQL 已删除", null);
    }

    /**
     * Execute a report query using the report's SQL template.
     */
    @PostMapping("/report/{reportId}")
    public ApiResponse<Map<String, Object>> executeReport(
            @PathVariable Long reportId,
            @RequestBody(required = false) Map<String, Object> params) {
        Long userId = securityContextUtil.getCurrentUserId();
        com.rtdwh.entity.ReportTemplate report = reportService.getReport(reportId, userId);
        if (!Boolean.TRUE.equals(report.getIsPublished())) {
            throw new IllegalStateException("报告尚未发布，无法查询");
        }
        String sql = parameterRenderer.render(report.getSqlQuery(), report.getFilterConfig(), params);
        return ApiResponse.success(queryService.executeReportQuery(sql, userId));
    }
}
