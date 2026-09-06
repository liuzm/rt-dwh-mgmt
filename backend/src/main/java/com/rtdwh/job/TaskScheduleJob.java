package com.rtdwh.job;

import com.rtdwh.service.TaskScheduleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j @Component @RequiredArgsConstructor
@ConditionalOnProperty(prefix = "workflow.schedule-trigger", name = "enabled", havingValue = "true", matchIfMissing = true)
public class TaskScheduleJob {
    private final TaskScheduleService service;
    @Scheduled(fixedDelayString = "${workflow.schedule-trigger.interval-ms:10000}", initialDelayString = "${workflow.schedule-trigger.initial-delay-ms:15000}")
    public void run() {
        try { int count = service.runDue(); if (count > 0) log.info("Created {} scheduled workflow instances", count); }
        catch (Exception exception) { log.error("Scheduled workflow trigger failed", exception); }
    }
}
