package com.rtdwh.controller;

import com.rtdwh.dto.ApiResponse;
import com.rtdwh.entity.*;
import com.rtdwh.repository.DwhTableMetaRepository;
import com.rtdwh.service.*;
import com.rtdwh.util.SecurityContextUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController @RequestMapping("/dwh/assets") @RequiredArgsConstructor
@PreAuthorize("hasAuthority('dwh:view')")
public class AssetController {
    private final DwhTableMetaRepository tables;
    private final AssetSchemaService schemas;
    private final AssetContextService context;
    private final QueryAccessScopeService access;
    private final SecurityContextUtil security;
    @Value("${doris.catalog:rtdwh_paimon}") private String catalog = "rtdwh_paimon";

    @GetMapping("/{assetId}") public ApiResponse<DwhTableMeta> detail(@PathVariable String assetId) { return ApiResponse.success(authorized(assetId)); }
    @GetMapping("/{assetId}/schema-revisions") public ApiResponse<List<AssetSchemaRevision>> revisions(@PathVariable String assetId) {
        return ApiResponse.success(schemas.history(authorized(assetId).getId()));
    }
    @GetMapping("/{assetId}/context") public ApiResponse<AssetContextService.Context> context(@PathVariable String assetId) {
        return ApiResponse.success(context.context(authorized(assetId), security.getCurrentUserId()));
    }
    private DwhTableMeta authorized(String assetId) {
        var table = tables.findByAssetId(assetId).orElseThrow(() -> new IllegalArgumentException("资产不存在"));
        if (!access.allowed(security.getCurrentUserId(), table.getCatalogName() == null ? catalog : table.getCatalogName(), table.getPaimonDb(), table.getPaimonTable()))
            throw new AccessDeniedException("无权访问该资产");
        return table;
    }
}
