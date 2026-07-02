package com.opportunity.scheduler;

import com.opportunity.entity.SchedulerLog;
import com.opportunity.repository.SchedulerLogRepository;
import com.opportunity.service.ConfigService;
import com.opportunity.service.OpportunityService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;

/**
 * 商机信息定时任务调度器
 * 使用 TaskScheduler + CronTrigger 实现动态 cron 调度
 * （cron 配置存储在数据库，前端修改后通过 ConfigService 回调本类重新注册任务）
 */
@Component
public class OpportunityScheduler {

    private static final Logger log = LoggerFactory.getLogger(OpportunityScheduler.class);

    private final OpportunityService opportunityService;
    private final SchedulerLogRepository schedulerLogRepository;
    private final ConfigService configService;
    private final TaskScheduler taskScheduler;

    /** 当前注册的任务句柄（按任务名区分） */
    private final Map<String, ScheduledFuture<?>> scheduledTasks = new HashMap<>();

    public OpportunityScheduler(OpportunityService opportunityService,
                                SchedulerLogRepository schedulerLogRepository,
                                ConfigService configService,
                                TaskScheduler taskScheduler) {
        this.opportunityService = opportunityService;
        this.schedulerLogRepository = schedulerLogRepository;
        this.configService = configService;
        this.taskScheduler = taskScheduler;
    }

    @PostConstruct
    public void init() {
        // 注册 cron 变更监听器：配置变更时重新调度
        configService.registerCronChangeListener(this::rescheduleAll);
        // 首次注册任务
        rescheduleAll();
    }

    /**
     * 重新调度所有任务（先取消旧任务，再用最新 cron 重新注册）
     */
    public synchronized void rescheduleAll() {
        cancelTask("SEARCH_CRAWL");
        cancelTask("SCORE_ANALYSIS");

        scheduleSearch();
        scheduleAnalysis();

        log.info("定时任务已重新注册: search-cron=[{}], analysis-cron=[{}]",
                configService.getSearchCron(), configService.getAnalysisCron());
    }

    private void cancelTask(String taskName) {
        ScheduledFuture<?> future = scheduledTasks.remove(taskName);
        if (future != null) {
            future.cancel(false);
        }
    }

    private void scheduleSearch() {
        String cron = configService.getSearchCron();
        try {
            CronTrigger trigger = new CronTrigger(cron);
            ScheduledFuture<?> future = taskScheduler.schedule(this::scheduledSearch, trigger);
            scheduledTasks.put("SEARCH_CRAWL", future);
            log.info("已注册定时搜索任务: cron=[{}]", cron);
        } catch (IllegalArgumentException e) {
            log.error("定时搜索任务 cron 表达式无效: [{}] - {}", cron, e.getMessage());
        }
    }

    private void scheduleAnalysis() {
        String cron = configService.getAnalysisCron();
        try {
            CronTrigger trigger = new CronTrigger(cron);
            ScheduledFuture<?> future = taskScheduler.schedule(this::scheduledAnalysis, trigger);
            scheduledTasks.put("SCORE_ANALYSIS", future);
            log.info("已注册定时分析任务: cron=[{}]", cron);
        } catch (IllegalArgumentException e) {
            log.error("定时分析任务 cron 表达式无效: [{}] - {}", cron, e.getMessage());
        }
    }

    /**
     * 定时搜索抓取商机信息
     */
    public void scheduledSearch() {
        SchedulerLog taskLog = new SchedulerLog();
        taskLog.setTaskName("SEARCH_CRAWL");
        taskLog.setStartTime(LocalDateTime.now());
        taskLog.setStatus("RUNNING");
        // 处理总数在任务完成后才知道（搜索到的候选线索数），先留空
        taskLog.setTotalCount(null);
        schedulerLogRepository.save(taskLog);

        log.info("===== 定时搜索任务开始 =====");
        try {
            java.util.List<String> cities = configService.getActiveCityNames();
            OpportunityService.TaskResult result = opportunityService.executeSearch(cities);
            taskLog.setTotalCount(result.getTotalCount());
            taskLog.setNewCount(result.getNewCount());
            // 部分城市搜索失败时记录错误信息，但不影响整体任务状态
            if (!result.getErrors().isEmpty()) {
                taskLog.setErrorMsg(String.join(" | ", result.getErrors()));
            }
            taskLog.setStatus("SUCCESS");
            log.info("===== 定时搜索任务完成: 候选 {} 条，新增 {} 条 =====",
                    result.getTotalCount(), result.getNewCount());
        } catch (Exception e) {
            log.error("定时搜索任务失败", e);
            taskLog.setStatus("FAILED");
            taskLog.setErrorMsg(e.getMessage());
        } finally {
            taskLog.setEndTime(LocalDateTime.now());
            schedulerLogRepository.save(taskLog);
        }
    }

    /**
     * 定时评分分析
     */
    public void scheduledAnalysis() {
        SchedulerLog taskLog = new SchedulerLog();
        taskLog.setTaskName("SCORE_ANALYSIS");
        taskLog.setStartTime(LocalDateTime.now());
        taskLog.setStatus("RUNNING");
        // 处理总数在任务完成后才知道（待分析记录数），先留空
        taskLog.setTotalCount(null);
        schedulerLogRepository.save(taskLog);

        log.info("===== 定时评分分析任务开始 =====");
        try {
            OpportunityService.TaskResult result = opportunityService.executeAnalysis();
            taskLog.setTotalCount(result.getTotalCount());
            taskLog.setNewCount(result.getNewCount());
            // 部分批次分析失败时记录错误信息
            if (!result.getErrors().isEmpty()) {
                taskLog.setErrorMsg(String.join(" | ", result.getErrors()));
            }
            taskLog.setStatus("SUCCESS");
            log.info("===== 定时评分分析任务完成: 待分析 {} 条，成功 {} 条 =====",
                    result.getTotalCount(), result.getNewCount());
        } catch (Exception e) {
            log.error("定时评分分析任务失败", e);
            taskLog.setStatus("FAILED");
            taskLog.setErrorMsg(e.getMessage());
        } finally {
            taskLog.setEndTime(LocalDateTime.now());
            schedulerLogRepository.save(taskLog);
        }
    }
}
