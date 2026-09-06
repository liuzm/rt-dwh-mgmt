package com.rtdwh.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.*;
import org.springframework.web.client.*;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SqlGatewayClientTest {
    final RestTemplate rest = mock(RestTemplate.class);
    final SqlGatewayClient client = new SqlGatewayClient(rest, new ObjectMapper());
    @Test void ambiguousMutationIsNotRetried() {
        when(rest.postForObject(anyString(),any(HttpEntity.class),eq(String.class))).thenThrow(new ResourceAccessException("lost"));
        assertThrows(ResourceAccessException.class,()->client.submit("http://bound", "session", "CALL sys.compact('ods.t')", Map.of()));
        verify(rest,times(1)).postForObject(eq("http://bound/v1/sessions/session/statements"),any(HttpEntity.class),eq(String.class));
    }
    @Test void missingOrPathShapedHandleCannotEscapeBoundOperation() {
        when(rest.postForObject(anyString(),any(HttpEntity.class),eq(String.class))).thenReturn("{}");
        assertThrows(IllegalArgumentException.class,()->client.open("http://bound",Map.of()));
        assertThrows(IllegalArgumentException.class,()->client.status("http://bound","../other","op"));
        verify(rest,never()).getForObject(anyString(),eq(String.class));
    }
    @Test void endpointCannotIncludeCredentialsOrRedirectQuery() {
        for (String address : new String[]{"file:///tmp/socket", "http://user:secret@host", "http://host?next=x", "http://host#fragment"})
            assertThrows(IllegalArgumentException.class,()->SqlGatewayClient.endpoint(address));
        assertEquals("https://gateway/proxy",SqlGatewayClient.endpoint("https://gateway/proxy/"));
    }
    @Test void generic404DoesNotProveSessionCleanup() {
        doThrow(HttpClientErrorException.create(HttpStatus.NOT_FOUND,"Not Found",HttpHeaders.EMPTY,"proxy route missing".getBytes(StandardCharsets.UTF_8),StandardCharsets.UTF_8))
                .when(rest).delete(anyString());
        assertThrows(HttpClientErrorException.class,()->client.close("http://bound","session-1"));
    }
    @Test void GatewayEvidenceOfAlreadyAbsentSessionAllowsIdempotentCleanup() {
        doThrow(HttpClientErrorException.create(HttpStatus.NOT_FOUND,"Not Found",HttpHeaders.EMPTY,
                "{\"errors\":[\"Session 'session-1' does not exist.\"]}".getBytes(StandardCharsets.UTF_8),StandardCharsets.UTF_8))
                .when(rest).delete(anyString());
        assertEquals("absent",client.close("http://bound","session-1"));
    }
}
