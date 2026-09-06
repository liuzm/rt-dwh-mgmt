package com.rtdwh.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rtdwh.entity.AlertRule;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.web.client.RestTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.util.ReflectionTestUtils.setField;

class AlertNotifyServiceTest {
    private final JavaMailSender mailSender = mock(JavaMailSender.class);
    private final RestTemplate restTemplate = mock(RestTemplate.class);
    private final AlertNotifyService service = new AlertNotifyService(
            mailSender, restTemplate, new ObjectMapper());

    @Test
    void http200WithWebhookErrorCodeIsNotMarkedAsSent() {
        setField(service, "dingtalkWebhook", "https://example.test/dingtalk");
        when(restTemplate.postForEntity(
                eq("https://example.test/dingtalk"), any(HttpEntity.class), eq(String.class)))
                .thenReturn(new ResponseEntity<>("{\"errcode\":310000,\"errmsg\":\"invalid token\"}", HttpStatus.OK));

        assertEquals(AlertNotifyService.AlertDeliveryStatus.RETRYABLE_FAILURE,
                service.sendAlertWithStatus(rule("dingtalk"), "failed", "error"));
    }

    @Test
    void http200WithZeroWebhookErrorCodeIsMarkedAsSent() {
        setField(service, "dingtalkWebhook", "https://example.test/dingtalk");
        when(restTemplate.postForEntity(
                eq("https://example.test/dingtalk"), any(HttpEntity.class), eq(String.class)))
                .thenReturn(new ResponseEntity<>("{\"errcode\":0,\"errmsg\":\"ok\"}", HttpStatus.OK));

        assertEquals(AlertNotifyService.AlertDeliveryStatus.SENT,
                service.sendAlertWithStatus(rule("dingtalk"), "failed", "error"));
    }

    private AlertRule rule(String channel) {
        AlertRule rule = new AlertRule();
        rule.setRuleName("quality alert");
        rule.setRuleType("quality_failure");
        rule.setNotifyChannel(channel);
        return rule;
    }
}
