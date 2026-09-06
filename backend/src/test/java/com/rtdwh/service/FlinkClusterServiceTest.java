package com.rtdwh.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

class FlinkClusterServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void splitsGeneratedSqlWithoutBreakingQuotedSemicolons() {
        String script = "-- task comment\n"
                + "CREATE TABLE `source_t` (`value` STRING) WITH ('note'='a;b');\n"
                + "CREATE TABLE `sink_t` (`value` STRING);\n"
                + "INSERT INTO `sink_t` SELECT * FROM `source_t`;";

        List<String> statements = FlinkClusterService.splitSqlStatements(script);

        assertEquals(3, statements.size());
        assertEquals(true, statements.get(0).startsWith("CREATE TABLE"));
        assertEquals(true, statements.get(2).startsWith("INSERT INTO"));
    }

    @Test
    void keepsStatementSetAsOneGatewayStatement() {
        String script = """
                CREATE TABLE source_a (id BIGINT);
                EXECUTE STATEMENT SET
                BEGIN
                  INSERT INTO sink_a SELECT * FROM source_a;
                  INSERT INTO sink_b SELECT * FROM source_b;
                END;
                """;

        List<String> statements = FlinkClusterService.splitSqlStatements(script);

        assertEquals(2, statements.size());
        assertEquals(true, statements.get(1).startsWith("EXECUTE STATEMENT SET"));
        assertEquals(true, statements.get(1).contains("INSERT INTO sink_b"));
        assertEquals(true, statements.get(1).endsWith("END"));
    }

    @Test
    void keepsStatementSetTogetherWhenInsertEndsWithCaseExpression() {
        String script = """
                EXECUTE STATEMENT SET
                BEGIN
                  INSERT INTO sink_a SELECT CASE WHEN id = 1 THEN 'x' ELSE 'y' END FROM source_a;
                  INSERT INTO sink_b SELECT * FROM source_b;
                END;
                """;

        List<String> statements = FlinkClusterService.splitSqlStatements(script);

        assertEquals(1, statements.size());
        assertEquals(true, statements.get(0).contains("CASE WHEN id = 1"));
        assertEquals(true, statements.get(0).contains("INSERT INTO sink_b"));
        assertEquals(true, statements.get(0).endsWith("END"));
    }

    @Test
    void detectsOnlyIndependentMultiInsertSubmissionsAsMultipleJobs() {
        assertEquals(true, FlinkClusterService.createsMultipleJobs(List.of(
                "CREATE TABLE source_a (id BIGINT)",
                "INSERT INTO sink_a SELECT * FROM source_a",
                "INSERT OVERWRITE sink_b SELECT * FROM source_b"
        )));
        assertEquals(false, FlinkClusterService.createsMultipleJobs(List.of(
                "CREATE TABLE source_a (id BIGINT)",
                "EXECUTE STATEMENT SET BEGIN INSERT INTO sink_a SELECT * FROM source_a; END"
        )));
        assertEquals(true, FlinkClusterService.createsMultipleJobs(List.of(
                "CREATE MATERIALIZED TABLE mt_a AS SELECT * FROM source_a",
                "CREATE MATERIALIZED TABLE mt_b AS SELECT * FROM source_b"
        )));
    }

    @Test
    void stripsBlockCommentsBeforeDetectingIndependentInserts() {
        List<String> statements = FlinkClusterService.splitSqlStatements("""
                /* setup; comment */ INSERT INTO sink_a SELECT * FROM source_a;
                /* second sink */ INSERT INTO sink_b SELECT * FROM source_b;
                """);

        assertEquals(2, statements.size());
        assertEquals(true, FlinkClusterService.createsMultipleJobs(statements));
    }

    @Test
    void appliesTargetParallelismWithoutChangingOriginalRequirements() throws Exception {
        var original = objectMapper.readTree("""
                {
                  "vertex-a":{"parallelism":{"lowerBound":1,"upperBound":4}},
                  "vertex-b":{"parallelism":{"lowerBound":2,"upperBound":8}}
                }
                """);

        var updated = FlinkClusterService.applyTargetParallelism(original, 3);

        assertEquals(3, updated.path("vertex-a").path("parallelism").path("lowerBound").asInt());
        assertEquals(3, updated.path("vertex-b").path("parallelism").path("upperBound").asInt());
        assertEquals(1, original.path("vertex-a").path("parallelism").path("lowerBound").asInt());
    }

    @Test
    void enablesFixedTargetScalingOnlyForAdaptiveScheduler() {
        assertEquals(true, FlinkClusterService.isAdaptiveScheduler(
                Map.of("jobmanager.scheduler", "adaptive")));
        assertEquals(false, FlinkClusterService.isAdaptiveScheduler(
                Map.of("scheduler-mode", "reactive")));
        assertEquals(false, FlinkClusterService.isAdaptiveScheduler(
                Map.of("jobmanager.scheduler", "default")));
    }

    @Test
    void extractsJobIdFromSqlGatewayResultRows() throws Exception {
        String jobId = "0123456789abcdef0123456789abcdef";
        var result = objectMapper.readTree("""
                {
                  "results": {
                    "data": [
                      {"kind": "INSERT", "fields": ["%s"]}
                    ]
                  }
                }
                """.formatted(jobId));

        assertEquals(jobId, FlinkClusterService.extractFlinkJobId(result));
    }

    @Test
    void resolvesOnlyTheUniqueNewJobWithTheSubmissionName() throws Exception {
        var jobs = objectMapper.readTree("""
                [
                  {"jid":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","name":"unrelated"},
                  {"jid":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","name":"orders [rtdwh-7-token]"},
                  {"jid":"cccccccccccccccccccccccccccccccc","name":"orders [rtdwh-7-token]"}
                ]
                """);

        assertEquals(
                "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                FlinkClusterService.findUniqueNewJobId(
                        jobs,
                        Set.of("cccccccccccccccccccccccccccccccc"),
                        "orders [rtdwh-7-token]"));
        assertNull(FlinkClusterService.findUniqueNewJobId(
                jobs, Set.of(), "missing submission"));
        assertThrows(IllegalStateException.class, () -> FlinkClusterService.findUniqueNewJobId(
                jobs, Set.of(), "orders [rtdwh-7-token]"));
    }

    @Test
    void extractsRootCauseInsteadOfGenericGatewayError() throws Exception {
        var result = objectMapper.readTree("""
                {"errors":["Internal server error.",
                  "wrapper\\nCaused by: java.lang.NoClassDefFoundError: org/apache/hadoop/conf/Configuration"]}
                """);

        assertEquals(
                "Caused by: java.lang.NoClassDefFoundError: org/apache/hadoop/conf/Configuration",
                FlinkClusterService.extractSqlGatewayError(result));
    }

    @Test
    void addsFileSchemeToLocalStoragePath() {
        assertEquals(
                "file:///tmp/flink-savepoints",
                FlinkClusterService.normalizeStorageUri("/tmp/flink-savepoints"));
        assertEquals(
                "hdfs://namenode:8020/flink/savepoints",
                FlinkClusterService.normalizeStorageUri("hdfs://namenode:8020/flink/savepoints/"));
    }

    @Test
    void reportsMissingJobSeparatelyFromTemporaryClusterFailure() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        FlinkClusterService service = new FlinkClusterService(restTemplate, objectMapper);
        ReflectionTestUtils.setField(service, "flinkRestUrl", "http://localhost:8081");
        server.expect(requestTo("http://localhost:8081/jobs/missing-job"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        var result = service.getJobStatus("missing-job");

        assertEquals("NOT_FOUND", result.get("status"));
        assertEquals("NOT_FOUND", result.get("flinkState"));
        server.verify();
    }

    @Test
    void reportsNonAdaptiveJobAsUnsupportedWithoutCallingRequirementsEndpoint() {
        String jobId = "0123456789abcdef0123456789abcdef";
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        FlinkClusterService service = new FlinkClusterService(restTemplate, objectMapper);
        ReflectionTestUtils.setField(service, "flinkRestUrl", "http://localhost:8081");
        ReflectionTestUtils.setField(service, "scalingProvider", "standalone");
        ReflectionTestUtils.setField(service, "scalingMinParallelism", 1);
        ReflectionTestUtils.setField(service, "scalingMaxParallelism", 64);

        server.expect(requestTo("http://localhost:8081/jobs/" + jobId))
                .andRespond(withSuccess("""
                        {"state":"RUNNING","job-type":"STREAMING","vertices":[
                          {"id":"vertex-a","name":"Source -> Sink","parallelism":2,"maxParallelism":32}
                        ]}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://localhost:8081/jobs/" + jobId + "/jobmanager/config"))
                .andRespond(withSuccess("""
                        [{"key":"jobmanager.scheduler","value":"default"}]
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://localhost:8081/overview"))
                .andRespond(withSuccess("""
                        {"flink-version":"2.2.1","taskmanagers":1,"slots-total":8,
                         "slots-available":6,"jobs-running":1,"jobs-finished":0,
                         "jobs-failed":0,"jobs-cancelled":0}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://localhost:8081/jobmanager/config"))
                .andRespond(withSuccess("""
                        [{"key":"jobmanager.scheduler","value":"default"}]
                        """, MediaType.APPLICATION_JSON));

        Map<String, Object> result = service.getJobScalingInfo(jobId);

        assertEquals(false, result.get("supported"));
        assertEquals(2, result.get("currentParallelism"));
        assertEquals("该作业未使用 Adaptive Scheduler，不能在线调整资源需求", result.get("reason"));
        server.verify();
    }

    @Test
    void rejectsConfiguredMinimumAboveVertexMaxParallelism() {
        String jobId = "0123456789abcdef0123456789abcdef";
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        FlinkClusterService service = new FlinkClusterService(restTemplate, objectMapper);
        ReflectionTestUtils.setField(service, "flinkRestUrl", "http://localhost:8081");
        ReflectionTestUtils.setField(service, "scalingProvider", "standalone");
        ReflectionTestUtils.setField(service, "scalingMinParallelism", 64);
        ReflectionTestUtils.setField(service, "scalingMaxParallelism", 128);

        server.expect(requestTo("http://localhost:8081/jobs/" + jobId))
                .andRespond(withSuccess("""
                        {"state":"RUNNING","job-type":"STREAMING","vertices":[
                          {"id":"vertex-a","name":"Source -> Sink","parallelism":2,"maxParallelism":32}
                        ]}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://localhost:8081/jobs/" + jobId + "/jobmanager/config"))
                .andRespond(withSuccess("""
                        [{"key":"jobmanager.scheduler","value":"adaptive"}]
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://localhost:8081/jobs/" + jobId + "/resource-requirements"))
                .andRespond(withSuccess("""
                        {"vertex-a":{"parallelism":{"lowerBound":1,"upperBound":4}}}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://localhost:8081/overview"))
                .andRespond(withSuccess("""
                        {"flink-version":"2.2.1","taskmanagers":1,"slots-total":8,
                         "slots-available":6,"jobs-running":1,"jobs-finished":0,
                         "jobs-failed":0,"jobs-cancelled":0}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://localhost:8081/jobmanager/config"))
                .andRespond(withSuccess("""
                        [{"key":"jobmanager.scheduler","value":"adaptive"}]
                        """, MediaType.APPLICATION_JSON));

        Map<String, Object> result = service.getJobScalingInfo(jobId);

        assertEquals(false, result.get("supported"));
        assertEquals(64, result.get("minTargetParallelism"));
        assertEquals(32, result.get("maxTargetParallelism"));
        assertEquals(
                "平台最小并行度 64 超过作业允许的最大并行度 32，请调整伸缩配置",
                result.get("reason"));
        server.verify();
    }

    @Test
    void submitsAdaptiveResourceRequirementsWithExactVertexPayload() {
        String jobId = "0123456789abcdef0123456789abcdef";
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        FlinkClusterService service = new FlinkClusterService(restTemplate, objectMapper);
        ReflectionTestUtils.setField(service, "flinkRestUrl", "http://localhost:8081");
        ReflectionTestUtils.setField(service, "scalingProvider", "standalone");
        ReflectionTestUtils.setField(service, "scalingMinParallelism", 1);
        ReflectionTestUtils.setField(service, "scalingMaxParallelism", 64);

        server.expect(requestTo("http://localhost:8081/jobs/" + jobId))
                .andRespond(withSuccess("""
                        {"state":"RUNNING","job-type":"STREAMING","vertices":[
                          {"id":"vertex-a","name":"Source -> Sink","parallelism":1,"maxParallelism":32}
                        ]}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://localhost:8081/jobs/" + jobId + "/jobmanager/config"))
                .andRespond(withSuccess("""
                        [{"key":"jobmanager.scheduler","value":"adaptive"}]
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://localhost:8081/jobs/" + jobId + "/resource-requirements"))
                .andRespond(withSuccess("""
                        {"vertex-a":{"parallelism":{"lowerBound":1,"upperBound":1}}}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://localhost:8081/overview"))
                .andRespond(withSuccess("""
                        {"flink-version":"2.2.1","taskmanagers":1,"slots-total":8,
                         "slots-available":7,"jobs-running":1,"jobs-finished":0,
                         "jobs-failed":0,"jobs-cancelled":0}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://localhost:8081/jobmanager/config"))
                .andRespond(withSuccess("""
                        [{"key":"jobmanager.scheduler","value":"adaptive"}]
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://localhost:8081/jobs/" + jobId + "/resource-requirements"))
                .andRespond(withSuccess("""
                        {"vertex-a":{"parallelism":{"lowerBound":1,"upperBound":1}}}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://localhost:8081/jobs/" + jobId + "/resource-requirements"))
                .andExpect(method(HttpMethod.PUT))
                .andExpect(content().json("""
                        {"vertex-a":{"parallelism":{"lowerBound":4,"upperBound":4}}}
                        """))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        var result = service.rescaleJob(jobId, 4);

        assertEquals(true, result.get("accepted"));
        assertEquals(4, result.get("targetParallelism"));
        assertEquals(false, result.get("autoExpansionSupported"));
        server.verify();
    }
    @Test void parsesFlink22SavepointRequestAndResult() {
        RestTemplate rest = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(rest).build();
        FlinkClusterService service = new FlinkClusterService(rest, objectMapper);
        ReflectionTestUtils.setField(service, "flinkRestUrl", "http://flink");
        ReflectionTestUtils.setField(service, "savepointDir", "file:///state");
        server.expect(requestTo("http://flink/jobs/job/stop")).andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"request-id\":\"request1\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://flink/jobs/job/savepoints/request1"))
                .andRespond(withSuccess("{\"status\":{\"id\":\"COMPLETED\"},\"operation\":{\"location\":\"file:///state/savepoint-1\"}}", MediaType.APPLICATION_JSON));
        assertEquals("request1", service.triggerStopWithSavepoint("job").get("triggerId"));
        assertEquals("file:///state/savepoint-1", service.pollSavepointStatus("job", "request1").get("savepointPath"));
        server.verify();
    }

    @Test void completedSavepointQueueWithFailureIsNotSuccess() {
        RestTemplate rest = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(rest).build();
        FlinkClusterService service = new FlinkClusterService(rest, objectMapper);
        ReflectionTestUtils.setField(service, "flinkRestUrl", "http://flink");
        server.expect(requestTo("http://flink/jobs/job/savepoints/request1"))
                .andRespond(withSuccess("{\"status\":{\"id\":\"COMPLETED\"},\"operation\":{\"failure-cause\":{\"class\":\"IOException\"}}}", MediaType.APPLICATION_JSON));
        assertEquals("FAILED", service.pollSavepointStatus("job", "request1").get("status")); server.verify();
    }

    @Test void ambiguousStopResponseDoesNotSubmitFallbackSavepoint() {
        RestTemplate rest = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(rest).build();
        FlinkClusterService service = new FlinkClusterService(rest, objectMapper);
        ReflectionTestUtils.setField(service, "flinkRestUrl", "http://flink");
        ReflectionTestUtils.setField(service, "savepointDir", "file:///state");
        server.expect(requestTo("http://flink/jobs/job/stop")).andRespond(withStatus(HttpStatus.GATEWAY_TIMEOUT));
        assertThrows(RuntimeException.class, () -> service.triggerStopWithSavepoint("job")); server.verify();
    }
}
