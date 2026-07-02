package com.opportunity.service;

import com.opportunity.entity.SchedulerLog;
import com.opportunity.repository.SchedulerLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 异步任务执行器测试
 * 覆盖：搜索/分析的成功、失败、入参为空（默认取配置城市）、错误信息截断
 */
@ExtendWith(MockitoExtension.class)
class AsyncTaskExecutorTest {

    @Mock
    private OpportunityService opportunityService;
    @Mock
    private ConfigService configService;
    @Mock
    private SchedulerLogRepository schedulerLogRepository;

    private AsyncTaskExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new AsyncTaskExecutor(opportunityService, configService, schedulerLogRepository);
    }

    // ========== executeSearchAsync 测试 ==========

    @Test
    @DisplayName("executeSearchAsync 成功执行并记录 SUCCESS")
    void executeSearchAsync_success_recordsSuccessLog() {
        when(opportunityService.executeSearch(anyList()))
                .thenReturn(new OpportunityService.TaskResult(10, 5));

        // 模拟 save 第一次返回带 id 的 log
        when(schedulerLogRepository.save(any())).thenAnswer(inv -> {
            SchedulerLog log = inv.getArgument(0);
            log.setId(1L);
            return log;
        });

        executor.executeSearchAsync(Arrays.asList("北京市", "上海市"));

        ArgumentCaptor<SchedulerLog> captor = ArgumentCaptor.forClass(SchedulerLog.class);
        verify(schedulerLogRepository, atLeastOnce()).save(captor.capture());
        SchedulerLog savedLog = captor.getValue();
        assertThat(savedLog.getTaskName()).isEqualTo("SEARCH_CRAWL");
        assertThat(savedLog.getStatus()).isEqualTo("SUCCESS");
        assertThat(savedLog.getTotalCount()).isEqualTo(10);
        assertThat(savedLog.getNewCount()).isEqualTo(5);
        assertThat(savedLog.getEndTime()).isNotNull();
    }

    @Test
    @DisplayName("executeSearchAsync 入参为 null 时取默认城市")
    void executeSearchAsync_nullCities_usesDefault() {
        when(configService.getActiveCityNames()).thenReturn(Arrays.asList("北京市", "上海市"));
        when(opportunityService.executeSearch(anyList()))
                .thenReturn(new OpportunityService.TaskResult(0, 0));
        when(schedulerLogRepository.save(any())).thenAnswer(inv -> {
            SchedulerLog log = inv.getArgument(0);
            log.setId(1L);
            return log;
        });

        executor.executeSearchAsync(null);

        verify(configService).getActiveCityNames();
        verify(opportunityService).executeSearch(Arrays.asList("北京市", "上海市"));
    }

    @Test
    @DisplayName("executeSearchAsync 入参为空列表时取默认城市")
    void executeSearchAsync_emptyCities_usesDefault() {
        when(configService.getActiveCityNames()).thenReturn(Collections.singletonList("北京市"));
        when(opportunityService.executeSearch(anyList()))
                .thenReturn(new OpportunityService.TaskResult(0, 0));
        when(schedulerLogRepository.save(any())).thenAnswer(inv -> {
            SchedulerLog log = inv.getArgument(0);
            log.setId(1L);
            return log;
        });

        executor.executeSearchAsync(Collections.emptyList());

        verify(configService).getActiveCityNames();
    }

    @Test
    @DisplayName("executeSearchAsync 业务异常时记录 FAILED 和错误信息")
    void executeSearchAsync_throwsException_recordsFailed() {
        when(opportunityService.executeSearch(anyList()))
                .thenThrow(new RuntimeException("搜索服务异常"));
        when(schedulerLogRepository.save(any())).thenAnswer(inv -> {
            SchedulerLog log = inv.getArgument(0);
            log.setId(1L);
            return log;
        });

        executor.executeSearchAsync(Collections.singletonList("北京市"));

        ArgumentCaptor<SchedulerLog> captor = ArgumentCaptor.forClass(SchedulerLog.class);
        verify(schedulerLogRepository, atLeastOnce()).save(captor.capture());
        SchedulerLog lastLog = captor.getValue();
        assertThat(lastLog.getStatus()).isEqualTo("FAILED");
        assertThat(lastLog.getErrorMsg()).contains("搜索服务异常");
        assertThat(lastLog.getEndTime()).isNotNull();
    }

    @Test
    @DisplayName("executeSearchAsync 错误信息过长时被截断到 2000 字符")
    void executeSearchAsync_longErrorMsg_truncated() {
        String longMsg = String.join("", Collections.nCopies(3000, "x"));
        when(opportunityService.executeSearch(anyList()))
                .thenThrow(new RuntimeException(longMsg));
        when(schedulerLogRepository.save(any())).thenAnswer(inv -> {
            SchedulerLog log = inv.getArgument(0);
            log.setId(1L);
            return log;
        });

        executor.executeSearchAsync(Collections.singletonList("北京市"));

        ArgumentCaptor<SchedulerLog> captor = ArgumentCaptor.forClass(SchedulerLog.class);
        verify(schedulerLogRepository, atLeastOnce()).save(captor.capture());
        SchedulerLog lastLog = captor.getValue();
        assertThat(lastLog.getErrorMsg()).hasSize(2000);
    }

    // ========== executeAnalysisAsync 测试 ==========

    @Test
    @DisplayName("executeAnalysisAsync 成功执行并记录 SUCCESS")
    void executeAnalysisAsync_success_recordsSuccessLog() {
        when(opportunityService.executeAnalysis())
                .thenReturn(new OpportunityService.TaskResult(20, 15));
        when(schedulerLogRepository.save(any())).thenAnswer(inv -> {
            SchedulerLog log = inv.getArgument(0);
            log.setId(1L);
            return log;
        });

        executor.executeAnalysisAsync();

        ArgumentCaptor<SchedulerLog> captor = ArgumentCaptor.forClass(SchedulerLog.class);
        verify(schedulerLogRepository, atLeastOnce()).save(captor.capture());
        SchedulerLog lastLog = captor.getValue();
        assertThat(lastLog.getTaskName()).isEqualTo("SCORE_ANALYSIS");
        assertThat(lastLog.getStatus()).isEqualTo("SUCCESS");
        assertThat(lastLog.getTotalCount()).isEqualTo(20);
        assertThat(lastLog.getNewCount()).isEqualTo(15);
    }

    @Test
    @DisplayName("executeAnalysisAsync 业务异常时记录 FAILED")
    void executeAnalysisAsync_throwsException_recordsFailed() {
        when(opportunityService.executeAnalysis())
                .thenThrow(new RuntimeException("分析服务异常"));
        when(schedulerLogRepository.save(any())).thenAnswer(inv -> {
            SchedulerLog log = inv.getArgument(0);
            log.setId(1L);
            return log;
        });

        executor.executeAnalysisAsync();

        ArgumentCaptor<SchedulerLog> captor = ArgumentCaptor.forClass(SchedulerLog.class);
        verify(schedulerLogRepository, atLeastOnce()).save(captor.capture());
        SchedulerLog lastLog = captor.getValue();
        assertThat(lastLog.getStatus()).isEqualTo("FAILED");
        assertThat(lastLog.getErrorMsg()).contains("分析服务异常");
    }

    @Test
    @DisplayName("executeAnalysisAsync 异常时 errorMsg 为 null 不抛错")
    void executeAnalysisAsync_nullErrorMsg_handled() {
        when(opportunityService.executeAnalysis())
                .thenThrow(new RuntimeException((String) null));
        when(schedulerLogRepository.save(any())).thenAnswer(inv -> {
            SchedulerLog log = inv.getArgument(0);
            log.setId(1L);
            return log;
        });

        executor.executeAnalysisAsync();

        ArgumentCaptor<SchedulerLog> captor = ArgumentCaptor.forClass(SchedulerLog.class);
        verify(schedulerLogRepository, atLeastOnce()).save(captor.capture());
        SchedulerLog lastLog = captor.getValue();
        assertThat(lastLog.getStatus()).isEqualTo("FAILED");
        assertThat(lastLog.getErrorMsg()).isNull();
    }
}
