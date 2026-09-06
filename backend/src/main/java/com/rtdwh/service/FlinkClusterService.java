package com.rtdwh.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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

    @Value("${flink.scaling.provider:standalone}")
    private String scalingProvider;

    @Value("${flink.scaling.min-parallelism:1}")
    private int scalingMinParallelism;

    @Value("${flink.scaling.max-parallelism:128}")
    private int scalingMaxParallelism;

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

        String boundGateway = SqlGatewayClient.endpoint(sqlGatewayUrl);
        SqlGatewayClient gatewayClient = new SqlGatewayClient(restTemplate, objectMapper);
        String sessionHandle = null;
        try {
            sessionHandle = gatewayClient.open(boundGateway, Map.of());

            List<String> statements = splitSqlStatements(task.getFlinkSql());
            if (statements.isEmpty()) {
                throw new IllegalArgumentException("Flink SQL contains no executable statements");
            }
            if (createsMultipleJobs(statements)) {
                throw new IllegalArgumentException(
                        "一条平台任务不能提交多条独立 INSERT；请使用 EXECUTE STATEMENT SET 合并为单个 Flink Job");
            }

            Set<String> jobsBeforeSubmission = listClusterJobIds();
            String submissionJobName = task.getTaskName()
                    + " [rtdwh-" + task.getId() + "-" + UUID.randomUUID() + "]";
            Map<String, String> executionConfig = buildSqlGatewayExecutionConfig(
                    task, restorePath, submissionJobName);
            Set<String> submittedJobIds = new LinkedHashSet<>();
            String lastOperationHandle = null;

            // SQL Gateway executes one statement per operation. Catalog, USE,
            // database and table DDL must finish in the same session before INSERT.
            for (int index = 0; index < statements.size(); index++) {
                String statement = statements.get(index);
                lastOperationHandle = gatewayClient.submit(boundGateway, sessionHandle, statement, executionConfig);
                JsonNode operationResult = waitForSqlGatewayOperation(boundGateway, sessionHandle, lastOperationHandle);
                String resultJobId = extractFlinkJobId(operationResult);
                if (resultJobId != null) {
                    submittedJobIds.add(resultJobId);
                }
            }

            if (submittedJobIds.size() > 1) {
                for (String submittedJobId : submittedJobIds) {
                    try {
                        cancelJob(submittedJobId);
                    } catch (Exception cleanupException) {
                        log.error("Failed to cancel unexpected extra Flink job {}: {}",
                                submittedJobId, cleanupException.getMessage());
                    }
                }
                throw new IllegalStateException("单个平台任务意外创建了多个 Flink Job，已尝试全部取消: "
                        + String.join(", ", submittedJobIds));
            }

            String jobId = submittedJobIds.stream().findFirst().orElse(null);
            if (jobId == null) {
                jobId = waitForNewClusterJob(jobsBeforeSubmission, submissionJobName);
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
                    gatewayClient.close(boundGateway, sessionHandle);
                    log.debug("SQL Gateway session {} cleaned up", sessionHandle);
                } catch (Exception cleanupEx) {
                    log.warn("Failed to clean up SQL Gateway session {}: {}",
                            sessionHandle, cleanupEx.getMessage());
                }
            }
        }
    }

    private JsonNode waitForSqlGatewayOperation(String boundGateway, String sessionHandle, String operationHandle) {
        for (int attempt = 0; attempt < 60; attempt++) {
            try {
                JsonNode statusJson = new SqlGatewayClient(restTemplate, objectMapper).status(boundGateway, sessionHandle, operationHandle);
                {
                    String status = statusJson.path("status").asText("");
                    if ("FINISHED".equalsIgnoreCase(status)) {
                        return fetchSqlGatewayOperationResult(boundGateway, sessionHandle, operationHandle);
                    }
                    if (Set.of("ERROR", "FAILED", "CANCELED", "CLOSED").contains(status.toUpperCase())) {
                        JsonNode errorResult = fetchSqlGatewayOperationResult(boundGateway, sessionHandle, operationHandle);
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

    private JsonNode fetchSqlGatewayOperationResult(String boundGateway, String sessionHandle, String operationHandle) {
        try {
            return new SqlGatewayClient(restTemplate, objectMapper).result(boundGateway, sessionHandle, operationHandle, 0);
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

    private Map<String, String> buildSqlGatewayExecutionConfig(
            SyncTask task,
            String restorePath,
            String submissionJobName
    ) {
        Map<String, String> config = new LinkedHashMap<>();
        config.put("pipeline.name", submissionJobName);
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
        boolean blockComment = false;

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
            if (blockComment) {
                if (currentChar == '*' && nextChar == '/') {
                    blockComment = false;
                    current.append(' ');
                    index++;
                }
                continue;
            }
            if (!singleQuoted && !doubleQuoted && !backtickQuoted
                    && currentChar == '-' && nextChar == '-') {
                lineComment = true;
                index++;
                continue;
            }
            if (!singleQuoted && !doubleQuoted && !backtickQuoted
                    && currentChar == '/' && nextChar == '*') {
                blockComment = true;
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
                boolean statementSet = statement.toUpperCase(Locale.ROOT)
                        .startsWith("EXECUTE STATEMENT SET");
                int previousSeparator = statement.lastIndexOf(';');
                String statementSetTail = previousSeparator < 0
                        ? statement
                        : statement.substring(previousSeparator + 1).trim();
                // Only the standalone END segment closes a Statement Set.
                // An INSERT may itself end in CASE ... END; and must not split
                // the remaining INSERT statements into separate Flink Jobs.
                boolean statementSetEnd = "END".equalsIgnoreCase(statementSetTail);
                if (statementSet && !statementSetEnd) {
                    // Semicolons inside BEGIN ... END separate INSERT statements;
                    // the whole Statement Set must be sent to Gateway in one request.
                    current.append(currentChar);
                } else {
                    if (!statement.isEmpty()) {
                        statements.add(statement);
                    }
                    current.setLength(0);
                }
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

    static boolean createsMultipleJobs(List<String> statements) {
        long jobStatements = statements.stream()
                .map(FlinkClusterService::leadingSqlKeyword)
                .filter(keyword -> keyword.startsWith("INSERT")
                        || keyword.startsWith("EXECUTE STATEMENT SET")
                        || keyword.startsWith("EXECUTE PLAN")
                        || keyword.startsWith("CREATE MATERIALIZED TABLE")
                        || keyword.startsWith("ALTER MATERIALIZED TABLE"))
                .count();
        return jobStatements > 1;
    }

    private static String leadingSqlKeyword(String statement) {
        if (statement == null) return "";
        return statement.stripLeading().toUpperCase(Locale.ROOT);
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

    private String waitForNewClusterJob(Set<String> existingJobIds, String expectedName) {
        for (int attempt = 0; attempt < 15; attempt++) {
            try {
                ResponseEntity<String> response = restTemplate.getForEntity(
                        flinkRestUrl + "/jobs/overview", String.class);
                if (response.getBody() != null) {
                    JsonNode jobs = objectMapper.readTree(response.getBody()).path("jobs");
                    String exactJobId = findUniqueNewJobId(jobs, existingJobIds, expectedName);
                    if (exactJobId != null) return exactJobId;
                }
            } catch (IllegalStateException exception) {
                throw exception;
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

    static String findUniqueNewJobId(JsonNode jobs, Set<String> existingJobIds, String expectedName) {
        if (jobs == null || !jobs.isArray()) return null;
        String match = null;
        for (JsonNode job : jobs) {
            String id = job.path("jid").asText("");
            if (id.isBlank() || existingJobIds.contains(id)
                    || !expectedName.equals(job.path("name").asText())) {
                continue;
            }
            if (match != null && !match.equals(id)) {
                throw new IllegalStateException(
                        "SQL Gateway 提交后出现多个同名 Flink Job，无法安全关联任务");
            }
            match = id;
        }
        return match;
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
        return triggerSavepointRequest(flinkJobId, true);
    }

    private Map<String, Object> triggerSavepointRequest(String jobId, boolean stop) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(stop ? "targetDirectory" : "target-directory", normalizeStorageUri(savepointDir));
        if (stop) payload.put("drain", false); else payload.put("cancel-job", false);
        HttpHeaders headers = new HttpHeaders(); headers.setContentType(MediaType.APPLICATION_JSON);
        // One request only: a timeout may have been accepted by the engine.
        ResponseEntity<String> response = restTemplate.postForEntity(
                flinkRestUrl + "/jobs/" + jobId + (stop ? "/stop" : "/savepoints"), new HttpEntity<>(payload, headers), String.class);
        try {
            JsonNode body = objectMapper.readTree(response.getBody());
            String triggerId = firstNonBlankText(body, "request-id", "triggerId");
            if (triggerId == null || triggerId.isBlank()) throw new IllegalStateException("Flink 未返回保存点请求标识，结果需要核对");
            return Map.of("triggerId", triggerId, "jobId", jobId, "status", "PENDING");
        } catch (java.io.IOException invalid) { throw new IllegalStateException("保存点响应无法解析，结果需要核对", invalid); }
    }

    /**
     * Trigger a manual savepoint (without stopping the job).
     * POST /jobs/{jobId}/savepoints
     */
    public Map<String, Object> triggerSavepoint(String flinkJobId) {
        return triggerSavepointRequest(flinkJobId, false);
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

                JsonNode operation = json.path("operation");
                String savepointPath = firstNonBlankText(operation, "location", "savepointLocation");
                if (savepointPath == null) savepointPath = json.path("location").asText("");
                JsonNode failure = operation.path("failure-cause");
                if (failure.isMissingNode() || failure.isNull()) failure = json.path("failureCause");
                boolean failed = !failure.isMissingNode() && !failure.isNull() && (failure.isContainerNode() || !failure.asText().isBlank());
                if (failed) status = "FAILED";
                else if ("COMPLETED".equals(status) && savepointPath.isBlank()) status = "UNKNOWN";
                return Map.of("triggerId", triggerId, "status", status, "savepointPath", savepointPath,
                        "failureCause", failed ? "Flink 保存点执行失败，请查看引擎诊断" : "");
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
            if (e instanceof RestClientResponseException responseException
                    && responseException.getStatusCode() == HttpStatus.NOT_FOUND) {
                log.info("Flink job no longer exists on cluster: {}", flinkJobId);
                return Map.of(
                        "status", "NOT_FOUND",
                        "flinkState", "NOT_FOUND",
                        "lagMs", 0L,
                        "throughputQps", 0.0
                );
            }
            log.warn("Failed to get Flink job status for {}: {}", flinkJobId, e.getMessage());
            return Map.of("status", "UNREACHABLE", "lagMs", 0L, "throughputQps", 0.0);
        }
    }

    /**
     * Map Flink's own job state to our internal status
     */
    private String mapFlinkState(String flinkState) {
        switch (flinkState.toUpperCase(Locale.ROOT)) {
            case "RUNNING": return "running";
            case "CANCELED": return "finished";
            case "FAILED": return "failed";
            case "FINISHED": return "finished";
            case "CREATED": return "submitting";
            case "RESTARTING": return "running";
            case "SUSPENDED": return "paused";
            case "FAILING": return "failed";
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
    // 7. Elastic Scaling
    // ========================================================================

    /**
     * Return a fresh capacity snapshot. Flink REST can expose capacity and update
     * adaptive job requirements, but only the resource provider can create or
     * remove TaskManagers. The provider flag keeps those two capabilities clear.
     */
    public Map<String, Object> getClusterCapacity() {
        Map<String, Object> health = healthCheck();
        String status = String.valueOf(health.getOrDefault("status", "unreachable"));
        int slotsTotal = intValue(health.get("taskSlotsTotal"));
        int slotsAvailable = intValue(health.get("taskSlotsAvailable"));
        String provider = normalizeScalingProvider(scalingProvider);
        Map<String, String> configuration = readFlinkConfiguration(null);
        boolean adaptiveScheduler = isAdaptiveScheduler(configuration);
        boolean autoExpansionSupported = adaptiveScheduler && "kubernetes-native".equals(provider);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", status);
        result.put("provider", provider);
        result.put("autoExpansionSupported", autoExpansionSupported);
        result.put("jobRescalingSupported", adaptiveScheduler && "healthy".equals(status));
        result.put("adaptiveScheduler", adaptiveScheduler);
        result.put("currentTaskManagers", intValue(health.get("taskManagers")));
        result.put("slotsTotal", slotsTotal);
        result.put("slotsAvailable", slotsAvailable);
        result.put("slotsUsed", Math.max(0, slotsTotal - slotsAvailable));
        result.put("slotUtilization", slotsTotal == 0
                ? 0.0
                : Math.round((slotsTotal - slotsAvailable) * 1000.0 / slotsTotal) / 10.0);
        result.put("runningJobs", intValue(health.get("runningJobs")));
        result.put("observedAt", Instant.now().toString());

        if (!"healthy".equals(status)) {
            result.put("reason", String.valueOf(health.getOrDefault("error", "Flink 集群当前不可访问")));
        } else if (!adaptiveScheduler) {
            result.put("reason", "集群未启用 jobmanager.scheduler=adaptive，仅支持容量观测");
        } else if (!autoExpansionSupported) {
            result.put("reason", "已支持作业在线并行度调整；Standalone 集群的 TaskManager 仍需由部署工具扩缩");
        } else {
            result.put("reason", "Native Kubernetes 会根据作业资源需求自动申请或释放 TaskManager");
        }
        return result;
    }

    /** Read current per-vertex requirements and elastic-scaling capability. */
    public Map<String, Object> getJobScalingInfo(String flinkJobId) {
        if (flinkJobId == null || flinkJobId.isBlank()) {
            throw new IllegalArgumentException("Flink Job ID 不能为空");
        }

        try {
            JsonNode job = getJson(flinkRestUrl + "/jobs/" + flinkJobId);
            Map<String, String> jobConfiguration = readFlinkConfiguration(flinkJobId);
            boolean adaptiveScheduler = isAdaptiveScheduler(jobConfiguration);
            String flinkState = job.path("state").asText("UNKNOWN");
            String jobType = job.path("job-type").asText("UNKNOWN");
            boolean running = "RUNNING".equalsIgnoreCase(flinkState);
            boolean streaming = "STREAMING".equalsIgnoreCase(jobType);

            Map<String, JsonNode> jobVertices = new LinkedHashMap<>();
            JsonNode verticesNode = job.path("vertices");
            if (verticesNode.isArray()) {
                for (JsonNode vertex : verticesNode) {
                    String vertexId = vertex.path("id").asText("");
                    if (!vertexId.isBlank()) jobVertices.put(vertexId, vertex);
                }
            }

            // Some schedulers reject this endpoint instead of returning an
            // empty object. Check capability first so a healthy, non-adaptive
            // job is reported as unsupported rather than as a failed request.
            JsonNode requirements = objectMapper.createObjectNode();
            String requirementsError = null;
            if (adaptiveScheduler && running && streaming) {
                try {
                    requirements = getJson(
                            flinkRestUrl + "/jobs/" + flinkJobId + "/resource-requirements");
                } catch (RestClientResponseException exception) {
                    requirementsError = flinkError(exception);
                } catch (Exception exception) {
                    requirementsError = exception.getMessage();
                }
            }

            List<Map<String, Object>> vertices = new ArrayList<>();
            int currentMin = Integer.MAX_VALUE;
            int currentMax = 0;
            int requestedLower = Integer.MAX_VALUE;
            int requestedUpper = 0;
            int minTarget = Math.max(1, scalingMinParallelism);
            int maxTarget = Math.max(1, scalingMaxParallelism);
            boolean hasRequirements = false;

            Set<String> vertexIds = new LinkedHashSet<>(jobVertices.keySet());
            requirements.fieldNames().forEachRemaining(vertexIds::add);
            for (String vertexId : vertexIds) {
                JsonNode parallelism = requirements.path(vertexId).path("parallelism");
                boolean hasBounds = parallelism.isObject()
                        && parallelism.has("lowerBound")
                        && parallelism.has("upperBound");
                int lowerBound = hasBounds
                        ? parallelism.path("lowerBound").asInt(scalingMinParallelism)
                        : scalingMinParallelism;
                int upperBound = hasBounds
                        ? parallelism.path("upperBound").asInt(lowerBound)
                        : lowerBound;
                JsonNode vertex = jobVertices.get(vertexId);
                int currentParallelism = vertex == null
                        ? lowerBound
                        : vertex.path("parallelism").asInt(lowerBound);
                int vertexMax = vertex == null
                        ? scalingMaxParallelism
                        : vertex.path("maxParallelism").asInt(scalingMaxParallelism);
                if (vertexMax > 0) maxTarget = Math.min(maxTarget, vertexMax);

                Map<String, Object> detail = new LinkedHashMap<>();
                detail.put("id", vertexId);
                detail.put("name", vertex == null ? vertexId : vertex.path("name").asText(vertexId));
                detail.put("currentParallelism", currentParallelism);
                detail.put("lowerBound", hasBounds ? lowerBound : null);
                detail.put("upperBound", hasBounds ? upperBound : null);
                detail.put("maxParallelism", vertexMax);
                vertices.add(detail);

                currentMin = Math.min(currentMin, currentParallelism);
                currentMax = Math.max(currentMax, currentParallelism);
                if (hasBounds) {
                    hasRequirements = true;
                    requestedLower = Math.min(requestedLower, lowerBound);
                    requestedUpper = Math.max(requestedUpper, upperBound);
                }
            }

            boolean hasCurrentParallelism = currentMin != Integer.MAX_VALUE;
            boolean targetRangeValid = maxTarget >= minTarget;
            boolean supported = adaptiveScheduler && running && streaming
                    && requirementsError == null && hasRequirements && targetRangeValid;
            String provider = normalizeScalingProvider(scalingProvider);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("supported", supported);
            result.put("jobId", flinkJobId);
            result.put("flinkState", flinkState);
            result.put("jobType", jobType);
            result.put("adaptiveScheduler", adaptiveScheduler);
            result.put("provider", provider);
            result.put("autoExpansionSupported", adaptiveScheduler && "kubernetes-native".equals(provider));
            result.put("currentParallelism", hasCurrentParallelism && currentMin == currentMax ? currentMin : null);
            result.put("currentParallelismMin", hasCurrentParallelism ? currentMin : null);
            result.put("currentParallelismMax", hasCurrentParallelism ? currentMax : null);
            result.put("requestedLowerBound", hasRequirements ? requestedLower : null);
            result.put("requestedUpperBound", hasRequirements ? requestedUpper : null);
            result.put("minTargetParallelism", minTarget);
            result.put("maxTargetParallelism", maxTarget);
            result.put("vertices", vertices);
            result.put("capacity", getClusterCapacity());
            result.put("observedAt", Instant.now().toString());

            if (!adaptiveScheduler) {
                result.put("reason", "该作业未使用 Adaptive Scheduler，不能在线调整资源需求");
            } else if (!streaming) {
                result.put("reason", "仅 Streaming 作业支持在线弹性伸缩");
            } else if (!running) {
                result.put("reason", "仅 RUNNING 状态的作业可以调整并行度");
            } else if (requirementsError != null) {
                result.put("reason", "Flink 资源需求接口不可用: " + requirementsError);
            } else if (!hasRequirements) {
                result.put("reason", "Flink 未返回可调整的 JobVertex 资源需求");
            } else if (!targetRangeValid) {
                result.put("reason", "平台最小并行度 " + minTarget
                        + " 超过作业允许的最大并行度 " + maxTarget + "，请调整伸缩配置");
            } else if (!"kubernetes-native".equals(provider)) {
                result.put("reason", "可以在线调整作业并行度；扩容前请确认 Standalone 集群已有足够 Slot");
            }
            return result;
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode() == HttpStatus.NOT_FOUND) {
                throw new IllegalStateException("Flink 集群中不存在该 Job", exception);
            }
            throw new IllegalStateException("读取 Flink 弹性伸缩信息失败: " + flinkError(exception), exception);
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("读取 Flink 弹性伸缩信息失败: " + exception.getMessage(), exception);
        }
    }

    /**
     * Re-declare every JobVertex parallelism bound. With Adaptive Scheduler this
     * is applied asynchronously and, on Native Kubernetes, drives TM allocation.
     */
    public Map<String, Object> rescaleJob(String flinkJobId, int targetParallelism) {
        Map<String, Object> scalingInfo = getJobScalingInfo(flinkJobId);
        if (!Boolean.TRUE.equals(scalingInfo.get("supported"))) {
            throw new IllegalStateException(String.valueOf(
                    scalingInfo.getOrDefault("reason", "当前作业不支持在线调整并行度")));
        }

        int minTarget = intValue(scalingInfo.get("minTargetParallelism"));
        int maxTarget = intValue(scalingInfo.get("maxTargetParallelism"));
        if (targetParallelism < minTarget || targetParallelism > maxTarget) {
            throw new IllegalArgumentException(
                    "目标并行度必须在 " + minTarget + " 到 " + maxTarget + " 之间");
        }

        try {
            String endpoint = flinkRestUrl + "/jobs/" + flinkJobId + "/resource-requirements";
            JsonNode current = getJson(endpoint);
            JsonNode payload = applyTargetParallelism(current, targetParallelism);
            if (!payload.fields().hasNext()) {
                throw new IllegalStateException("Flink 未返回可调整的 JobVertex 资源需求");
            }

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            ResponseEntity<String> response = restTemplate.exchange(
                    endpoint,
                    HttpMethod.PUT,
                    new HttpEntity<>(payload.toString(), headers),
                    String.class
            );
            if (!response.getStatusCode().is2xxSuccessful()) {
                throw new IllegalStateException("Flink 返回 HTTP " + response.getStatusCode().value());
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("accepted", true);
            result.put("jobId", flinkJobId);
            result.put("targetParallelism", targetParallelism);
            result.put("affectedVertices", current.size());
            result.put("provider", normalizeScalingProvider(scalingProvider));
            result.put("autoExpansionSupported", scalingInfo.get("autoExpansionSupported"));
            result.put("acceptedAt", Instant.now().toString());
            result.put("message", "Flink 已接受资源需求，Adaptive Scheduler 将异步完成重调度");
            return result;
        } catch (RestClientResponseException exception) {
            throw new IllegalStateException("Flink 拒绝并行度调整: " + flinkError(exception), exception);
        } catch (IllegalStateException | IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("提交 Flink 并行度调整失败: " + exception.getMessage(), exception);
        }
    }

    static JsonNode applyTargetParallelism(JsonNode current, int targetParallelism) {
        if (targetParallelism < 1) throw new IllegalArgumentException("目标并行度不能小于 1");
        if (current == null || !current.isObject()) {
            throw new IllegalArgumentException("JobVertex 资源需求必须是 JSON 对象");
        }
        ObjectNode updated = ((ObjectNode) current).deepCopy();
        Iterator<Map.Entry<String, JsonNode>> fields = updated.fields();
        while (fields.hasNext()) {
            JsonNode requirement = fields.next().getValue();
            if (!(requirement instanceof ObjectNode requirementObject)) continue;
            JsonNode parallelismNode = requirementObject.get("parallelism");
            ObjectNode parallelism = parallelismNode instanceof ObjectNode objectNode
                    ? objectNode
                    : requirementObject.putObject("parallelism");
            parallelism.put("lowerBound", targetParallelism);
            parallelism.put("upperBound", targetParallelism);
        }
        return updated;
    }

    static boolean isAdaptiveScheduler(Map<String, String> configuration) {
        String scheduler = configuration.getOrDefault("jobmanager.scheduler", "");
        // Reactive Mode derives parallelism from all currently available slots;
        // it does not provide the fixed-target semantics exposed by this API.
        return "adaptive".equalsIgnoreCase(scheduler);
    }

    private Map<String, String> readFlinkConfiguration(String flinkJobId) {
        String endpoint = flinkJobId == null || flinkJobId.isBlank()
                ? flinkRestUrl + "/jobmanager/config"
                : flinkRestUrl + "/jobs/" + flinkJobId + "/jobmanager/config";
        try {
            JsonNode entries = getJson(endpoint);
            Map<String, String> configuration = new LinkedHashMap<>();
            if (entries.isArray()) {
                for (JsonNode entry : entries) {
                    String key = entry.path("key").asText("");
                    if (!key.isBlank()) configuration.put(key, entry.path("value").asText(""));
                }
            }
            return configuration;
        } catch (Exception exception) {
            log.debug("Unable to read Flink configuration from {}: {}", endpoint, exception.getMessage());
            return Map.of();
        }
    }

    private JsonNode getJson(String endpoint) throws Exception {
        ResponseEntity<String> response = restTemplate.getForEntity(endpoint, String.class);
        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            throw new IllegalStateException("Flink 返回 HTTP " + response.getStatusCode().value());
        }
        return objectMapper.readTree(response.getBody());
    }

    private String normalizeScalingProvider(String provider) {
        String value = provider == null ? "" : provider.trim().toLowerCase(Locale.ROOT);
        if (Set.of("kubernetes", "kubernetes-native", "native-kubernetes").contains(value)) {
            return "kubernetes-native";
        }
        if ("standalone".equals(value) || "docker-compose".equals(value)) return "standalone";
        return "external";
    }

    private int intValue(Object value) {
        if (value instanceof Number number) return number.intValue();
        if (value == null) return 0;
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private String flinkError(RestClientResponseException exception) {
        String body = exception.getResponseBodyAsString();
        if (body == null || body.isBlank()) return "HTTP " + exception.getStatusCode().value();
        try {
            JsonNode json = objectMapper.readTree(body);
            JsonNode errors = json.path("errors");
            if (errors.isArray() && !errors.isEmpty()) {
                return errors.get(errors.size() - 1).asText();
            }
        } catch (Exception ignored) {
            // Keep the concise raw response below.
        }
        String concise = body.replaceAll("\\s+", " ").trim();
        return concise.length() > 500 ? concise.substring(0, 500) + "..." : concise;
    }

    // ========================================================================
    // 8. Health Check
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
    // 9. Jar Management
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
