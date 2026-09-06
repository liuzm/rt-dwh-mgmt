package com.rtdwh.job;

import com.rtdwh.service.SyncTaskService;
import lombok.extern.slf4j.Slf4j;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.quartz.DisallowConcurrentExecution;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.quartz.QuartzJobBean;

@Slf4j
@DisallowConcurrentExecution
public class FlinkJobStatusMonitorJob extends QuartzJobBean {

    @Autowired
    private SyncTaskService syncTaskService;

    @Override
    protected void executeInternal(JobExecutionContext context) throws JobExecutionException {
        try {
            log.debug("Starting Flink job status monitor cycle");
            int synced = syncTaskService.syncTaskStatusFromFlink();
            log.debug("Flink status monitor completed: {} tasks synced", synced);
        } catch (Exception e) {
            log.error("Flink job status monitor error: {}", e.getMessage(), e);
            // Don't rethrow - we want the job to keep running on next schedule
        }
    }
}
