package com.rtdwh.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rtdwh.entity.SyncTask;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class FlinkClusterService {

    private static final HttpClient FLINK_CANCEL_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @Value("${flink.rest-api.url}")
    private String flinkRestUrl;

    @Value("${flink.rest-api.timeout-seconds:30}")
    private int timeoutSeconds;

    @Value("${flink.submission.mode:application}")
    private String submissionMode;

    @Value("${flink.submission.jar-path:/opt/flink/lib}")
    private String jarPath;

    @Value("${flink.submission.savepoint-dir:file:///tmp/flink-savepoints}")
    private String savepointDir;

    @Value("${flink.sql-gateway.enabled:false}")
    private boolean sqlGatewayEnabled;

    @Value("${flink.sql-gateway.url:http://localhost:9083}")
    private String sqlGatewayUrl;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    // Public getters for controller access
    public RestTemplate getRestTemplate() { return restTemplate; }
    public ObjectMapper getObjectMapper() { return objectMapper; }
    public String getFlinkRestUrl() { return flinkRestUrl; }
    public String getSubmissionMode() { return submissionMode; }
    public String getSavepointDir() { return savepointDir; }
    public boolean isSqlGatewayEnabled() { return sqlGatewayEnabled; }
    public String getSqlGatewayUrl() { return sqlGatewayUrl; }

    /** Update the active runtime connection used by subsequent Flink operations. */
    public void updateRuntimeConfig(String restUrl, String mode) {
        this.flinkRestUrl = restUrl.replaceAll("/+$", "");
        this.submissionMode = mode;
    }

    /** Update all editable runtime settings used by subsequent Flink operations. */
    public void updateRuntimeConfig(
            String restUrl,
            String mode,
            String savepointDirectory,
            boolean gatewayEnabled,
            String gatewayUrl
    ) {
        updateRuntimeConfig(restUrl, mode);
        this.savepointDir = savepointDirectory;
        this.sqlGatewayEnabled = gatewayEnabled;
        this.sqlGatewayUrl = gatewayUrl.replaceAll("/+$", "");
    }

    // ========================================================================
    // 1. Jar Upload + Run (Application / Session Mode)
    // ========================================================================

    /**
     * Upload a Flink JAR to the cluster and run it.
     * Flink REST API flow:
     *   POST /jars/upload  → get jarId
     *   POST /jars/{jarId}/run → get jobId
     */
    public Map<String, Object> submitJob(SyncTask task) {
        log.info("Submitting Flink job for task [{}] type={}", task.getTaskName(), task.getTaskType());

        try {
            // Step 1: If we already have a jarId stored, skip upload
            String jarId = task.getFlinkJarId();
            if (jarId == null) {
                // For CDC jobs, we use the flink-cdc-paimon-connector jar
                // In production, this jar should already be pre-uploaded to the cluster
                jarId = uploadOrGetJar(task);
            }

            // Step 2: Run the jar
            Map<String, Object> runPayload = buildRunPayload(task, null);
            String runUrl = flinkRestUrl + "/jars/" + jarId + "/run";

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, Object>> request = new HttpEntity<>(runPayload, headers);

            ResponseEntity<String> response = restTemplate.postForEntity(runUrl, request, String.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                JsonNode json = objectMapper.readTree(response.getBody());
                String jobId = json.path("jobid").asText();

                log.info("Flink job submitted successfully: jobId={}, task={}", jobId, task.getTaskName());
                return Map.of(
                    "jobId", jobId,
                    "jarId", jarId,
                    "submittedAt", LocalDateTime.now().toString()
                );
            }
            throw new IllegalStateException("Flink job submission failed: HTTP " + response.getStatusCode());
        } catch (Exception e) {
            log.error("Failed to submit Flink job for task [{}]: {}", task.getTaskName(), e.getMessage());
            throw new RuntimeException("Flink job submission error: " + e.getMessage(), e);
        }
    }

    /**
     * Submit from savepoint (resume from paused state)
     */
    public Map<String, Object> submitFromSavepoint(SyncTask task, String savepointPath) {
        log.info("Resubmitting Flink job from savepoint: {}", savepointPath);

        try {
            String jarId = task.getFlinkJarId();
            if (jarId == null) {
                jarId = uploadOrGetJar(task);
            }

            Map<String, Object> runPayload = buildRunPayload(task, savepointPath);
            String runUrl = flinkRestUrl + "/jars/" + jarId + "/run";

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, Object>> request = new HttpEntity<>(runPayload, headers);

            ResponseEntity<String> response = restTemplate.postForEntity(runUrl, request, String.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                JsonNode json = objectMapper.readTree(response.getBody());
                String jobId = json.path("jobid").asText();

                log.info("Flink job resubmitted from savepoint: jobId={}", jobId);
                return Map.of(
                    "jobId", jobId,
                    "jarId", jarId,
                    "submittedAt", LocalDateTime.now().toString()
                );
            }
            throw new IllegalStateException("Flink job resubmission failed: HTTP " + response.getStatusCode());
        } catch (Exception e) {
            throw new RuntimeException("Flink resubmission error: " + e.getMessage(), e);
        }
    }

    /**
     * Find or upload the CDC/ETL jar to the Flink cluster.
     * In production, jars are typically pre-deployed. This method:
     * 1. Lists existing jars on the cluster
     * 2. If matching jar found, reuse it
     * 3. Otherwise, "upload" (simulated - in real deployment use multipart)
     */
    private String uploadOrGetJar(SyncTask task) {
        try {
            // List existing jars
            ResponseEntity<String> listResp = restTemplate.getForEntity(
                flinkRestUrl + "/jars", String.class);

            if (listResp.getStatusCode().is2xxSuccessful() && listResp.getBody() != null) {
                JsonNode json = objectMapper.readTree(listResp.getBody());
                JsonNode files = json.path("files");

                // Look for CDC connector jar
                String jarPrefix = getJarPrefixForTaskType(task.getTaskType());
                for (JsonNode file : files) {
                    String name = file.path("name").asText();
                    if (name.startsWith(jarPrefix)) {
                        String id = file.path("id").asText();
                        log.info("Found existing jar on cluster: {} → {}", name, id);
                        return id;
                    }
                }
            }

            // No matching jar found - for CDC sync, use the SQL-based approach
            // via Flink SQL Gateway if available, otherwise use a generic jar
            if (sqlGatewayEnabled) {
                log.info("No CDC jar found, will use SQL Gateway for task submission");
                return null; // Will use SQL Gateway instead
            }

            // Fallback: use the first available jar or a generic Flink job jar
            if (listResp.getStatusCode().is2xxSuccessful() && listResp.getBody() != null) {
                JsonNode json = objectMapper.readTree(listResp.getBody());
                JsonNode files = json.path("files");
                if (files.size() > 0) {
                    String id = files.get(0).path("id").asText();
                    log.warn("Using fallback jar: {}", id);
                    return id;
                }
            }

            throw new IllegalStateException("No Flink jars available on cluster. "
                + "Please deploy the flink-cdc-paimon jar or enable SQL Gateway.");
        } catch (Exception e) {
            log.error("Failed to find/upload jar: {}", e.getMessage());
            throw new RuntimeException("Jar resolution error: " + e.getMessage(), e);
        }
    }

    private String getJarPrefixForTaskType(SyncTask.TaskType type) {
        switch (type) {
            case cdc_sync: return "flink-cdc";
            case etl: return "flink-sql";
            case materialized: return "flink-sql";
            default: return "flink";
        }
    }

    private Map<String, Object> buildRunPayload(SyncTask task, String savepointPath) {
        Map<String, Object> payload = new LinkedHashMap<>();

        if (submissionMode.equals("application")) {
            // Application Mode: specify entry point class
            payload.put("entryClass", getEntryClass(task));
        }

        // Program args: Flink REST API expects a JSON array of strings
        payload.put("programArgs", buildProgramArgsList(task));

        // Parallelism
        payload.put("parallelism", task.getParallelism() != null ? task.getParallelism() : 1);

        // Savepoint path (for resuming)
        if (savepointPath != null) {
            payload.put("savepointPath", savepointPath);
            payload.put("allowNonRestoredState", true);
        }

        // Flink configuration overrides
        Map<String, String> flinkConfig = new LinkedHashMap<>();
        if (task.getCheckpointIntervalMs() != null) {
            flinkConfig.put("execution.checkpointing.interval", task.getCheckpointIntervalMs() + "ms");
            flinkConfig.put("execution.checkpointing.mode", "EXACTLY_ONCE");
            String storageUri = normalizeStorageUri(savepointDir);
            flinkConfig.put("execution.checkpointing.dir", storageUri + "/checkpoints");
            flinkConfig.put("execution.checkpointing.savepoint-dir", storageUri);
        }
        if (!flinkConfig.isEmpty()) {
            payload.put("flinkConfiguration", flinkConfig);
        }

        return payload;
    }

    private String getEntryClass(SyncTask task) {
        switch (task.getTaskType()) {
            case cdc_sync: return "org.apache.flink.cdc.CDCSyncJob";
            case etl: return "org.apache.flink.sql.FlinkSqlJob";
            case materialized: return "org.apache.flink.table.MaterializedTableJob";
            default: return "org.apache.flink.sql.FlinkSqlJob";
        }
    }

    private List<String> buildProgramArgsList(SyncTask task) {
        List<String> args = new ArrayList<>();
        args.add("--task-id=" + task.getId());
        args.add("--task-name=" + task.getTaskName());
        args.add("--task-type=" + task.getTaskType().name());
        args.add("--source-config-id=" + task.getSourceConfigId());
        args.add("--target-config-id=" + task.getTargetConfigId());
        if (task.getFlinkSql() != null && !task.getFlinkSql().trim().isEmpty()) {
            args.add("--sql=" + task.getFlinkSql());
        }
        return args;
    }

    private String buildProgramArgs(SyncTask task) {
        return String.join(" ", buildProgramArgsList(task));
    }

    // ========================================================================
    // 2. SQL Gateway Submission (Flink 2.x recommended)
    // ========================================================================

    /**
     * Submit a Flink SQL job via the SQL Gateway (Flink 2.x feature).
     * POST /v1/sessions/{sessionId}/statements
     */
    public Map<String, Object> submitViaSqlGateway(SyncTask task) {
        return submitViaSqlGateway(task, null);
    }

    /** Submit SQL and optionally restore the resulting job from a savepoint. */
    public Map<String, Object> submitViaSqlGateway(SyncTask task, String restorePath) {
        if (!sqlGatewayEnabled) {
            throw new IllegalStateException("SQL Gateway is not enabled");
        }
        if (task.getFlinkSql() == null || task.getFlinkSql().isBlank()) {
            throw new IllegalArgumentException("Flink SQL is empty");
        }
        log.info("Submitting Flink SQL via SQL Gateway for task [{}]", task.getTaskName());

        String sessionHandle = null;
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            // The SQL Gateway REST API uses sessionHandle/operationHandle. Do
            // not send the old sessionConfig shape, which is not part of the API.
            HttpEntity<Void> sessionReq = new HttpEntity<>(headers);

            ResponseEntity<String> sessionResp = restTemplate.postForEntity(
                sqlGatewayUrl + "/v1/sessions", sessionReq, String.class);

            if (sessionResp.getStatusCode().is2xxSuccessful() && sessionResp.getBody() != null) {
                JsonNode json = objectMapper.readTree(sessionResp.getBody());
                sessionHandle = firstNonBlankText(json, "sessionHandle", "sessionId");
            }

            if (sessionHandle == null || sessionHandle.isBlank()) {
                throw new IllegalStateException("SQL Gateway did not return a sessionHandle");
            }

            List<String> statements = splitSqlStatements(task.getFlinkSql());
            if (statements.isEmpty()) {
                throw new IllegalArgumentException("Flink SQL contains no executable statements");
            }

            Set<String> jobsBeforeSubmission = listClusterJobIds();
            Map<String, String> executionConfig = buildSqlGatewayExecutionConfig(task, restorePath);
            String jobId = null;
            String lastOperationHandle = null;

            // SQL Gateway executes one statement per operation. Catalog, USE,
            // database and table DDL must finish in the same session before INSERT.
            for (int index = 0; index < statements.size(); index++) {
                String statement = statements.get(index);
                Map<String, Object> statementPayload = new LinkedHashMap<>();
                statementPayload.put("statement", statement);
                statementPayload.put("executionConfig", executionConfig);

                ResponseEntity<String> statementResponse = restTemplate.postForEntity(
                        sqlGatewayUrl + "/v1/sessions/" + sessionHandle + "/statements",
                        new HttpEntity<>(statementPayload, headers),
                        String.class
                );
                if (!statementResponse.getStatusCode().is2xxSuccessful()
                        || statementResponse.getBody() == null) {
                    throw new IllegalStateException("SQL Gateway rejected statement " + (index + 1)
                            + ": HTTP " + statementResponse.getStatusCode());
                }

                JsonNode operationResponse = objectMapper.readTree(statementResponse.getBody());
                lastOperationHandle = firstNonBlankText(operationResponse, "operationHandle", "operationId");
                if (lastOperationHandle == null || lastOperationHandle.isBlank()) {
                    throw new IllegalStateException("SQL Gateway did not return operationHandle for statement "
                            + (index + 1));
                }

                JsonNode operationResult = waitForSqlGatewayOperation(sessionHandle, lastOperationHandle);
                String resultJobId = extractFlinkJobId(operationResult);
                if (resultJobId != null) {
                    jobId = resultJobId;
                }
            }

            if (jobId == null) {
                jobId = waitForNewClusterJob(jobsBeforeSubmission, task.getTaskName());
            }
            if (jobId == null) {
                throw new IllegalStateException("SQL submitted but Flink job ID was not returned by SQL Gateway");
            }

            log.info("SQL Gateway job submitted: sessionHandle={}, operationHandle={}, jobId={}",
                    sessionHandle, lastOperationHandle, jobId);
            return Map.of(
                    "jobId", jobId,
                    "sessionId", sessionHandle,
                    "submittedAt", LocalDateTime.now().toString()
            );
        } catch (Exception e) {
            log.error("SQL Gateway submission error: {}", e.getMessage());
            throw new RuntimeException("SQL Gateway submission error: " + e.getMessage(), e);
        } finally {
            if (sessionHandle != null) {
                try {
                    restTemplate.delete(sqlGatewayUrl + "/v1/sessions/" + sessionHandle);
                    log.debug("SQL Gateway session {} cleaned up", sessionHandle);
                } catch (Exception cleanupEx) {
                    log.warn("Failed to clean up SQL Gateway session {}: {}",
                            sessionHandle, cleanupEx.getMessage());
                }
            }
        }
    }

    private JsonNode waitForSqlGatewayOperation(String sessionHandle, String operationHandle) {
        for (int attempt = 0; attempt < 60; attempt++) {
            try {
                ResponseEntity<String> statusResponse = restTemplate.getForEntity(
                    sqlGatewayUrl + "/v1/sessions/" + sessionHandle
                    + "/operations/" + operationHandle + "/status",
                    String.class);

                if (statusResponse.getStatusCode().is2xxSuccessful()
                        && statusResponse.getBody() != null) {
                    JsonNode statusJson = objectMapper.readTree(statusResponse.getBody());
                    String status = statusJson.path("status").asText("");
                    if ("FINISHED".equalsIgnoreCase(status)) {
                        return fetchSqlGatewayOperationResult(sessionHandle, operationHandle);
                    }
                    if (Set.of("ERROR", "FAILED", "CANCELED", "CLOSED").contains(status.toUpperCase())) {
                        JsonNode errorResult = fetchSqlGatewayOperationResult(sessionHandle, operationHandle);
                        String detail = extractSqlGatewayError(errorResult);
                        throw new IllegalStateException("SQL Gateway operation ended with status " + status
                                + (detail == null ? "" : ": " + detail));
                    }
                }
            } catch (IllegalStateException e) {
                throw e;
            } catch (Exception e) {
                log.warn("SQL Gateway operation poll {} failed: {}", attempt + 1, e.getMessage());
            }

            try {
                Thread.sleep(500);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for SQL Gateway operation", interrupted);
            }
        }

        throw new IllegalStateException("SQL Gateway operation timed out after 30 seconds");
    }

    private JsonNode fetchSqlGatewayOperationResult(String sessionHandle, String operationHandle) {
        try {
            ResponseEntity<String> resultResponse = restTemplate.getForEntity(
                    sqlGatewayUrl + "/v1/sessions/" + sessionHandle
                            + "/operations/" + operationHandle + "/result/0",
                    String.class
            );
            return resultResponse.getBody() == null
                    ? objectMapper.createObjectNode()
                    : objectMapper.readTree(resultResponse.getBody());
        } catch (RestClientResponseException exception) {
            try {
                return objectMapper.readTree(exception.getResponseBodyAsString());
            } catch (Exception parseException) {
                return objectMapper.createObjectNode().put("fetchError", exception.getMessage());
            }
        } catch (Exception exception) {
            return objectMapper.createObjectNode().put("fetchError", exception.getMessage());
        }
    }

    static String extractSqlGatewayError(JsonNode result) {
        JsonNode errors = result.path("errors");
        String detail = "";
        if (errors.isArray()) {
            for (JsonNode error : errors) {
                String candidate = error.asText("");
                if (candidate.contains("Caused by:") || candidate.length() > detail.length()) {
                    detail = candidate;
                }
            }
        }
        if (detail.isBlank()) {
            detail = result.path("fetchError").asText("");
        }
        if (detail.isBlank()) return null;
        // Keep the useful root-cause tail without filling sync_task.last_error_msg
        // with the entire server-side stack trace.
        int causedBy = detail.lastIndexOf("Caused by:");
        String concise = causedBy >= 0 ? detail.substring(causedBy) : detail;
        return concise.length() > 1800 ? concise.substring(0, 1800) + "..." : concise;
    }

    static String normalizeStorageUri(String location) {
        String value = location == null || location.isBlank()
                ? "file:///tmp/flink-savepoints"
                : location.trim();
        URI uri = URI.create(value);
        if (uri.getScheme() != null) {
            return value.replaceAll("/+$", "");
        }
        return Path.of(value).toAbsolutePath().normalize().toUri().toString().replaceAll("/+$", "");
    }

    private Map<String, String> buildSqlGatewayExecutionConfig(SyncTask task, String restorePath) {
        Map<String, String> config = new LinkedHashMap<>();
        config.put("pipeline.name", task.getTaskName());
        config.put("parallelism.default", String.valueOf(
                task.getParallelism() == null ? 1 : task.getParallelism()));
        config.put("table.dml-sync", "false");
        if (task.getCheckpointIntervalMs() != null) {
            config.put("execution.checkpointing.interval", task.getCheckpointIntervalMs() + "ms");
            config.put("execution.checkpointing.mode", "EXACTLY_ONCE");
            String storageUri = normalizeStorageUri(savepointDir);
            config.put("execution.checkpointing.dir", storageUri + "/checkpoints");
            config.put("execution.checkpointing.savepoint-dir", storageUri);
        }
        if (restorePath != null && !restorePath.isBlank()) {
            config.put("execution.savepoint.path", restorePath);
            config.put("execution.savepoint.ignore-unclaimed-state", "true");
        }

        try {
            URI restUri = URI.create(flinkRestUrl);
            if (restUri.getHost() != null) {
                config.put("rest.address", restUri.getHost());
                config.put("rest.port", String.valueOf(restUri.getPort() > 0 ? restUri.getPort() : 8081));
            }
        } catch (Exception ignored) {
            log.warn("Unable to derive SQL Gateway target cluster from {}", flinkRestUrl);
        }
        return config;
    }

    static List<String> splitSqlStatements(String sqlScript) {
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean singleQuoted = false;
        boolean doubleQuoted = false;
        boolean backtickQuoted = false;
        boolean lineComment = false;

        for (int index = 0; index < sqlScript.length(); index++) {
            char currentChar = sqlScript.charAt(index);
            char nextChar = index + 1 < sqlScript.length() ? sqlScript.charAt(index + 1) : '\0';

            if (lineComment) {
                if (currentChar == '\n' || currentChar == '\r') {
                    lineComment = false;
                    current.append(' ');
                }
                continue;
            }
            if (!singleQuoted && !doubleQuoted && !backtickQuoted
                    && currentChar == '-' && nextChar == '-') {
                lineComment = true;
                index++;
                continue;
            }
            if (currentChar == '\'' && !doubleQuoted && !backtickQuoted) {
                current.append(currentChar);
                if (singleQuoted && nextChar == '\'') {
                    current.append(nextChar);
                    index++;
                } else {
                    singleQuoted = !singleQuoted;
                }
                continue;
            }
            if (currentChar == '"' && !singleQuoted && !backtickQuoted) {
                doubleQuoted = !doubleQuoted;
            } else if (currentChar == '`' && !singleQuoted && !doubleQuoted) {
                backtickQuoted = !backtickQuoted;
            }

            if (currentChar == ';' && !singleQuoted && !doubleQuoted && !backtickQuoted) {
                String statement = current.toString().trim();
                if (!statement.isEmpty()) {
                    statements.add(statement);
                }
                current.setLength(0);
            } else {
                current.append(currentChar);
            }
        }

        String tail = current.toString().trim();
        if (!tail.isEmpty()) {
            statements.add(tail);
        }
        return statements;
    }

    private String firstNonBlankText(JsonNode json, String... fields) {
        for (String field : fields) {
            String value = json.path(field).asText("");
            if (!value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    static String extractFlinkJobId(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isTextual()) {
            String value = node.asText().trim();
            if (value.matches("(?i)[0-9a-f]{32}")) {
                return value;
            }
            return null;
        }
        if (node.isContainerNode()) {
            for (JsonNode child : node) {
                String found = extractFlinkJobId(child);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private Set<String> listClusterJobIds() {
        Set<String> ids = new LinkedHashSet<>();
        try {
            ResponseEntity<String> response = restTemplate.getForEntity(
                    flinkRestUrl + "/jobs/overview", String.class);
            if (response.getBody() != null) {
                for (JsonNode job : objectMapper.readTree(response.getBody()).path("jobs")) {
                    String id = job.path("jid").asText("");
                    if (!id.isBlank()) ids.add(id);
                }
            }
        } catch (Exception exception) {
            log.warn("Unable to snapshot Flink jobs before SQL submission: {}", exception.getMessage());
        }
        return ids;
    }

    private String waitForNewClusterJob(Set<String> existingJobIds, String taskName) {
        for (int attempt = 0; attempt < 15; attempt++) {
            try {
                ResponseEntity<String> response = restTemplate.getForEntity(
                        flinkRestUrl + "/jobs/overview", String.class);
                if (response.getBody() != null) {
                    JsonNode jobs = objectMapper.readTree(response.getBody()).path("jobs");
                    String firstNewJob = null;
                    for (JsonNode job : jobs) {
                        String id = job.path("jid").asText("");
                        if (!id.isBlank() && !existingJobIds.contains(id)) {
                            if (taskName.equals(job.path("name").asText())) return id;
                            if (firstNewJob == null) firstNewJob = id;
                        }
                    }
                    if (firstNewJob != null) return firstNewJob;
                }
            } catch (Exception exception) {
                log.warn("Unable to resolve submitted Flink job ID: {}", exception.getMessage());
            }
            try {
                Thread.sleep(1000);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
    }

    // ========================================================================
    // 3. Stop with Savepoint (Async Operation)
    // ========================================================================

    /**
     * Trigger a stop-with-savepoint operation. This is ASYNC in Flink.
     * POST /jobs/{jobId}/stop?mode=cancel
     * Returns a triggerId that can be polled for completion.
     */
    public Map<String, Object> triggerStopWithSavepoint(String flinkJobId) {
        log.info("Triggering stop-with-savepoint for job: {}", flinkJobId);

        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("targetDirectory", normalizeStorageUri(savepointDir));
            payload.put("drain", false);  // Don't drain before savepoint for CDC jobs

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, Object>> request = new HttpEntity<>(payload, headers);

            // Flink 2.x: POST /jobs/{jobId}/stop with JSON body
            // Flink 1.x: POST /jobs/{jobId}/savepoints with mode=cancel_or_savepoint
            ResponseEntity<String> response;
            try {
                // Try Flink 2.x style first
                response = restTemplate.postForEntity(
                    flinkRestUrl + "/jobs/" + flinkJobId + "/stop", request, String.class);
            } catch (Exception e) {
                // Fallback to Flink 1.x style
                log.info("Flink /stop endpoint not available, falling back to /savepoints endpoint");
                Map<String, Object> savepointPayload = Map.of(
                    "targetDirectory", normalizeStorageUri(savepointDir),
                    "cancelJob", true  // cancel with savepoint (Flink 1.x way)
                );
                HttpEntity<Map<String, Object>> savepointReq = new HttpEntity<>(savepointPayload, headers);
                response = restTemplate.postForEntity(
                    flinkRestUrl + "/jobs/" + flinkJobId + "/savepoints", savepointReq, String.class);
            }

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                JsonNode json = objectMapper.readTree(response.getBody());
                String triggerId = json.path("triggerId").asText();

                log.info("Savepoint triggered: jobId={}, triggerId={}", flinkJobId, triggerId);
                return Map.of(
                    "triggerId", triggerId,
                    "jobId", flinkJobId,
                    "status", "PENDING"
                );
            }

            // Some Flink versions return 202 with triggerId in location header
            if (response.getStatusCode() == HttpStatus.ACCEPTED) {
                String location = response.getHeaders().getFirst("Location");
                String triggerId = location != null ?
                    location.substring(location.lastIndexOf("/") + 1) : "unknown";
                return Map.of(
                    "triggerId", triggerId,
                    "jobId", flinkJobId,
                    "status", "PENDING"
                );
            }

            throw new IllegalStateException("Stop-with-savepoint trigger failed: HTTP " + response.getStatusCode());
        } catch (Exception e) {
            log.error("Failed to trigger stop-with-savepoint: {}", e.getMessage());
            throw new RuntimeException("Savepoint trigger error: " + e.getMessage(), e);
        }
    }

    /**
     * Trigger a manual savepoint (without stopping the job).
     * POST /jobs/{jobId}/savepoints
     */
    public Map<String, Object> triggerSavepoint(String flinkJobId) {
        log.info("Triggering savepoint for job: {}", flinkJobId);

        try {
            Map<String, Object> payload = Map.of(
                "targetDirectory", normalizeStorageUri(savepointDir),
                "cancelJob", false
            );

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, Object>> request = new HttpEntity<>(payload, headers);

            ResponseEntity<String> response = restTemplate.postForEntity(
                flinkRestUrl + "/jobs/" + flinkJobId + "/savepoints", request, String.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                JsonNode json = objectMapper.readTree(response.getBody());
                String triggerId = json.path("triggerId").asText();

                return Map.of(
                    "triggerId", triggerId,
                    "jobId", flinkJobId,
                    "status", "PENDING"
                );
            }
            throw new IllegalStateException("Savepoint trigger failed");
        } catch (Exception e) {
            throw new RuntimeException("Savepoint trigger error: " + e.getMessage(), e);
        }
    }

    /**
     * Poll savepoint trigger status until completed or failed.
     * GET /jobs/{jobId}/savepoints/{triggerId}
     */
    public Map<String, Object> pollSavepointStatus(String flinkJobId, String triggerId) {
        log.debug("Polling savepoint status: jobId={}, triggerId={}", flinkJobId, triggerId);

        try {
            ResponseEntity<String> response = restTemplate.getForEntity(
                flinkRestUrl + "/jobs/" + flinkJobId + "/savepoints/" + triggerId,
                String.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                JsonNode json = objectMapper.readTree(response.getBody());
                String status = json.path("status").path("id").asText();

                String savepointPath = null;
                if ("COMPLETED".equals(status)) {
                    savepointPath = json.path("operation").path("savepointLocation").asText();
                    if (savepointPath.isEmpty()) {
                        // Some versions store it differently
                        savepointPath = json.path("location").asText();
                    }
                }

                return Map.of(
                    "triggerId", triggerId,
                    "status", status, // PENDING, IN_PROGRESS, COMPLETED, FAILED
                    "savepointPath", savepointPath != null ? savepointPath : "",
                    "failureCause", json.path("failureCause").asText("")
                );
            }
            return Map.of("triggerId", triggerId, "status", "UNKNOWN");
        } catch (Exception e) {
            log.warn("Failed to poll savepoint status: {}", e.getMessage());
            return Map.of("triggerId", triggerId, "status", "UNKNOWN", "error", e.getMessage());
        }
    }

    /**
     * Poll savepoint until completed, with configurable max attempts.
     */
    public String waitForSavepointCompletion(String flinkJobId, String triggerId) {
        int maxAttempts = 60;
        long pollIntervalMs = 3000;

        for (int i = 0; i < maxAttempts; i++) {
            Map<String, Object> result = pollSavepointStatus(flinkJobId, triggerId);
            String status = (String) result.get("status");

            switch (status) {
                case "COMPLETED":
                    return (String) result.get("savepointPath");
                case "FAILED":
                    String cause = (String) result.get("failureCause");
                    throw new IllegalStateException("Savepoint failed: " + cause);
                case "PENDING":
                case "IN_PROGRESS":
                default:
                    // Continue polling
                    try { Thread.sleep(pollIntervalMs); } catch (InterruptedException ignored) {}
            }
        }
        throw new IllegalStateException("Savepoint polling timed out after " + maxAttempts + " attempts");
    }

    // ========================================================================
    // 4. Cancel Job (Immediate stop, no savepoint)
    // ========================================================================

    /**
     * Cancel a Flink job immediately (no savepoint).
     * PATCH /jobs/{jobId}?mode=cancel
     * Or DELETE /jobs/{jobId} in some Flink versions
     */
    public void cancelJob(String flinkJobId) {
        log.info("Canceling Flink job: {}", flinkJobId);

        try {
            HttpRequest request = HttpRequest.newBuilder(
                            URI.create(flinkRestUrl + "/jobs/" + flinkJobId + "?mode=cancel"))
                    .timeout(Duration.ofSeconds(10))
                    .method("PATCH", HttpRequest.BodyPublishers.noBody())
                    .build();
            HttpResponse<Void> response = FLINK_CANCEL_CLIENT.send(
                    request, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("HTTP " + response.statusCode());
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            log.warn("Cancel job interrupted: {}", flinkJobId);
        } catch (Exception exception) {
            log.warn("Cancel job error (job may already be terminated): {}", exception.getMessage());
        }
    }

    // ========================================================================
    // 5. Job Status Monitoring (with Checkpoint info)
    // ========================================================================

    /**
     * Get comprehensive Flink job status including checkpoint details.
     * GET /jobs/{jobId}
     * GET /jobs/{jobId}/checkpoints
     */
    public Map<String, Object> getJobStatus(String flinkJobId) {
        log.debug("Fetching Flink job status: {}", flinkJobId);

        try {
            // Job overview
            ResponseEntity<String> jobResp = restTemplate.getForEntity(
                flinkRestUrl + "/jobs/" + flinkJobId, String.class);

            if (!jobResp.getStatusCode().is2xxSuccessful() || jobResp.getBody() == null) {
                return Map.of("status", "UNREACHABLE", "lagMs", 0L, "throughputQps", 0.0);
            }

            JsonNode jobJson = objectMapper.readTree(jobResp.getBody());
            String flinkState = jobJson.path("state").asText();
            long startTime = jobJson.path("start-time").asLong(0);
            long duration = jobJson.path("duration").asLong(0);

            // Map Flink state to our status
            String mappedStatus = mapFlinkState(flinkState);

            // Checkpoint details
            Map<String, Object> checkpointInfo = getCheckpointInfo(flinkJobId);

            // Metrics: source lag and throughput
            Long lagMs = getSourceLag(flinkJobId, jobJson);
            Double throughputQps = getThroughput(flinkJobId, jobJson);

            return Map.of(
                "status", mappedStatus,
                "flinkState", flinkState,
                "startTime", startTime,
                "duration", duration,
                "checkpointInfo", checkpointInfo,
                "lagMs", lagMs,
                "throughputQps", throughputQps
            );
        } catch (Exception e) {
            log.warn("Failed to get Flink job status for {}: {}", flinkJobId, e.getMessage());
            return Map.of("status", "UNREACHABLE", "lagMs", 0L, "throughputQps", 0.0);
        }
    }

    /**
     * Map Flink's own job state to our internal status
     */
    private String mapFlinkState(String flinkState) {
        switch (flinkState) {
            case "RUNNING": return "running";
            case "CANCELED": return "finished";
            case "FAILED": return "failed";
            case "FINISHED": return "finished";
            case "CREATED": return "submitting";
            case "RESTARTING": return "running";
            case "SUSPENDED": return "paused";
            case "Failing": return "failed";
            default: return flinkState.toLowerCase();
        }
    }

    private Map<String, Object> getCheckpointInfo(String flinkJobId) {
        try {
            ResponseEntity<String> resp = restTemplate.getForEntity(
                flinkRestUrl + "/jobs/" + flinkJobId + "/checkpoints", String.class);

            if (resp.getStatusCode().is2xxSuccessful() && resp.getBody() != null) {
                JsonNode json = objectMapper.readTree(resp.getBody());

                JsonNode latest = json.path("latest").path("completed");
                long count = json.path("counts").path("completed").asLong(0);
                long lastCompletedTs = latest.path("latest_ack_timestamp").asLong(0);
                long checkpointDuration = latest.path("state_size").asLong(0);

                return Map.of(
                    "completedCount", count,
                    "lastCompletedTimestamp", lastCompletedTs,
                    "stateSize", checkpointDuration,
                    "latestCheckpoint", latest.toString()
                );
            }
        } catch (Exception e) {
            log.warn("Failed to get checkpoint info: {}", e.getMessage());
        }
        return Map.of("completedCount", 0L);
    }

    private Long getSourceLag(String flinkJobId, JsonNode jobJson) {
        try {
            // Get source lag from vertex metrics
            JsonNode vertices = jobJson.path("vertices");
            for (JsonNode vertex : vertices) {
                String vertexId = vertex.path("id").asText();
                if (vertex.path("name").asText().contains("Source")) {
                    ResponseEntity<String> metricsResp = restTemplate.getForEntity(
                        flinkRestUrl + "/jobs/" + flinkJobId
                            + "/vertices/" + vertexId
                            + "/metrics?get=currentFetchEventTimeLag",
                        String.class);

                    if (metricsResp.getStatusCode().is2xxSuccessful() && metricsResp.getBody() != null) {
                        JsonNode metricsJson = objectMapper.readTree(metricsResp.getBody());
                        JsonNode values = metricsJson.path("metrics");
                        if (values.isArray() && values.size() > 0) {
                            return values.get(0).path("value").asLong(0);
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Failed to get source lag metrics: {}", e.getMessage());
        }
        return 0L;
    }

    private Double getThroughput(String flinkJobId, JsonNode jobJson) {
        try {
            JsonNode vertices = jobJson.path("vertices");
            double totalQps = 0;
            for (JsonNode vertex : vertices) {
                String vertexId = vertex.path("id").asText();
                ResponseEntity<String> metricsResp = restTemplate.getForEntity(
                    flinkRestUrl + "/jobs/" + flinkJobId
                        + "/vertices/" + vertexId
                        + "/metrics?get=numRecordsOutPerSecond",
                    String.class);

                if (metricsResp.getStatusCode().is2xxSuccessful() && metricsResp.getBody() != null) {
                    JsonNode metricsJson = objectMapper.readTree(metricsResp.getBody());
                    JsonNode values = metricsJson.path("metrics");
                    if (values.isArray() && values.size() > 0) {
                        totalQps += values.get(0).path("value").asDouble(0);
                    }
                }
            }
            return totalQps;
        } catch (Exception e) {
            return 0.0;
        }
    }

    // ========================================================================
    // 6. Job Logs
    // ========================================================================

    /**
     * Get job logs from JobManager or TaskManager.
     * GET /jobs/{jobId}/jobmanager/log?lines={n}
     * GET /taskmanagers/{tmId}/log?lines={n}
     */
    public Map<String, Object> getJobLogs(String flinkJobId, String type, int lines) {
        try {
            String logUrl;
            if ("jobmanager".equals(type)) {
                logUrl = flinkRestUrl + "/jobs/" + flinkJobId + "/jobmanager/log";
            } else {
                // First get TaskManager IDs for this job
                ResponseEntity<String> tmResp = restTemplate.getForEntity(
                    flinkRestUrl + "/jobs/" + flinkJobId + "/taskmanagers", String.class);

                if (tmResp.getStatusCode().is2xxSuccessful() && tmResp.getBody() != null) {
                    JsonNode json = objectMapper.readTree(tmResp.getBody());
                    JsonNode taskmanagers = json.path("taskmanagers");
                    if (taskmanagers.isArray() && taskmanagers.size() > 0) {
                        String tmId = taskmanagers.get(0).path("id").asText();
                        logUrl = flinkRestUrl + "/taskmanagers/" + tmId + "/log";
                    } else {
                        return Map.of("logs", "No TaskManagers found", "type", type);
                    }
                } else {
                    return Map.of("logs", "Failed to fetch TaskManager list", "type", type);
                }
            }

            ResponseEntity<String> logResp = restTemplate.getForEntity(logUrl, String.class);
            String logs = logResp.getBody() != null ? logResp.getBody() : "No logs available";

            // Truncate to last N lines
            if (lines > 0) {
                String[] logLines = logs.split("\n");
                int start = Math.max(0, logLines.length - lines);
                logs = String.join("\n", Arrays.copyOfRange(logLines, start, logLines.length));
            }

            return Map.of("logs", logs, "type", type, "lines", lines);
        } catch (Exception e) {
            return Map.of("logs", "Error fetching logs: " + e.getMessage(), "type", type);
        }
    }

    // ========================================================================
    // 7. Health Check
    // ========================================================================

    /**
     * Check Flink cluster health.
     * GET /overview
     */
    public Map<String, Object> healthCheck() {
        return healthCheck(flinkRestUrl);
    }

    /** Check an arbitrary Flink endpoint without changing the active runtime configuration. */
    public Map<String, Object> healthCheck(String restUrl) {
        String endpoint = restUrl.replaceAll("/+$", "");
        long startTime = System.currentTimeMillis();
        try {
            ResponseEntity<String> resp = restTemplate.getForEntity(
                endpoint + "/overview", String.class);

            if (resp.getStatusCode().is2xxSuccessful() && resp.getBody() != null) {
                String body = resp.getBody().trim();
                MediaType contentType = resp.getHeaders().getContentType();
                if (!body.startsWith("{") && !body.startsWith("[")) {
                    return invalidFlinkResponse(
                            endpoint,
                            startTime,
                            contentType,
                            "目标地址返回了网页内容而不是 Flink REST JSON；该端口可能被其他 Web 服务占用"
                    );
                }

                JsonNode json;
                try {
                    json = objectMapper.readTree(body);
                } catch (Exception parseException) {
                    return invalidFlinkResponse(
                            endpoint,
                            startTime,
                            contentType,
                            "目标地址返回的内容不是有效 JSON，无法识别为 Flink REST 服务"
                    );
                }

                if (!json.hasNonNull("flink-version") || !json.has("taskmanagers")) {
                    return invalidFlinkResponse(
                            endpoint,
                            startTime,
                            contentType,
                            "目标地址返回了 JSON，但缺少 Flink overview 标识字段"
                    );
                }

                Map<String, Object> result = new LinkedHashMap<>();
                result.put("status", "healthy");
                result.put("endpoint", endpoint);
                result.put("flinkVersion", json.path("flink-version").asText("unknown"));
                result.put("runningJobs", json.path("jobs-running").asInt());
                result.put("finishedJobs", json.path("jobs-finished").asInt());
                result.put("failedJobs", json.path("jobs-failed").asInt());
                result.put("cancelledJobs", json.path("jobs-cancelled").asInt());
                result.put("taskSlotsAvailable", json.path("slots-available").asInt());
                result.put("taskSlotsTotal", json.path("slots-total").asInt());
                result.put("taskManagers", json.path("taskmanagers").asInt());
                result.put("responseTimeMs", System.currentTimeMillis() - startTime);
                result.put("checkedAt", Instant.now().toString());
                return result;
            }
            return Map.of(
                    "status", "unhealthy",
                    "endpoint", endpoint,
                    "responseTimeMs", System.currentTimeMillis() - startTime,
                    "checkedAt", Instant.now().toString(),
                    "error", "Flink 返回了无效响应: HTTP " + resp.getStatusCode().value()
            );
        } catch (Exception e) {
            log.warn("Flink health check failed: {}", e.getMessage());
            return Map.of(
                    "status", "unreachable",
                    "endpoint", endpoint,
                    "responseTimeMs", System.currentTimeMillis() - startTime,
                    "checkedAt", Instant.now().toString(),
                    "error", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()
            );
        }
    }

    private Map<String, Object> invalidFlinkResponse(
            String endpoint,
            long startTime,
            MediaType contentType,
            String reason
    ) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "unhealthy");
        result.put("endpoint", endpoint);
        result.put("responseTimeMs", System.currentTimeMillis() - startTime);
        result.put("checkedAt", Instant.now().toString());
        result.put("diagnosticCode", "NOT_FLINK_REST");
        result.put("error", reason);
        result.put("suggestion", "请在“编辑配置”中填写真实的 Flink JobManager REST 地址，并确认 /overview 返回 JSON");
        if (contentType != null) {
            result.put("contentType", contentType.toString());
        }
        return result;
    }

    // ========================================================================
    // 8. Jar Management
    // ========================================================================

    /**
     * List all jars on the Flink cluster.
     * GET /jars
     */
    public List<Map<String, String>> listJars() {
        try {
            ResponseEntity<String> resp = restTemplate.getForEntity(
                flinkRestUrl + "/jars", String.class);

            if (resp.getStatusCode().is2xxSuccessful() && resp.getBody() != null) {
                JsonNode json = objectMapper.readTree(resp.getBody());
                JsonNode files = json.path("files");

                List<Map<String, String>> jars = new ArrayList<>();
                for (JsonNode file : files) {
                    jars.add(Map.of(
                        "id", file.path("id").asText(),
                        "name", file.path("name").asText(),
                        "uploaded", file.path("uploaded").asLong() + ""
                    ));
                }
                return jars;
            }
        } catch (Exception e) {
            log.warn("Failed to list jars: {}", e.getMessage());
        }
        return Collections.emptyList();
    }
}
