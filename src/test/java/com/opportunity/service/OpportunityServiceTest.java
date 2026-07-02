package com.opportunity.service;

import com.opportunity.entity.WebPageInfo;
import com.opportunity.repository.WebPageInfoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 商机信息核心服务测试
 * 覆盖：executeSearch 各种分支、executeAnalysis 死循环防护、analyzeAndSaveOneBatch 回滚、统计接口
 */
@ExtendWith(MockitoExtension.class)
class OpportunityServiceTest {

    @Mock
    private WebPageInfoRepository repository;
    @Mock
    private VolcengineSearchService searchService;
    @Mock
    private SimHashService similarityService;
    @Mock
    private LlmAnalysisService analysisService;
    @Mock
    private ConfigService configService;
    @Mock
    private OpportunityService selfProxy;

    private OpportunityService service;

    @BeforeEach
    void setUp() {
        service = new OpportunityService(repository, searchService, similarityService,
                analysisService, configService, selfProxy);
    }

    private VolcengineSearchService.SearchResult buildSearchResult(String title, String url) {
        VolcengineSearchService.SearchResult r = new VolcengineSearchService.SearchResult();
        r.setTitle(title);
        r.setUrl(url);
        r.setContent("正文内容");
        r.setPublishTime("2025-05-30");
        return r;
    }

    private WebPageInfo buildWebPageInfo(Long id, String title, String url) {
        WebPageInfo w = new WebPageInfo();
        w.setId(id);
        w.setTitle(title);
        w.setUrl(url);
        w.setContent("content");
        return w;
    }

    // ========== executeSearch 测试 ==========

    @Test
    @DisplayName("executeSearch 空城市列表返回 0")
    void executeSearch_emptyCities_returnsZero() {
        OpportunityService.TaskResult result = service.executeSearch(Collections.emptyList());
        assertThat(result.getTotalCount()).isZero();
        assertThat(result.getNewCount()).isZero();
    }

    @Test
    @DisplayName("executeSearch 正常新增记录")
    void executeSearch_normalNewRecord_saves() {
        when(repository.findAllSimHashes()).thenReturn(new ArrayList<>(Collections.singletonList(100L)));
        when(searchService.searchOpportunities("北京市"))
                .thenReturn(Arrays.asList(buildSearchResult("新招标公告", "http://a.com/1")));
        when(repository.findByUrl("http://a.com/1")).thenReturn(Collections.emptyList());
        when(configService.getDedupThreshold()).thenReturn(0.9);
        when(similarityService.computeSimHash("正文内容")).thenReturn(200L);
        when(similarityService.getMaxSimilarity(eq(200L), anyList())).thenReturn(0.3);

        OpportunityService.TaskResult result = service.executeSearch(Collections.singletonList("北京市"));

        assertThat(result.getTotalCount()).isEqualTo(1);
        assertThat(result.getNewCount()).isEqualTo(1);
        verify(repository).save(any(WebPageInfo.class));
    }

    @Test
    @DisplayName("executeSearch URL 已存在时跳过")
    void executeSearch_urlExists_skips() {
        when(repository.findAllSimHashes()).thenReturn(new ArrayList<>());
        when(searchService.searchOpportunities("北京市"))
                .thenReturn(Collections.singletonList(buildSearchResult("标题", "http://a.com/1")));
        // findByUrl 返回非空 → URL 已存在
        when(repository.findByUrl("http://a.com/1"))
                .thenReturn(Collections.singletonList(buildWebPageInfo(1L, "标题", "http://a.com/1")));

        OpportunityService.TaskResult result = service.executeSearch(Collections.singletonList("北京市"));

        assertThat(result.getNewCount()).isZero();
        verify(repository, never()).save(any(WebPageInfo.class));
    }

    @Test
    @DisplayName("executeSearch 内容 SimHash 相似度超阈值时跳过")
    void executeSearch_highSimilarity_skips() {
        when(repository.findAllSimHashes()).thenReturn(new ArrayList<>(Collections.singletonList(100L)));
        when(searchService.searchOpportunities("北京市"))
                .thenReturn(Collections.singletonList(buildSearchResult("已有标题", "http://a.com/1")));
        when(repository.findByUrl("http://a.com/1")).thenReturn(Collections.emptyList());
        when(configService.getDedupThreshold()).thenReturn(0.9);
        when(similarityService.computeSimHash("正文内容")).thenReturn(200L);
        when(similarityService.getMaxSimilarity(eq(200L), anyList())).thenReturn(0.95);

        OpportunityService.TaskResult result = service.executeSearch(Collections.singletonList("北京市"));

        assertThat(result.getNewCount()).isZero();
        verify(repository, never()).save(any(WebPageInfo.class));
    }

    @Test
    @DisplayName("executeSearch 正文为空时跳过相似度检查直接保存")
    void executeSearch_emptyContent_skipsSimilarity() {
        VolcengineSearchService.SearchResult r = buildSearchResult("", "http://a.com/1");
        r.setContent("");
        when(repository.findAllSimHashes()).thenReturn(new ArrayList<>());
        when(searchService.searchOpportunities("北京市"))
                .thenReturn(Collections.singletonList(r));
        when(repository.findByUrl("http://a.com/1")).thenReturn(Collections.emptyList());
        when(similarityService.computeSimHash("")).thenReturn(null);

        OpportunityService.TaskResult result = service.executeSearch(Collections.singletonList("北京市"));

        assertThat(result.getNewCount()).isEqualTo(1);
        verify(repository).save(any(WebPageInfo.class));
    }

    @Test
    @DisplayName("executeSearch 单城市搜索异常时继续处理其他城市")
    void executeSearch_cityThrows_continuesOthers() {
        when(repository.findAllSimHashes()).thenReturn(new ArrayList<>());
        when(searchService.searchOpportunities("北京市")).thenThrow(new RuntimeException("搜索失败"));
        when(searchService.searchOpportunities("上海市"))
                .thenReturn(Collections.singletonList(buildSearchResult("上海招标", "http://b.com/1")));
        when(repository.findByUrl("http://b.com/1")).thenReturn(Collections.emptyList());
        when(configService.getDedupThreshold()).thenReturn(0.9);
        when(similarityService.computeSimHash("正文内容")).thenReturn(300L);
        when(similarityService.getMaxSimilarity(anyLong(), anyList())).thenReturn(0.1);

        OpportunityService.TaskResult result = service.executeSearch(Arrays.asList("北京市", "上海市"));

        // 北京市失败但上海市成功，新增1条
        assertThat(result.getNewCount()).isEqualTo(1);
        assertThat(result.getTotalCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("executeSearch 线程被中断时跳出循环")
    void executeSearch_interrupted_breaks() {
        when(repository.findAllSimHashes()).thenReturn(new ArrayList<>());
        when(searchService.searchOpportunities("北京市"))
                .thenAnswer(invocation -> {
                    Thread.currentThread().interrupt();
                    return Collections.emptyList();
                });

        OpportunityService.TaskResult result = service.executeSearch(Arrays.asList("北京市", "上海市"));

        // 北京市处理后线程被中断，sleep 抛 InterruptedException → break，不再处理上海市
        verify(searchService, never()).searchOpportunities("上海市");
    }

    @Test
    @DisplayName("executeSearch 截断超长标题和正文")
    void executeSearch_truncatesLongFields() {
        String longTitle = String.join("", Collections.nCopies(600, "a"));
        String longContent = String.join("", Collections.nCopies(11000, "b"));

        VolcengineSearchService.SearchResult r = new VolcengineSearchService.SearchResult();
        r.setTitle(longTitle);
        r.setUrl("http://a.com/1");
        r.setContent(longContent);

        when(repository.findAllSimHashes()).thenReturn(new ArrayList<>());
        when(searchService.searchOpportunities("北京市")).thenReturn(Collections.singletonList(r));
        when(repository.findByUrl("http://a.com/1")).thenReturn(Collections.emptyList());
        // 必须设置阈值，否则 Mockito 对 double 返回默认值 0.0，导致 0.0>=0.0 跳过保存
        when(configService.getDedupThreshold()).thenReturn(0.9);
        when(similarityService.computeSimHash(longContent)).thenReturn(1L);
        when(similarityService.getMaxSimilarity(anyLong(), anyList())).thenReturn(0.0);

        service.executeSearch(Collections.singletonList("北京市"));

        verify(repository).save(argThat(w -> w.getTitle().length() == 500 && w.getContent().length() == 10000));
    }

    // ========== executeAnalysis 测试 ==========

    @Test
    @DisplayName("executeAnalysis 无待分析记录返回 0")
    void executeAnalysis_noPending_returnsZero() {
        when(configService.getAnalysisBatchSize()).thenReturn(10);
        when(repository.countUnscoredRecords()).thenReturn(0L);

        OpportunityService.TaskResult result = service.executeAnalysis();

        assertThat(result.getTotalCount()).isZero();
        assertThat(result.getNewCount()).isZero();
        verify(repository, never()).findUnscoredRecordsPage(any());
    }

    @Test
    @DisplayName("executeAnalysis 正常分批处理")
    void executeAnalysis_normalBatches_processesAll() {
        when(configService.getAnalysisBatchSize()).thenReturn(10);
        when(repository.countUnscoredRecords()).thenReturn(2L);
        List<WebPageInfo> batch = Arrays.asList(
                buildWebPageInfo(1L, "标题1", "url1"),
                buildWebPageInfo(2L, "标题2", "url2"));
        when(repository.findUnscoredRecordsPage(any()))
                .thenReturn(batch)
                .thenReturn(Collections.emptyList());
        when(selfProxy.analyzeAndSaveOneBatch(anyList())).thenReturn(2);

        OpportunityService.TaskResult result = service.executeAnalysis();

        assertThat(result.getTotalCount()).isEqualTo(2);
        assertThat(result.getNewCount()).isEqualTo(2);
        verify(selfProxy, times(1)).analyzeAndSaveOneBatch(anyList());
    }

    @Test
    @DisplayName("executeAnalysis 连续失败 3 次后终止避免死循环")
    void executeAnalysis_consecutiveFailures_terminates() {
        when(configService.getAnalysisBatchSize()).thenReturn(10);
        when(repository.countUnscoredRecords()).thenReturn(100L);
        // 始终返回同一批记录（模拟失败后 analysisStartTime 被清空，下次又被捞到）
        List<WebPageInfo> batch = Collections.singletonList(buildWebPageInfo(1L, "标题", "url"));
        when(repository.findUnscoredRecordsPage(any())).thenReturn(batch);
        when(selfProxy.analyzeAndSaveOneBatch(anyList()))
                .thenThrow(new RuntimeException("大模型故障"));

        OpportunityService.TaskResult result = service.executeAnalysis();

        // 应该在连续失败 3 次后终止
        verify(selfProxy, times(3)).analyzeAndSaveOneBatch(anyList());
        assertThat(result.getNewCount()).isZero();
    }

    @Test
    @DisplayName("executeAnalysis 间歇失败不终止任务（重置计数器）")
    void executeAnalysis_intermittentFailure_continues() {
        when(configService.getAnalysisBatchSize()).thenReturn(10);
        when(repository.countUnscoredRecords()).thenReturn(3L);

        List<WebPageInfo> batch1 = Collections.singletonList(buildWebPageInfo(1L, "标题1", "url1"));
        List<WebPageInfo> batch2 = Collections.singletonList(buildWebPageInfo(2L, "标题2", "url2"));
        List<WebPageInfo> batch3 = Collections.singletonList(buildWebPageInfo(3L, "标题3", "url3"));

        when(repository.findUnscoredRecordsPage(any()))
                .thenReturn(batch1)
                .thenReturn(batch2)
                .thenReturn(batch3)
                .thenReturn(Collections.emptyList());

        // 第1批失败，第2批成功，第3批失败
        when(selfProxy.analyzeAndSaveOneBatch(anyList()))
                .thenThrow(new RuntimeException("失败1"))
                .thenReturn(1)
                .thenThrow(new RuntimeException("失败3"));

        OpportunityService.TaskResult result = service.executeAnalysis();

        // 间歇失败不会累计到 3 次，任务会继续直到批次为空
        verify(selfProxy, times(3)).analyzeAndSaveOneBatch(anyList());
        assertThat(result.getNewCount()).isEqualTo(1);
    }

    // ========== analyzeAndSaveOneBatch 测试 ==========

    @Test
    @DisplayName("analyzeAndSaveOneBatch 大模型调用失败时清空 analysisStartTime 并抛异常")
    void analyzeAndSaveOneBatch_llmThrows_clearsStartTime() throws Exception {
        List<WebPageInfo> batch = Collections.singletonList(buildWebPageInfo(1L, "标题", "url"));
        when(analysisService.analyzeOneBatch(anyList())).thenThrow(new RuntimeException("LLM 500"));

        try {
            service.analyzeAndSaveOneBatch(batch);
        } catch (RuntimeException e) {
            // 预期抛出
        }

        // 验证开始时间被清空
        assertThat(batch.get(0).getAnalysisStartTime()).isNull();
        verify(repository, times(2)).saveAll(anyList()); // 1次标记 + 1次回滚
    }

    @Test
    @DisplayName("analyzeAndSaveOneBatch 正常写入评分结果")
    void analyzeAndSaveOneBatch_normal_writesScores() throws Exception {
        WebPageInfo r1 = buildWebPageInfo(1L, "标题1", "url1");
        List<WebPageInfo> batch = Collections.singletonList(r1);

        LlmAnalysisService.AnalysisResult result = new LlmAnalysisService.AnalysisResult();
        result.setRecordId(1L);
        result.setProvince("北京市");
        result.setCity("北京市");
        result.setCounty("海淀区");
        result.setDeadline("20250630");
        result.setAmount("500万");
        result.setTypeFlag(true);
        result.setScore(85);
        result.setScoreReason("高分");
        result.setType("政府文件");

        when(analysisService.analyzeOneBatch(anyList())).thenReturn(Collections.singletonList(result));

        int count = service.analyzeAndSaveOneBatch(batch);

        assertThat(count).isEqualTo(1);
        assertThat(r1.getProvince()).isEqualTo("北京市");
        assertThat(r1.getScore()).isEqualTo(85);
        assertThat(r1.getAnalysisStartTime()).isNotNull();
        assertThat(r1.getAnalysisEndTime()).isNotNull();
        verify(repository, times(2)).saveAll(anyList()); // 1次标记 + 1次落表
    }

    @Test
    @DisplayName("analyzeAndSaveOneBatch 大模型未返回部分记录时清空这些记录的 analysisStartTime")
    void analyzeAndSaveOneBatch_partialResults_clearsMissing() throws Exception {
        WebPageInfo r1 = buildWebPageInfo(1L, "标题1", "url1");
        WebPageInfo r2 = buildWebPageInfo(2L, "标题2", "url2");
        List<WebPageInfo> batch = Arrays.asList(r1, r2);

        // 只返回 r1 的结果
        LlmAnalysisService.AnalysisResult result = new LlmAnalysisService.AnalysisResult();
        result.setRecordId(1L);
        result.setScore(70);
        result.setType("政府文件");

        when(analysisService.analyzeOneBatch(anyList())).thenReturn(Collections.singletonList(result));

        int count = service.analyzeAndSaveOneBatch(batch);

        assertThat(count).isEqualTo(1);
        assertThat(r1.getScore()).isEqualTo(70);
        assertThat(r1.getAnalysisStartTime()).isNotNull();
        // r2 未在结果中，开始时间应被清空
        assertThat(r2.getAnalysisStartTime()).isNull();
    }

    @Test
    @DisplayName("analyzeAndSaveOneBatch 大模型返回的 recordId 不匹配时该结果被忽略")
    void analyzeAndSaveOneBatch_unknownRecordId_ignored() throws Exception {
        WebPageInfo r1 = buildWebPageInfo(1L, "标题1", "url1");
        List<WebPageInfo> batch = Collections.singletonList(r1);

        // recordId 不匹配（返回 999，但 batch 中是 1）
        LlmAnalysisService.AnalysisResult result = new LlmAnalysisService.AnalysisResult();
        result.setRecordId(999L);
        result.setScore(80);

        when(analysisService.analyzeOneBatch(anyList())).thenReturn(Collections.singletonList(result));

        int count = service.analyzeAndSaveOneBatch(batch);

        assertThat(count).isZero(); // 不匹配的结果不计入
        assertThat(r1.getScore()).isNull(); // r1 未被更新
        assertThat(r1.getAnalysisStartTime()).isNull(); // 被清空
    }

    // ========== 统计与查询接口 ==========

    @Test
    @DisplayName("getStatistics 返回完整统计信息")
    void getStatistics_returnsAllFields() {
        when(repository.count()).thenReturn(10L);
        when(repository.countByScoreIsNotNull()).thenReturn(3L);
        when(repository.countByCity()).thenReturn(Collections.singletonList(new Object[]{"北京市", 5L}));
        when(repository.countByType()).thenReturn(Collections.singletonList(new Object[]{"政府文件", 3L}));

        Map<String, Object> stats = service.getStatistics();

        assertThat(stats).containsKeys("totalCount", "scoredCount", "unscoredCount", "cityDistribution", "typeDistribution");
        assertThat(stats.get("totalCount")).isEqualTo(10L);
        assertThat(stats.get("scoredCount")).isEqualTo(3L);
        assertThat(stats.get("unscoredCount")).isEqualTo(7L);
    }

    @Test
    @DisplayName("getRecentRecords 调用 repository 查询")
    void getRecentRecords_delegatesToRepository() {
        List<WebPageInfo> mockRecords = Collections.singletonList(buildWebPageInfo(1L, "标题", "url"));
        when(repository.findRecentRecords()).thenReturn(mockRecords);

        List<WebPageInfo> result = service.getRecentRecords();
        assertThat(result).isSameAs(mockRecords);
    }

    // ========== TaskResult 内部类 ==========

    @Test
    @DisplayName("TaskResult getter 返回正确值")
    void taskResult_getters_returnCorrectValues() {
        OpportunityService.TaskResult tr = new OpportunityService.TaskResult(100, 50);
        assertThat(tr.getTotalCount()).isEqualTo(100);
        assertThat(tr.getNewCount()).isEqualTo(50);
    }
}
