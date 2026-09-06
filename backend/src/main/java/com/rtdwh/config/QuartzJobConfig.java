package com.rtdwh.config;

import com.rtdwh.job.FlinkJobStatusMonitorJob;
import org.quartz.*;
import org.quartz.spi.TriggerFiredBundle;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.quartz.SchedulerFactoryBeanCustomizer;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.quartz.SpringBeanJobFactory;

@Configuration
public class QuartzJobConfig {

    /**
     * Quartz creates Job instances itself, so explicitly let Spring inject the
     * service dependencies declared on those instances.
     */
    @Bean
    public SchedulerFactoryBeanCustomizer quartzJobAutowireCustomizer(ApplicationContext applicationContext) {
        AutowireCapableBeanFactory beanFactory = applicationContext.getAutowireCapableBeanFactory();
        return schedulerFactoryBean -> schedulerFactoryBean.setJobFactory(new SpringBeanJobFactory() {
            @Override
            protected Object createJobInstance(TriggerFiredBundle bundle) throws Exception {
                Object job = super.createJobInstance(bundle);
                beanFactory.autowireBean(job);
                return job;
            }
        });
    }

    @Bean
    @ConditionalOnProperty(prefix = "flink.status-monitor", name = "enabled", havingValue = "true", matchIfMissing = true)
    public JobDetail flinkStatusMonitorJobDetail() {
        return JobBuilder.newJob(FlinkJobStatusMonitorJob.class)
                .withIdentity("flinkStatusMonitorJob", "monitorGroup")
                .storeDurably()
                .requestRecovery()
                .withDescription("定时轮询 Flink 集群状态并同步任务状态到 DB")
                .build();
    }

    @Bean
    @ConditionalOnProperty(prefix = "flink.status-monitor", name = "enabled", havingValue = "true", matchIfMissing = true)
    public Trigger flinkStatusMonitorTrigger(
            @org.springframework.beans.factory.annotation.Value("${flink.status-monitor.interval-seconds:30}")
            int intervalSeconds) {
        SimpleScheduleBuilder schedule = SimpleScheduleBuilder.simpleSchedule()
                .withIntervalInSeconds(Math.max(intervalSeconds, 5))
                .repeatForever()
                .withMisfireHandlingInstructionNowWithExistingCount();

        return TriggerBuilder.newTrigger()
                .forJob(flinkStatusMonitorJobDetail())
                .withIdentity("flinkStatusMonitorTrigger", "monitorGroup")
                .withSchedule(schedule)
                .withDescription("定时轮询 Flink 任务状态")
                .build();
    }
}
