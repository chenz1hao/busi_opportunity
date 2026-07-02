package com.opportunity.scheduler;

import com.opportunity.entity.SchedulerLog;
import com.opportunity.repository.SchedulerLogRepository;
import com.opportunity.service.ConfigService;
import com.opportunity.service.OpportunityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.support.CronTrigger;

import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.ScheduledFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * 定时任务调度器测试
 * 覆盖：cron 注册、cron 无效、重复调度取消旧任务、定时搜索/分析的成功与失败
 */
@ExtendWith(MockitoExtension.class)
class OpportunitySchedulerTest {

    @Mock
    private OpportunityService opportunityService;
    @Mock
    private SchedulerLogRepository schedulerLogRepository;
    @Mock
    private ConfigService configService;
    @Mock
    private TaskScheduler taskScheduler;
    @Mock
    private ScheduledFuture<?> scheduledFuture;

    private OpportunityScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new OpportunityScheduler(opportunityService, schedulerLogRepository, configService, taskScheduler);
    }

    // ========== rescheduleAll 测试 ==========

    @Test
    @DisplayName("rescheduleAll 注册搜索与分析两个定时任务")
    void rescheduleAll_normal_registersBothTasks() {
        when(configService.getSearchCron()).thenReturn("0 0 8 * * ?");
        when(configService.getAnalysisCron()).thenReturn("0 0 2 * * ?");
        // 使用 doReturn().when() 避免 ScheduledFuture<?> 通配符泛型推断问题
        doReturn(scheduledFuture).when(taskScheduler).schedule(any(Runnable.class), any(CronTrigger.class));

        scheduler.rescheduleAll();

        verify(taskScheduler, times(2)).schedule(any(Runnable.class), any(CronTrigger.class));
    }

    @Test
    @DisplayName("rescheduleAll 重复调用时先取消旧任务")
    void rescheduleAll_repeat_cancelsOldTasks() {
        when(configService.getSearchCron()).thenReturn("0 0 8 * * ?");
        when(configService.getAnalysisCron()).thenReturn("0 0 2 * * ?");
        doReturn(scheduledFuture).when(taskScheduler).schedule(any(Runnable.class), any(CronTrigger.class));
        when(scheduledFuture.cancel(false)).thenReturn(true);

        scheduler.rescheduleAll();
        scheduler.rescheduleAll();

        // 两次 rescheduleAll，第一次注册的任务应该被取消
        verify(scheduledFuture, times(2)).cancel(false);
        verify(taskScheduler, times(4)).schedule(any(Runnable.class), any(CronTrigger.class));
    }

    @Test
    @DisplayName("searchCron 无效时不抛异常仅记录日志")
    void rescheduleAll_invalidSearchCron_logsError() {
        when(configService.getSearchCron()).thenReturn("invalid-cron");
        when(configService.getAnalysisCron()).thenReturn("0 0 2 * * ?");
        doReturn(scheduledFuture).when(taskScheduler).schedule(any(Runnable.class), any(CronTrigger.class));

        // 无效 cron 在 CronTrigger 构造时抛 IllegalArgumentException，被 scheduleSearch 捕获
        scheduler.rescheduleAll();

        // 分析任务仍正常注册（只调用1次，搜索任务因cron无效未注册）
        verify(taskScheduler, times(1)).schedule(any(Runnable.class), any(CronTrigger.class));
    }

    @Test
    @DisplayName("analysisCron 无效时不抛异常仅记录日志")
    void rescheduleAll_invalidAnalysisCron_logsError() {
        when(configService.getSearchCron()).thenReturn("0 0 8 * * ?");
        when(configService.getAnalysisCron()).thenReturn("invalid-cron");
        doReturn(scheduledFuture).when(taskScheduler).schedule(any(Runnable.class), any(CronTrigger.class));

        scheduler.rescheduleAll();

        // 搜索任务正常注册（只调用1次，分析任务因cron无效未注册）
        verify(taskScheduler, times(1)).schedule(any(Runnable.class), any(CronTrigger.class));
    }

    // ========== scheduledSearch 测试 ==========

    @Test
    @DisplayName("scheduledSearch 成功执行并记录 SUCCESS")
    void scheduledSearch_success_recordsSuccessLog() {
        when(configService.getActiveCityNames()).thenReturn(Collections.singletonList("北京市"));
        when(opportunityService.executeSearch(anyList()))
                .thenReturn(new OpportunityService.TaskResult(10, 5));
        when(schedulerLogRepository.save(any())).thenAnswer(inv -> {
            SchedulerLog log = inv.getArgument(0);
            log.setId(1L);
            return log;
        });

        scheduler.scheduledSearch();

        ArgumentCaptor<SchedulerLog> captor = ArgumentCaptor.forClass(SchedulerLog.class);
        verify(schedulerLogRepository, atLeastOnce()).save(captor.capture());
        SchedulerLog lastLog = captor.getValue();
        assertThat(lastLog.getTaskName()).isEqualTo("SEARCH_CRAWL");
        assertThat(lastLog.getStatus()).isEqualTo("SUCCESS");
        assertThat(lastLog.getTotalCount()).isEqualTo(10);
        assertThat(lastLog.getNewCount()).isEqualTo(5);
    }

    @Test
    @DisplayName("scheduledSearch 异常时记录 FAILED")
    void scheduledSearch_throws_recordsFailed() {
        when(configService.getActiveCityNames()).thenReturn(Collections.singletonList("北京市"));
        when(opportunityService.executeSearch(anyList()))
                .thenThrow(new RuntimeException("搜索失败"));
        when(schedulerLogRepository.save(any())).thenAnswer(inv -> {
            SchedulerLog log = inv.getArgument(0);
            log.setId(1L);
            return log;
        });

        scheduler.scheduledSearch();

        ArgumentCaptor<SchedulerLog> captor = ArgumentCaptor.forClass(SchedulerLog.class);
        verify(schedulerLogRepository, atLeastOnce()).save(captor.capture());
        SchedulerLog lastLog = captor.getValue();
        assertThat(lastLog.getStatus()).isEqualTo("FAILED");
        assertThat(lastLog.getErrorMsg()).contains("搜索失败");
    }

    // ========== scheduledAnalysis 测试 ==========

    @Test
    @DisplayName("scheduledAnalysis 成功执行并记录 SUCCESS")
    void scheduledAnalysis_success_recordsSuccessLog() {
        when(opportunityService.executeAnalysis())
                .thenReturn(new OpportunityService.TaskResult(20, 15));
        when(schedulerLogRepository.save(any())).thenAnswer(inv -> {
            SchedulerLog log = inv.getArgument(0);
            log.setId(1L);
            return log;
        });

        scheduler.scheduledAnalysis();

        ArgumentCaptor<SchedulerLog> captor = ArgumentCaptor.forClass(SchedulerLog.class);
        verify(schedulerLogRepository, atLeastOnce()).save(captor.capture());
        SchedulerLog lastLog = captor.getValue();
        assertThat(lastLog.getTaskName()).isEqualTo("SCORE_ANALYSIS");
        assertThat(lastLog.getStatus()).isEqualTo("SUCCESS");
        assertThat(lastLog.getTotalCount()).isEqualTo(20);
        assertThat(lastLog.getNewCount()).isEqualTo(15);
    }

    @Test
    @DisplayName("scheduledAnalysis 异常时记录 FAILED")
    void scheduledAnalysis_throws_recordsFailed() {
        when(opportunityService.executeAnalysis())
                .thenThrow(new RuntimeException("分析失败"));
        when(schedulerLogRepository.save(any())).thenAnswer(inv -> {
            SchedulerLog log = inv.getArgument(0);
            log.setId(1L);
            return log;
        });

        scheduler.scheduledAnalysis();

        ArgumentCaptor<SchedulerLog> captor = ArgumentCaptor.forClass(SchedulerLog.class);
        verify(schedulerLogRepository, atLeastOnce()).save(captor.capture());
        SchedulerLog lastLog = captor.getValue();
        assertThat(lastLog.getStatus()).isEqualTo("FAILED");
        assertThat(lastLog.getErrorMsg()).contains("分析失败");
    }
}
