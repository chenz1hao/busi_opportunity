package com.opportunity.service;

import com.opportunity.entity.SchedulerLog;
import com.opportunity.repository.SchedulerLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 异步任务执行器
 * 独立 Bean，避免 @Async 自调用失效问题
 * 用于手动触发的搜索/分析任务：立即返回，后台执行，并通过 scheduler_log 记录执行状态
 */
@Component
public class AsyncTaskExecutor {

    private static final Logger log = LoggerFactory.getLogger(AsyncTaskExecutor.class);

    private final OpportunityService opportunityService;
    private final ConfigService configService;
    private final SchedulerLogRepository schedulerLogRepository;

    public AsyncTaskExecutor(OpportunityService opportunityService,
                             ConfigService configService,
                             SchedulerLogRepository schedulerLogRepository) {
        this.opportunityService = opportunityService;
        this.configService = configService;
        this.schedulerLogRepository = schedulerLogRepository;
    }

    /**
     * 异步执行搜索抓取任务
     */
    @Async
    public void executeSearchAsync(List<String> cities) {
        SchedulerLog taskLog = new SchedulerLog();
        taskLog.setTaskName("SEARCH_CRAWL");
        taskLog.setStartTime(LocalDateTime.now());
        taskLog.setStatus("RUNNING");
        // 处理总数在任务完成后才知道（搜索到的候选线索数），先留空
        taskLog.setTotalCount(null);
        schedulerLogRepository.save(taskLog);

        log.info("===== 手动搜索任务开始 (id={}) =====", taskLog.getId());
        try {
            List<String> targetCities = (cities == null || cities.isEmpty())
                    ? configService.getActiveCityNames() : cities;
            OpportunityService.TaskResult result = opportunityService.executeSearch(targetCities);
            taskLog.setTotalCount(result.getTotalCount());
            taskLog.setNewCount(result.getNewCount());
            // 部分城市搜索失败时记录错误信息到 errorMsg
            if (!result.getErrors().isEmpty()) {
                taskLog.setErrorMsg(truncateError(String.join(" | ", result.getErrors())));
            }
            taskLog.setStatus("SUCCESS");
            log.info("===== 手动搜索任务完成 (id={}): 候选 {} 条，新增 {} 条 =====",
                    taskLog.getId(), result.getTotalCount(), result.getNewCount());
        } catch (Exception e) {
            log.error("手动搜索任务失败 (id={})", taskLog.getId(), e);
            taskLog.setStatus("FAILED");
            taskLog.setErrorMsg(truncateError(e.getMessage()));
        } finally {
            taskLog.setEndTime(LocalDateTime.now());
            schedulerLogRepository.save(taskLog);
        }
    }

    /**
     * 异步执行评分分析任务
     */
    @Async
    public void executeAnalysisAsync() {
        SchedulerLog taskLog = new SchedulerLog();
        taskLog.setTaskName("SCORE_ANALYSIS");
        taskLog.setStartTime(LocalDateTime.now());
        taskLog.setStatus("RUNNING");
        // 处理总数在任务完成后才知道（待分析记录数），先留空
        taskLog.setTotalCount(null);
        schedulerLogRepository.save(taskLog);

        log.info("===== 手动分析任务开始 (id={}) =====", taskLog.getId());
        try {
            OpportunityService.TaskResult result = opportunityService.executeAnalysis();
            taskLog.setTotalCount(result.getTotalCount());
            taskLog.setNewCount(result.getNewCount());
            // 部分批次分析失败时记录错误信息到 errorMsg
            if (!result.getErrors().isEmpty()) {
                taskLog.setErrorMsg(truncateError(String.join(" | ", result.getErrors())));
            }
            taskLog.setStatus("SUCCESS");
            log.info("===== 手动分析任务完成 (id={}): 待分析 {} 条，成功 {} 条 =====",
                    taskLog.getId(), result.getTotalCount(), result.getNewCount());
        } catch (Exception e) {
            log.error("手动分析任务失败 (id={})", taskLog.getId(), e);
            taskLog.setStatus("FAILED");
            taskLog.setErrorMsg(truncateError(e.getMessage()));
        } finally {
            taskLog.setEndTime(LocalDateTime.now());
            schedulerLogRepository.save(taskLog);
        }
    }

    /** 错误信息截断，避免过长 */
    private String truncateError(String msg) {
        if (msg == null) return null;
        return msg.length() > 2000 ? msg.substring(0, 2000) : msg;
    }
}
