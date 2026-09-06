package com.rtdwh.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rtdwh.dto.WorkflowDTO;
import com.rtdwh.entity.SyncTask;
import com.rtdwh.entity.TaskSchedule;
import com.rtdwh.entity.TaskScheduleRevision;
import com.rtdwh.repository.TaskScheduleRevisionRepository;
import com.rtdwh.repository.SyncTaskRepository;
import com.rtdwh.repository.TaskScheduleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.util.List;

@Service
@RequiredArgsConstructor
public class TaskScheduleService {
    private final TaskScheduleRepository repository;
    private final SyncTaskRepository taskRepository;
    private final WorkflowService workflowService;
    private final ObjectMapper objectMapper;
    private final TaskScheduleRevisionRepository revisions;

    public List<TaskSchedule> list() { return repository.findAll(); }

    @Transactional
    public TaskSchedule configure(Long taskId, WorkflowDTO.ScheduleRequest request, Long userId) {
        SyncTask task = taskRepository.findByIdForUpdate(taskId).orElseThrow(() -> new IllegalArgumentException("任务不存在: " + taskId));
        if (task.getExecutionMode() != SyncTask.ExecutionMode.scheduled) {
            throw new IllegalArgumentException("只有周期任务可以配置周期调度");
        }
        if (task.getPublishedVersionId() == null) {
            throw new IllegalStateException("请先发布任务版本再启用周期调度");
        }
        ZoneId zone = zone(request.getTimezone());
        CronExpression cron = cron(request.getCronExpression());
        String parameters = normalizeJson(request.getParametersJson());
        workflowService.validateScheduleParameters(taskId, parameters);
        TaskSchedule schedule = repository.findByTaskId(taskId).orElseGet(TaskSchedule::new);
        schedule.setTaskId(taskId);
        schedule.setCronExpression(request.getCronExpression().trim());
        schedule.setTimezone(zone.getId());
        schedule.setBusinessDateOffset(Math.max(-366, Math.min(request.getBusinessDateOffset() == null ? -1 : request.getBusinessDateOffset(), 366)));
        schedule.setParametersJson(parameters);
        schedule.setEnabled(!Boolean.FALSE.equals(request.getEnabled()));
        schedule.setCreatedBy(schedule.getCreatedBy() == null ? userId : schedule.getCreatedBy());
        schedule.setNextRunAt(schedule.getEnabled() ? next(cron, zone, Instant.now()) : null);
        schedule.setLastError(null);
        repository.saveAndFlush(schedule);
        schedule.setActiveRevisionId(recordRevision(schedule, userId, "configure").getId());
        return repository.save(schedule);
    }

    @Transactional
    public void delete(Long taskId, Long actor) {
        taskRepository.findByIdForUpdate(taskId).orElseThrow();
        repository.findByTaskId(taskId).ifPresent(schedule -> {
            schedule.setEnabled(false);
            recordRevision(schedule, actor, "delete");
            repository.delete(schedule);
        });
    }

    public List<TaskScheduleRevision> history(Long taskId) { return revisions.findTop100ByTaskIdOrderByRevisionNoDesc(taskId); }

    private TaskScheduleRevision recordRevision(TaskSchedule schedule, Long actor, String action) {
        int number = revisions.findFirstByTaskIdOrderByRevisionNoDesc(schedule.getTaskId())
                .map(revision -> revision.getRevisionNo() + 1).orElse(1);
        return revisions.saveAndFlush(TaskScheduleRevision.builder().taskId(schedule.getTaskId()).scheduleId(schedule.getId())
                .revisionNo(number).cronExpression(schedule.getCronExpression()).timezone(schedule.getTimezone())
                .businessDateOffset(schedule.getBusinessDateOffset()).parametersJson(schedule.getParametersJson())
                .enabled(schedule.getEnabled()).action(action).createdBy(actor).createdAt(LocalDateTime.now()).build());
    }

    @Transactional
    public int runDue() {
        int count = 0;
        for (int i = 0; i < 100; i++) {
            if (!runOneDue()) break;
            count++;
        }
        return count;
    }

    private boolean runOneDue() {
        Instant now = Instant.now();
        TaskSchedule schedule = repository.findDueForUpdate(now, PageRequest.of(0, 1)).stream().findFirst().orElse(null);
        if (schedule == null) return false;
        if (schedule.getActiveRevisionId() == null) {
            // Freeze only the currently observed legacy configuration, never backfill past provenance.
            schedule.setActiveRevisionId(recordRevision(schedule, schedule.getCreatedBy(), "adopt").getId());
        }
        TaskScheduleRevision revision = revisions.findById(schedule.getActiveRevisionId())
                .filter(value -> value.getTaskId().equals(schedule.getTaskId())).orElseThrow();
        try {
            workflowService.validateScheduleParameters(schedule.getTaskId(), revision.getParametersJson());
        } catch (IllegalArgumentException | IllegalStateException invalid) {
            schedule.setEnabled(false); schedule.setNextRunAt(null);
            schedule.setLastError("已发布版本不接受当前调度参数，请重新配置调度");
            repository.save(schedule); return true;
        }
        Instant scheduledAt = schedule.getNextRunAt();
        ZoneId zone = zone(revision.getTimezone());
        schedule.setLastRunAt(scheduledAt);
        schedule.setNextRunAt(next(cron(revision.getCronExpression()), zone, now));
        schedule.setLastError(null);
        repository.save(schedule);
        LocalDate businessDate = scheduledAt.atZone(zone).toLocalDate().plusDays(revision.getBusinessDateOffset());
        workflowService.createScheduledInstance(schedule.getTaskId(), schedule.getId(), scheduledAt,
                businessDate, revision.getParametersJson(), revision.getCreatedBy(), revision.getId());
        return true;
    }

    private Instant next(CronExpression cron, ZoneId zone, Instant base) {
        ZonedDateTime value = cron.next(base.atZone(zone));
        if (value == null) throw new IllegalArgumentException("Cron 无法计算下次执行时间");
        return value.toInstant();
    }
    private CronExpression cron(String value) {
        try { return CronExpression.parse(value.trim()); }
        catch (Exception exception) { throw new IllegalArgumentException("Cron 表达式格式不正确"); }
    }
    private ZoneId zone(String value) {
        try { return ZoneId.of(value == null || value.isBlank() ? "Asia/Shanghai" : value.trim()); }
        catch (Exception exception) { throw new IllegalArgumentException("时区格式不正确"); }
    }
    private String normalizeJson(String json) {
        if (json == null || json.isBlank()) return "{}";
        try {
            JsonNode node = objectMapper.readTree(json);
            if (!node.isObject()) throw new IllegalArgumentException();
            return objectMapper.writeValueAsString(node);
        } catch (Exception exception) { throw new IllegalArgumentException("调度参数必须是 JSON 对象"); }
    }
}
