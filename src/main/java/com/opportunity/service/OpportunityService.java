package com.opportunity.service;

import com.opportunity.entity.WebPageInfo;
import com.opportunity.repository.WebPageInfoRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 商机信息核心服务
 * 协调搜索、去重、落库、评分等流程
 */
@Service
public class OpportunityService {

    private static final Logger log = LoggerFactory.getLogger(OpportunityService.class);

    private final WebPageInfoRepository repository;
    private final VolcengineSearchService searchService;
    private final SimHashService similarityService;
    private final LlmAnalysisService analysisService;
    private final ConfigService configService;

    /**
     * 自注入代理对象，用于解决 @Transactional 自调用失效问题
     * executeAnalysis() 非 @Transactional，直接 this 调用本类的 analyzeAndSaveOneBatch(@Transactional)
     * 不会走 AOP 代理，事务不生效。通过 self 调用可保证事务正常开启。
     * 使用 @Lazy 避免循环依赖初始化问题。
     */
    private final OpportunityService self;

    public OpportunityService(WebPageInfoRepository repository,
                              VolcengineSearchService searchService,
                              SimHashService similarityService,
                              LlmAnalysisService analysisService,
                              ConfigService configService,
                              @Lazy @Autowired OpportunityService self) {
        this.repository = repository;
        this.searchService = searchService;
        this.similarityService = similarityService;
        this.analysisService = analysisService;
        this.configService = configService;
        this.self = self;
    }

    /**
     * 执行搜索抓取流程
     *
     * @param cities 城市列表
     * @return 新增数量
     */
    @Transactional
    public TaskResult executeSearch(List<String> cities) {
        int newCount = 0;
        int totalCandidates = 0;
        List<String> errors = new ArrayList<>();
        // 加载已有正文 SimHash 指纹，用于内容级去重
        List<Long> existingHashes = repository.findAllSimHashes();

        for (String city : cities) {
            try {
                log.info("开始搜索城市: {}", city);
                List<VolcengineSearchService.SearchResult> results = searchService.searchOpportunities(city);
                totalCandidates += results.size();

                for (VolcengineSearchService.SearchResult result : results) {
                    // 检查URL是否已存在（历史数据可能存在重复，取 List 首条判断）
                    if (result.getUrl() != null && !repository.findByUrl(result.getUrl()).isEmpty()) {
                        log.debug("爬取URL完全重复跳过：{}", result.getTitle());
                        continue;
                    }

                    // 基于正文内容的 SimHash 去重
                    double dedupThreshold = configService.getDedupThreshold();
                    Long contentHash = similarityService.computeSimHash(result.getContent());
                    if (contentHash != null) {
                        double maxSimilarity = similarityService.getMaxSimilarity(contentHash, existingHashes);
                        if (maxSimilarity >= dedupThreshold) {
                            log.debug("内容去重跳过: {} (相似度: {})", result.getTitle(), String.format("%.4f", maxSimilarity));
                            continue;
                        }
                    }

                    // 保存新记录
                    WebPageInfo entity = new WebPageInfo();
                    entity.setTitle(truncate(result.getTitle(), 500));
                    entity.setContent(truncate(result.getContent(), 10000));
                    entity.setUrl(result.getUrl());
                    entity.setPublishTime(result.getPublishTime());
                    entity.setSourceCity(city);
                    entity.setTitleHash(sha256(result.getTitle()));
                    entity.setSimHash(contentHash);
                    entity.setMaxSimilarityScore(contentHash != null
                            ? similarityService.getMaxSimilarity(contentHash, existingHashes) : 0.0);

                    repository.save(entity);
                    if (contentHash != null) {
                        existingHashes.add(contentHash);
                    }
                    newCount++;
                    log.info("新增记录: {} (城市: {})", entity.getTitle(), city);
                }
            } catch (Exception e) {
                log.error("搜索城市 {} 失败: {}", city, e.getMessage(), e);
                errors.add("城市[" + city + "]搜索失败: " + e.getMessage());
            }
            // 城市间请求间隔，避免触发搜索接口 QPS 限流（最后一个城市不等待）
            try {
                Thread.sleep(1000);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        log.info("搜索完成: 候选线索数={}, 新增记录数={}", totalCandidates, newCount);
        return new TaskResult(totalCandidates, newCount, errors);
    }

    /**
     * 执行评分分析流程
     * 改为按批次分页查询、每批调用大模型后立即落表，避免应用异常导致大量 token 浪费
     *
     * @return 分析数量（totalCount=总待分析数，newCount=成功分析数）
     */
    public TaskResult executeAnalysis() {
        int batchSize = configService.getAnalysisBatchSize();
        long totalPending = repository.countUnscoredRecords();
        if (totalPending == 0) {
            log.info("没有需要分析的记录");
            return new TaskResult(0, 0);
        }
        log.info("开始分批分析: 待分析记录数={}, 单批次大小={}", totalPending, batchSize);

        int totalAnalyzed = 0;
        int batchIndex = 0;
        List<String> errors = new ArrayList<>();
        // 连续失败计数器：大模型接口持续故障时，失败批次的 analysisStartTime 会被清空，
        // 下一批查询又会捞到同样记录，导致死循环。这里通过连续失败次数限制来终止任务。
        int consecutiveFailures = 0;
        final int maxConsecutiveFailures = 3;

        // 循环按批次处理：每批查询 → 标记开始时间 → 调用大模型 → 立即落表
        while (true) {
            // 分页查询本批待分析记录
            List<WebPageInfo> batch = repository.findUnscoredRecordsPage(
                    org.springframework.data.domain.PageRequest.of(0, batchSize));
            if (batch.isEmpty()) {
                break; // 所有记录已处理完
            }
            batchIndex++;
            log.info("===== 开始处理第 {} 批, 本批 {} 条 =====", batchIndex, batch.size());

            try {
                // 通过代理对象调用，确保 @Transactional 注解生效（避免自调用导致事务失效）
                int analyzed = self.analyzeAndSaveOneBatch(batch);
                totalAnalyzed += analyzed;
                consecutiveFailures = 0; // 成功则重置计数
                log.info("===== 第 {} 批完成, 成功分析 {} 条 =====", batchIndex, analyzed);
            } catch (Exception e) {
                consecutiveFailures++;
                // 单批失败：已在该方法内回滚 analysisStartTime
                log.error("===== 第 {} 批失败 (连续失败 {}/{}): {} =====",
                        batchIndex, consecutiveFailures, maxConsecutiveFailures, e.getMessage(), e);
                errors.add("第" + batchIndex + "批分析失败: " + e.getMessage());
                if (consecutiveFailures >= maxConsecutiveFailures) {
                    // 连续多批失败：说明大模型接口大概率已不可用，终止整个分析任务避免死循环
                    // 未处理的记录 analysisStartTime 已被清空，下次任务可继续筛选
                    log.error("===== 连续 {} 批失败，终止分析任务，避免死循环 =====", consecutiveFailures);
                    break;
                }
            }
        }

        log.info("分析完成: 待分析数={}, 成功分析数={}", totalPending, totalAnalyzed);
        return new TaskResult((int) totalPending, totalAnalyzed, errors);
    }

    /**
     * 分析并落表单个批次
     * 1. 标记开始时间 → 落表
     * 2. 调用大模型
     * 3. 写入评分结果 → 落表（成功）；或清空开始时间 → 落表（失败/遗漏）
     *
     * @return 本批成功分析的条数
     */
    @Transactional
    public int analyzeAndSaveOneBatch(List<WebPageInfo> batch) {
        // 1. 标记分析开始时间并落表（占用这些记录，避免并发重复处理）
        for (WebPageInfo record : batch) {
            record.setAnalysisStartTime(LocalDateTime.now());
        }
        repository.saveAll(batch);

        List<LlmAnalysisService.AnalysisResult> analysisResults;
        try {
            analysisResults = analysisService.analyzeOneBatch(batch);
        } catch (Exception e) {
            // 大模型调用整体失败：清空本批所有记录的开始时间，让下次还能被筛选出来
            log.error("大模型调用失败，回滚 {} 条记录的 analysisStartTime", batch.size(), e);
            for (WebPageInfo record : batch) {
                record.setAnalysisStartTime(null);
            }
            repository.saveAll(batch);
            throw new RuntimeException("大模型调用失败: " + e.getMessage(), e);
        }

        // 2. 写入评分结果
        Map<Long, WebPageInfo> recordMap = new HashMap<>();
        for (WebPageInfo record : batch) {
            recordMap.put(record.getId(), record);
        }

        int updatedCount = 0;
        Set<Long> analyzedIds = new HashSet<>();
        for (LlmAnalysisService.AnalysisResult result : analysisResults) {
            WebPageInfo record = recordMap.get(result.getRecordId());
            if (record != null) {
                record.setProvince(result.getProvince());
                record.setCity(result.getCity());
                record.setCounty(result.getCounty());
                record.setDeadline(result.getDeadline());
                record.setAmount(result.getAmount());
                record.setTypeFlag(result.getTypeFlag());
                record.setScore(result.getScore());
                record.setScoreReason(result.getScoreReason());
                record.setType(result.getType());
                record.setAnalysisEndTime(LocalDateTime.now());
                analyzedIds.add(record.getId());
                updatedCount++;
            }
        }

        // 3. 大模型未返回结果的记录：清空开始时间，下次可重新分析
        int skippedCount = 0;
        for (WebPageInfo record : batch) {
            if (!analyzedIds.contains(record.getId())) {
                record.setAnalysisStartTime(null);
                skippedCount++;
            }
        }
        if (skippedCount > 0) {
            log.warn("本批大模型未返回结果的记录数={}，已清空 analysisStartTime", skippedCount);
        }

        // 4. 立即落表，保证本批结果持久化
        repository.saveAll(batch);
        return updatedCount;
    }

    /**
     * 获取统计信息
     */
    public Map<String, Object> getStatistics() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalCount", repository.count());
        stats.put("scoredCount", repository.countByScoreIsNotNull());
        stats.put("unscoredCount", repository.count() - repository.countByScoreIsNotNull());
        stats.put("cityDistribution", repository.countByCity());
        stats.put("typeDistribution", repository.countByType());
        return stats;
    }

    /**
     * 获取最近记录
     */
    public List<WebPageInfo> getRecentRecords() {
        return repository.findRecentRecords();
    }

    private String truncate(String str, int maxLen) {
        if (str == null) return null;
        return str.length() > maxLen ? str.substring(0, maxLen) : str;
    }

    private String sha256(String input) {
        if (input == null) return null;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes());
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(input.hashCode());
        }
    }

    /**
     * 任务执行结果
     * totalCount: 处理的线索总数（搜索=候选线索数，分析=待分析记录数）
     * newCount:   实际新增/成功处理的数量
     * errors:     过程中收集的错误信息（如部分城市搜索失败、部分批次分析失败）
     */
    public static class TaskResult {
        private final int totalCount;
        private final int newCount;
        private final List<String> errors;

        public TaskResult(int totalCount, int newCount) {
            this(totalCount, newCount, Collections.emptyList());
        }

        public TaskResult(int totalCount, int newCount, List<String> errors) {
            this.totalCount = totalCount;
            this.newCount = newCount;
            this.errors = errors != null ? errors : Collections.emptyList();
        }

        public int getTotalCount() {
            return totalCount;
        }

        public int getNewCount() {
            return newCount;
        }

        public List<String> getErrors() {
            return errors;
        }
    }
}