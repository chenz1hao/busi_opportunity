package com.opportunity.repository;

import com.opportunity.entity.SchedulerLog;
import com.opportunity.entity.SystemConfig;
import com.opportunity.entity.WebPageInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Repository 层测试
 * 使用 @DataJpaTest + H2 内存数据库，验证自定义查询方法
 */
@DataJpaTest
@ActiveProfiles("test")
class RepositoryTest {

    @Autowired
    private SystemConfigRepository systemRepo;
    @Autowired
    private SchedulerLogRepository schedulerLogRepo;
    @Autowired
    private WebPageInfoRepository webPageRepo;

    @BeforeEach
    void cleanUp() {
        webPageRepo.deleteAll();
        schedulerLogRepo.deleteAll();
        systemRepo.deleteAll();
    }

    // ========== SystemConfigRepository ==========

    @Test
    @DisplayName("findByConfigKey 根据配置键查询")
    void findByConfigKey_returnsConfig() {
        SystemConfig c = new SystemConfig();
        c.setConfigKey("test.key");
        c.setConfigValue("test-value");
        c.setDescription("desc");
        systemRepo.save(c);

        Optional<SystemConfig> result = systemRepo.findByConfigKey("test.key");
        assertThat(result).isPresent();
        assertThat(result.get().getConfigValue()).isEqualTo("test-value");
    }

    @Test
    @DisplayName("SystemConfig 的 @PrePersist/@PreUpdate 回调填充 updatedTime")
    void systemConfig_prePersist_fillsTimestamps() {
        SystemConfig c = new SystemConfig();
        c.setConfigKey("k");
        c.setConfigValue("v");
        c.setDescription("d");
        systemRepo.save(c);

        SystemConfig saved = systemRepo.findById(c.getId()).orElse(null);
        assertThat(saved).isNotNull();
        assertThat(saved.getUpdatedTime()).isNotNull();
    }

    // ========== SchedulerLogRepository ==========

    @Test
    @DisplayName("findTop10ByOrderByStartTimeDesc 返回最近10条按开始时间倒序")
    void findTop10ByOrderByStartTimeDesc_returnsLatest10() {
        for (int i = 0; i < 15; i++) {
            SchedulerLog log = new SchedulerLog();
            log.setTaskName("SEARCH_CRAWL");
            log.setStartTime(LocalDateTime.now().minusMinutes(15 - i));
            log.setStatus("SUCCESS");
            schedulerLogRepo.save(log);
        }

        List<SchedulerLog> result = schedulerLogRepo.findTop10ByOrderByStartTimeDesc();
        assertThat(result).hasSize(10);
        // 第一条应该是最新（startTime 最大）
        assertThat(result.get(0).getStartTime()).isAfterOrEqualTo(result.get(9).getStartTime());
    }

    @Test
    @DisplayName("findByTaskNameOrderByStartTimeDesc 按任务名查询并按开始时间倒序")
    void findByTaskNameOrderByStartTimeDesc_filtersByTaskName() {
        SchedulerLog l1 = new SchedulerLog();
        l1.setTaskName("SEARCH_CRAWL");
        l1.setStartTime(LocalDateTime.now().minusMinutes(10));
        l1.setStatus("SUCCESS");
        schedulerLogRepo.save(l1);

        SchedulerLog l2 = new SchedulerLog();
        l2.setTaskName("SCORE_ANALYSIS");
        l2.setStartTime(LocalDateTime.now());
        l2.setStatus("SUCCESS");
        schedulerLogRepo.save(l2);

        List<SchedulerLog> searchLogs = schedulerLogRepo.findByTaskNameOrderByStartTimeDesc("SEARCH_CRAWL");
        assertThat(searchLogs).hasSize(1);
        assertThat(searchLogs.get(0).getTaskName()).isEqualTo("SEARCH_CRAWL");
    }

    @Test
    @DisplayName("existsByTaskNameAndStatus 检查任务名+状态是否存在")
    void existsByTaskNameAndStatus_checksCorrectly() {
        SchedulerLog log = new SchedulerLog();
        log.setTaskName("SEARCH_CRAWL");
        log.setStartTime(LocalDateTime.now());
        log.setStatus("RUNNING");
        schedulerLogRepo.save(log);

        assertThat(schedulerLogRepo.existsByTaskNameAndStatus("SEARCH_CRAWL", "RUNNING")).isTrue();
        assertThat(schedulerLogRepo.existsByTaskNameAndStatus("SEARCH_CRAWL", "SUCCESS")).isFalse();
        assertThat(schedulerLogRepo.existsByTaskNameAndStatus("SCORE_ANALYSIS", "RUNNING")).isFalse();
    }

    @Test
    @DisplayName("SchedulerLog 的 @PrePersist 回调填充 createdTime")
    void schedulerLog_prePersist_fillsCreatedTime() {
        SchedulerLog log = new SchedulerLog();
        log.setTaskName("TEST");
        log.setStartTime(LocalDateTime.now());
        log.setStatus("RUNNING");
        schedulerLogRepo.save(log);

        SchedulerLog saved = schedulerLogRepo.findById(log.getId()).orElse(null);
        assertThat(saved).isNotNull();
        assertThat(saved.getCreatedTime()).isNotNull();
    }

    // ========== WebPageInfoRepository ==========

    private WebPageInfo buildRecord(String title, String url, String city, Integer score) {
        WebPageInfo w = new WebPageInfo();
        w.setTitle(title);
        w.setUrl(url);
        w.setSourceCity(city);
        w.setScore(score);
        w.setContent("content");
        return w;
    }

    @Test
    @DisplayName("findAllSimHashes 返回非空的 SimHash 指纹列表")
    void findAllSimHashes_returnsNonNullHashes() {
        WebPageInfo r1 = buildRecord("标题1", "url1", "北京市", null);
        r1.setSimHash(111L);
        webPageRepo.save(r1);

        WebPageInfo r2 = buildRecord("标题2", "url2", "上海市", 80);
        r2.setSimHash(222L);
        webPageRepo.save(r2);

        // 未设置 simHash 的记录应被跳过
        webPageRepo.save(buildRecord("标题3", "url3", "成都市", null));

        List<Long> hashes = webPageRepo.findAllSimHashes();
        assertThat(hashes).containsExactlyInAnyOrder(111L, 222L);
    }

    @Test
    @DisplayName("findUnscoredRecords 返回 score 和 analysisStartTime 均为空的记录")
    void findUnscoredRecords_returnsPending() {
        WebPageInfo r1 = buildRecord("未评分", "url1", "北京市", null);
        r1.setAnalysisStartTime(null);
        webPageRepo.save(r1);

        WebPageInfo r2 = buildRecord("已评分", "url2", "上海市", 80);
        webPageRepo.save(r2);

        WebPageInfo r3 = buildRecord("分析中", "url3", "广州市", null);
        r3.setAnalysisStartTime(LocalDateTime.now());
        webPageRepo.save(r3);

        List<WebPageInfo> result = webPageRepo.findUnscoredRecords();
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getTitle()).isEqualTo("未评分");
    }

    @Test
    @DisplayName("findUnscoredRecordsPage 分页查询未评分记录")
    void findUnscoredRecordsPage_returnsPage() {
        for (int i = 0; i < 5; i++) {
            webPageRepo.save(buildRecord("标题" + i, "url" + i, "北京市", null));
        }

        List<WebPageInfo> page1 = webPageRepo.findUnscoredRecordsPage(PageRequest.of(0, 2));
        assertThat(page1).hasSize(2);
    }

    @Test
    @DisplayName("countUnscoredRecords 统计未评分且未开始分析的记录数")
    void countUnscoredRecords_returnsCount() {
        webPageRepo.save(buildRecord("未评分1", "url1", "北京市", null));
        webPageRepo.save(buildRecord("未评分2", "url2", "北京市", null));
        webPageRepo.save(buildRecord("已评分", "url3", "北京市", 80));

        long count = webPageRepo.countUnscoredRecords();
        assertThat(count).isEqualTo(2);
    }

    @Test
    @DisplayName("findByUrl 返回所有匹配 URL 的记录（含历史重复数据）")
    void findByUrl_returnsAllMatches() {
        webPageRepo.save(buildRecord("标题1", "dup-url", "北京市", null));
        webPageRepo.save(buildRecord("标题2", "dup-url", "北京市", null)); // 历史 URL 重复

        List<WebPageInfo> result = webPageRepo.findByUrl("dup-url");
        assertThat(result).hasSize(2);
    }

    @Test
    @DisplayName("findByUrl 无匹配返回空列表")
    void findByUrl_noMatch_returnsEmpty() {
        webPageRepo.save(buildRecord("标题1", "url1", "北京市", null));
        assertThat(webPageRepo.findByUrl("not-exists")).isEmpty();
    }

    @Test
    @DisplayName("findBySourceCity 按城市查询")
    void findBySourceCity_returnsByCity() {
        webPageRepo.save(buildRecord("标题1", "url1", "北京市", null));
        webPageRepo.save(buildRecord("标题2", "url2", "上海市", null));

        List<WebPageInfo> result = webPageRepo.findBySourceCity("北京市");
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getSourceCity()).isEqualTo("北京市");
    }

    @Test
    @DisplayName("findByScoreBetween 按评分区间查询")
    void findByScoreBetween_returnsByRange() {
        webPageRepo.save(buildRecord("标题1", "url1", "北京市", 60));
        webPageRepo.save(buildRecord("标题2", "url2", "北京市", 80));
        webPageRepo.save(buildRecord("标题3", "url3", "北京市", 95));

        List<WebPageInfo> result = webPageRepo.findByScoreBetween(70, 90);
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getScore()).isEqualTo(80);
    }

    @Test
    @DisplayName("countByScoreIsNotNull 统计已评分记录数")
    void countByScoreIsNotNull_returnsCount() {
        webPageRepo.save(buildRecord("标题1", "url1", "北京市", 60));
        webPageRepo.save(buildRecord("标题2", "url2", "北京市", 80));
        webPageRepo.save(buildRecord("标题3", "url3", "北京市", null));

        assertThat(webPageRepo.countByScoreIsNotNull()).isEqualTo(2L);
    }

    @Test
    @DisplayName("countByCity 按城市分组统计")
    void countByCity_returnsGrouped() {
        webPageRepo.save(buildRecord("标题1", "url1", "北京市", null));
        webPageRepo.save(buildRecord("标题2", "url2", "北京市", null));
        webPageRepo.save(buildRecord("标题3", "url3", "上海市", null));

        List<Object[]> result = webPageRepo.countByCity();
        assertThat(result).hasSize(2);
    }

    @Test
    @DisplayName("countByType 按类型分组统计（仅含 type 非空的记录）")
    void countByType_returnsGrouped() {
        WebPageInfo r1 = buildRecord("标题1", "url1", "北京市", 60);
        r1.setType("政府文件");
        webPageRepo.save(r1);

        WebPageInfo r2 = buildRecord("标题2", "url2", "北京市", 80);
        r2.setType("政府文件");
        webPageRepo.save(r2);

        WebPageInfo r3 = buildRecord("标题3", "url3", "北京市", null);
        // type 为空，不会被统计
        webPageRepo.save(r3);

        List<Object[]> result = webPageRepo.countByType();
        assertThat(result).hasSize(1);
        assertThat(result.get(0)[0]).isEqualTo("政府文件");
    }

    @Test
    @DisplayName("findRecentRecords 返回最近记录列表")
    void findRecentRecords_returnsList() {
        webPageRepo.save(buildRecord("标题1", "url1", "北京市", null));
        webPageRepo.save(buildRecord("标题2", "url2", "北京市", null));

        List<WebPageInfo> result = webPageRepo.findRecentRecords();
        assertThat(result).hasSize(2);
    }

    @Test
    @DisplayName("WebPageInfo 的 @PrePersist 回调填充 createdTime 和 updatedTime")
    void webPageInfo_prePersist_fillsTimestamps() {
        WebPageInfo w = buildRecord("标题", "url", "北京市", null);
        webPageRepo.save(w);

        WebPageInfo saved = webPageRepo.findById(w.getId()).orElse(null);
        assertThat(saved).isNotNull();
        assertThat(saved.getCreatedTime()).isNotNull();
        assertThat(saved.getUpdatedTime()).isNotNull();
    }
}

