package com.rtdwh.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.*;
import java.net.URI;
import java.util.Map;

/** Stateless transport. Every call requires an explicit, previously bound endpoint. No POST retries. */
@Service @RequiredArgsConstructor
public class SqlGatewayClient {
    private final RestTemplate rest;
    private final ObjectMapper mapper;

    public static String endpoint(String value) {
        URI uri = URI.create(value == null ? "" : value.trim());
        if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("引擎地址必须为不含凭证、查询参数和片段的 HTTP(S) 地址");
        }
        return uri.toString().replaceAll("/+$", "");
    }
    public static String handle(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_-]{1,64}")) throw new IllegalArgumentException("无效的引擎句柄");
        return value;
    }
    public String open(String endpoint, Map<String, Object> body) {
        return responseHandle(post(endpoint, "/v1/sessions", body), "sessionHandle", "sessionId");
    }
    public String submit(String endpoint, String session, String statement, Map<String, String> config) {
        return responseHandle(post(endpoint, "/v1/sessions/" + handle(session) + "/statements",
                Map.of("statement", statement, "executionConfig", config)), "operationHandle", "operationId");
    }
    public JsonNode status(String endpoint, String session, String operation) {
        return get(endpoint, operationPath(session, operation) + "/status");
    }
    public JsonNode result(String endpoint, String session, String operation, long token) {
        if (token < 0) throw new IllegalArgumentException("无效的结果页");
        return get(endpoint, operationPath(session, operation) + "/result/" + token);
    }
    public JsonNode job(String endpoint, String jobId) { return get(endpoint, "/jobs/" + handle(jobId)); }
    public String close(String endpoint, String session) {
        try { rest.delete(endpoint(endpoint) + "/v1/sessions/" + handle(session)); return "closed"; }
        catch (RestClientResponseException failure) {
            String body = failure.getResponseBodyAsString();
            // A proxy's generic 404 is not proof that the bound Gateway session is absent.
            if (body.contains(session) && body.contains("Session") && body.contains("does not exist")) return "absent";
            throw failure;
        }
    }
    private String operationPath(String session, String operation) {
        return "/v1/sessions/" + handle(session) + "/operations/" + handle(operation);
    }
    public JsonNode get(String endpoint, String path) { return json(rest.getForObject(endpoint(endpoint) + path, String.class)); }
    private JsonNode post(String endpoint, String path, Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders(); headers.setContentType(MediaType.APPLICATION_JSON);
        return json(rest.postForObject(endpoint(endpoint) + path, new HttpEntity<>(body, headers), String.class));
    }
    private JsonNode json(String value) {
        try { if (value == null) throw new IllegalStateException("引擎未返回结果"); return mapper.readTree(value); }
        catch (java.io.IOException failure) { throw new IllegalStateException("引擎返回无法解析", failure); }
    }
    private String responseHandle(JsonNode node, String primary, String legacy) {
        return handle(node.path(primary).asText(node.path(legacy).asText()));
    }
}
