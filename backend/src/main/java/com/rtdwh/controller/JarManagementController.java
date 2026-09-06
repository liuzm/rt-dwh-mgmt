package com.rtdwh.controller;

import com.rtdwh.dto.ApiResponse;
import com.rtdwh.service.FlinkClusterService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/jars")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('task:manage')")
public class JarManagementController {

    private final FlinkClusterService flinkClusterService;

    /**
     * Upload a Flink JAR to the cluster.
     * POST /jars/upload with multipart/form-data
     */
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<Map<String, Object>> uploadJar(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "description", required = false) String description) {
        if (file.isEmpty()) {
            return ApiResponse.error(400, "上传文件不能为空");
        }

        try {
            // Save to temp file for multipart upload
            Path tempFile = Files.createTempFile("flink-jar-", "-" + file.getOriginalFilename());
            file.transferTo(tempFile.toFile());

            // Build multipart request
            org.springframework.util.LinkedMultiValueMap<String, Object> parts =
                new org.springframework.util.LinkedMultiValueMap<>();
            parts.add("jarfile", new org.springframework.core.io.FileSystemResource(tempFile.toFile()));
            if (description != null) {
                parts.add("classpaths", description);
            }

            org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
            headers.setContentType(MediaType.MULTIPART_FORM_DATA);
            org.springframework.http.HttpEntity<org.springframework.util.LinkedMultiValueMap<String, Object>> request =
                new org.springframework.http.HttpEntity<>(parts, headers);

            String uploadUrl = flinkClusterService.getFlinkRestUrl() + "/jars/upload";
            org.springframework.http.ResponseEntity<String> response =
                flinkClusterService.getRestTemplate().exchange(
                    uploadUrl,
                    org.springframework.http.HttpMethod.POST,
                    request,
                    String.class
                );

            Files.deleteIfExists(tempFile);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                com.fasterxml.jackson.databind.JsonNode json =
                    flinkClusterService.getObjectMapper().readTree(response.getBody());
                String jarId = json.path("jarId").asText();
                return ApiResponse.success(Map.of(
                    "jarId", jarId,
                    "fileName", file.getOriginalFilename(),
                    "size", file.getSize(),
                    "description", description
                ));
            }
            return ApiResponse.error(500, "JAR 上传失败: HTTP " + response.getStatusCode());
        } catch (IOException e) {
            log.error("JAR upload IO error: {}", e.getMessage());
            return ApiResponse.error(500, "JAR 上传失败: " + e.getMessage());
        } catch (Exception e) {
            log.error("JAR upload error: {}", e.getMessage());
            return ApiResponse.error(500, "JAR 上传失败: " + e.getMessage());
        }
    }

    /**
     * List all uploaded JARs on the cluster.
     * GET /jars
     */
    @GetMapping
    public ApiResponse<List<Map<String, String>>> listJars() {
        return ApiResponse.success(flinkClusterService.listJars());
    }

    /**
     * Delete a JAR from the cluster.
     * DELETE /jars/{jarId}
     */
    @DeleteMapping("/{jarId}")
    public ApiResponse<Void> deleteJar(@PathVariable String jarId) {
        try {
            String deleteUrl = flinkClusterService.getFlinkRestUrl() + "/jars/" + jarId;
            flinkClusterService.getRestTemplate().delete(deleteUrl);
            return ApiResponse.success("JAR 已删除", null);
        } catch (Exception e) {
            log.warn("Failed to delete jar {}: {}", jarId, e.getMessage());
            return ApiResponse.error(500, "删除失败: " + e.getMessage());
        }
    }
}
