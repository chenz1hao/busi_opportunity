package com.opportunity.controller;

import com.opportunity.entity.CityConfig;
import com.opportunity.entity.SchedulerLog;
import com.opportunity.entity.SystemConfig;
import com.opportunity.entity.WebPageInfo;
import com.opportunity.repository.SchedulerLogRepository;
import com.opportunity.repository.WebPageInfoRepository;
import com.opportunity.service.AsyncTaskExecutor;
import com.opportunity.service.ConfigService;
import com.opportunity.service.OpportunityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.*;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 商机信息控制器测试
 * 使用 @WebMvcTest + MockBean 隔离 Service 层
 * 覆盖：统计、记录查询、手动触发（重复任务校验）、城市配置 CRUD、系统配置 CRUD
 */
@ExtendWith(SpringExtension.class)
@WebMvcTest(OpportunityController.class)
class OpportunityControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private WebPageInfoRepository webPageInfoRepository;
    @MockBean
    private OpportunityService opportunityService;
    @MockBean
    private SchedulerLogRepository schedulerLogRepository;
    @MockBean
    private ConfigService configService;
    @MockBean
    private AsyncTaskExecutor asyncTaskExecutor;

    private WebPageInfo buildRecord(Long id, String title, String url, Integer score, String type, String city) {
        WebPageInfo r = new WebPageInfo();
        r.setId(id);
        r.setTitle(title);
        r.setUrl(url);
        r.setScore(score);
        r.setType(type);
        r.setSourceCity(city);
        r.setCreatedTime(LocalDateTime.now());
        return r;
    }

    // ========== 统计接口 ==========

    @Test
    @DisplayName("GET /api/statistics 返回统计数据")
    void getStatistics_returnsData() throws Exception {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalCount", 10);
        stats.put("scoredCount", 5);
        when(opportunityService.getStatistics()).thenReturn(stats);

        mockMvc.perform(get("/api/statistics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(10))
                .andExpect(jsonPath("$.scoredCount").value(5));
    }

    // ========== 记录查询接口 ==========

    @Test
    @DisplayName("GET /api/records 默认分页返回记录列表")
    void getRecords_defaultPagination_returnsList() throws Exception {
        List<WebPageInfo> records = Arrays.asList(
                buildRecord(1L, "标题1", "url1", 80, "政府文件", "北京市"),
                buildRecord(2L, "标题2", "url2", null, null, "上海市"));
        when(webPageInfoRepository.findAll(any(Sort.class))).thenReturn(records);

        mockMvc.perform(get("/api/records"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].title").value("标题1"));
    }

    @Test
    @DisplayName("GET /api/records 按 city 过滤")
    void getRecords_filterByCity() throws Exception {
        List<WebPageInfo> records = Arrays.asList(
                buildRecord(1L, "标题1", "url1", 80, "政府文件", "北京市"),
                buildRecord(2L, "标题2", "url2", 60, "新闻", "上海市"));
        when(webPageInfoRepository.findAll(any(Sort.class))).thenReturn(records);

        mockMvc.perform(get("/api/records").param("city", "北京市"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].sourceCity").value("北京市"));
    }

    @Test
    @DisplayName("GET /api/records 按 minScore 过滤（过滤掉 null 分数）")
    void getRecords_filterByMinScore() throws Exception {
        List<WebPageInfo> records = Arrays.asList(
                buildRecord(1L, "标题1", "url1", 80, "政府文件", "北京市"),
                buildRecord(2L, "标题2", "url2", null, null, "上海市"));
        when(webPageInfoRepository.findAll(any(Sort.class))).thenReturn(records);

        mockMvc.perform(get("/api/records").param("minScore", "70"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].score").value(80));
    }

    @Test
    @DisplayName("GET /api/records 按 maxScore 过滤")
    void getRecords_filterByMaxScore() throws Exception {
        List<WebPageInfo> records = Arrays.asList(
                buildRecord(1L, "标题1", "url1", 80, "政府文件", "北京市"),
                buildRecord(2L, "标题2", "url2", 60, "新闻", "上海市"));
        when(webPageInfoRepository.findAll(any(Sort.class))).thenReturn(records);

        mockMvc.perform(get("/api/records").param("maxScore", "70"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].score").value(60));
    }

    @Test
    @DisplayName("GET /api/records 按 type 过滤")
    void getRecords_filterByType() throws Exception {
        List<WebPageInfo> records = Arrays.asList(
                buildRecord(1L, "标题1", "url1", 80, "政府文件", "北京市"),
                buildRecord(2L, "标题2", "url2", 60, "新闻", "上海市"));
        when(webPageInfoRepository.findAll(any(Sort.class))).thenReturn(records);

        mockMvc.perform(get("/api/records").param("type", "新闻"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].type").value("新闻"));
    }

    @Test
    @DisplayName("GET /api/records 按 keyword 过滤（标题包含）")
    void getRecords_filterByKeywordInTitle() throws Exception {
        WebPageInfo r1 = buildRecord(1L, "招标公告", "url1", 80, "政府文件", "北京市");
        r1.setContent("正文内容");
        WebPageInfo r2 = buildRecord(2L, "采购公告", "url2", 60, "新闻", "上海市");
        r2.setContent("其他内容");
        when(webPageInfoRepository.findAll(any(Sort.class))).thenReturn(Arrays.asList(r1, r2));

        mockMvc.perform(get("/api/records").param("keyword", "招标"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].title").value("招标公告"));
    }

    @Test
    @DisplayName("GET /api/records 按 keyword 过滤（正文包含）")
    void getRecords_filterByKeywordInContent() throws Exception {
        WebPageInfo r1 = buildRecord(1L, "标题1", "url1", 80, "政府文件", "北京市");
        r1.setContent("特殊关键词");
        WebPageInfo r2 = buildRecord(2L, "标题2", "url2", 60, "新闻", "上海市");
        r2.setContent("普通内容");
        when(webPageInfoRepository.findAll(any(Sort.class))).thenReturn(Arrays.asList(r1, r2));

        mockMvc.perform(get("/api/records").param("keyword", "特殊关键词"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].title").value("标题1"));
    }

    @Test
    @DisplayName("GET /api/records 分页超出范围返回空列表")
    void getRecords_pageOutOfRange_returnsEmpty() throws Exception {
        List<WebPageInfo> records = Collections.singletonList(
                buildRecord(1L, "标题1", "url1", 80, "政府文件", "北京市"));
        when(webPageInfoRepository.findAll(any(Sort.class))).thenReturn(records);

        mockMvc.perform(get("/api/records").param("page", "10").param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isEmpty())
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    @DisplayName("GET /api/records/{id} 存在时返回记录")
    void getRecord_exists_returnsRecord() throws Exception {
        WebPageInfo r = buildRecord(1L, "标题", "url", 80, "政府文件", "北京市");
        when(webPageInfoRepository.findById(1L)).thenReturn(Optional.of(r));

        mockMvc.perform(get("/api/records/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("标题"));
    }

    @Test
    @DisplayName("GET /api/records/{id} 不存在时返回 404")
    void getRecord_notExists_returns404() throws Exception {
        when(webPageInfoRepository.findById(999L)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/records/999"))
                .andExpect(status().isNotFound());
    }

    // ========== 手动触发接口 ==========

    @Test
    @DisplayName("POST /api/trigger/search 无运行中任务时下发成功")
    void triggerSearch_noRunningTask_dispatches() throws Exception {
        when(schedulerLogRepository.existsByTaskNameAndStatus("SEARCH_CRAWL", "RUNNING")).thenReturn(false);
        when(configService.getActiveCityNames()).thenReturn(Collections.singletonList("北京市"));

        mockMvc.perform(post("/api/trigger/search").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("success"))
                .andExpect(jsonPath("$.message").value("搜索任务已下发，请稍后查看"));

        verify(asyncTaskExecutor).executeSearchAsync(anyList());
    }

    @Test
    @DisplayName("POST /api/trigger/search body 为 null 时取默认城市")
    void triggerSearch_nullBody_usesDefaultCities() throws Exception {
        when(schedulerLogRepository.existsByTaskNameAndStatus("SEARCH_CRAWL", "RUNNING")).thenReturn(false);
        when(configService.getActiveCityNames()).thenReturn(Collections.singletonList("北京市"));

        mockMvc.perform(post("/api/trigger/search"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("success"));

        verify(asyncTaskExecutor).executeSearchAsync(Collections.singletonList("北京市"));
    }

    @Test
    @DisplayName("POST /api/trigger/search body 中 cities 为空时取默认城市")
    void triggerSearch_emptyCities_usesDefault() throws Exception {
        when(schedulerLogRepository.existsByTaskNameAndStatus("SEARCH_CRAWL", "RUNNING")).thenReturn(false);
        when(configService.getActiveCityNames()).thenReturn(Collections.singletonList("北京市"));

        mockMvc.perform(post("/api/trigger/search").contentType(MediaType.APPLICATION_JSON).content("{\"cities\":[]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("success"));

        verify(asyncTaskExecutor).executeSearchAsync(Collections.singletonList("北京市"));
    }

    @Test
    @DisplayName("POST /api/trigger/search 有运行中任务时返回 error")
    void triggerSearch_runningTask_returnsError() throws Exception {
        when(schedulerLogRepository.existsByTaskNameAndStatus("SEARCH_CRAWL", "RUNNING")).thenReturn(true);

        mockMvc.perform(post("/api/trigger/search").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("error"))
                .andExpect(jsonPath("$.message").value("当前已有搜索任务正在执行中，请稍后再试"));

        verify(asyncTaskExecutor, never()).executeSearchAsync(anyList());
    }

    @Test
    @DisplayName("POST /api/trigger/analysis 无运行中任务时下发成功")
    void triggerAnalysis_noRunningTask_dispatches() throws Exception {
        when(schedulerLogRepository.existsByTaskNameAndStatus("SCORE_ANALYSIS", "RUNNING")).thenReturn(false);

        mockMvc.perform(post("/api/trigger/analysis"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("success"))
                .andExpect(jsonPath("$.message").value("分析任务已下发，请稍后查看"));

        verify(asyncTaskExecutor).executeAnalysisAsync();
    }

    @Test
    @DisplayName("POST /api/trigger/analysis 有运行中任务时返回 error")
    void triggerAnalysis_runningTask_returnsError() throws Exception {
        when(schedulerLogRepository.existsByTaskNameAndStatus("SCORE_ANALYSIS", "RUNNING")).thenReturn(true);

        mockMvc.perform(post("/api/trigger/analysis"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("error"))
                .andExpect(jsonPath("$.message").value("当前已有分析任务正在执行中，请稍后再试"));

        verify(asyncTaskExecutor, never()).executeAnalysisAsync();
    }

    // ========== 配置接口 ==========

    @Test
    @DisplayName("GET /api/config/cities 返回启用城市名列表")
    void getCities_returnsActiveNames() throws Exception {
        when(configService.getActiveCityNames()).thenReturn(Arrays.asList("北京市", "上海市"));

        mockMvc.perform(get("/api/config/cities"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0]").value("北京市"))
                .andExpect(jsonPath("$[1]").value("上海市"));
    }

    @Test
    @DisplayName("GET /api/config/cities/all 返回所有城市配置")
    void getAllCities_returnsAll() throws Exception {
        CityConfig c = new CityConfig();
        c.setId(1L);
        c.setCityName("北京市");
        c.setEnabled(true);
        c.setSortOrder(0);
        when(configService.getAllCities()).thenReturn(Collections.singletonList(c));

        mockMvc.perform(get("/api/config/cities/all"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].cityName").value("北京市"));
    }

    @Test
    @DisplayName("POST /api/config/cities 新增城市成功")
    void addCity_normal_returns200() throws Exception {
        CityConfig c = new CityConfig();
        c.setId(1L);
        c.setCityName("新城市");
        c.setEnabled(true);
        when(configService.addCity("新城市")).thenReturn(c);

        mockMvc.perform(post("/api/config/cities").contentType(MediaType.APPLICATION_JSON)
                .content("{\"cityName\":\"新城市\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cityName").value("新城市"));
    }

    @Test
    @DisplayName("POST /api/config/cities 城市名重复返回 400")
    void addCity_duplicate_returns400() throws Exception {
        when(configService.addCity(any())).thenThrow(new IllegalStateException("城市已存在: 新城市"));

        mockMvc.perform(post("/api/config/cities").contentType(MediaType.APPLICATION_JSON)
                .content("{\"cityName\":\"新城市\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("error"))
                .andExpect(jsonPath("$.message").value("城市已存在: 新城市"));
    }

    @Test
    @DisplayName("POST /api/config/cities 城市名为空返回 400")
    void addCity_emptyName_returns400() throws Exception {
        when(configService.addCity(any())).thenThrow(new IllegalArgumentException("城市名不能为空"));

        mockMvc.perform(post("/api/config/cities").contentType(MediaType.APPLICATION_JSON)
                .content("{\"cityName\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("城市名不能为空"));
    }

    @Test
    @DisplayName("PUT /api/config/cities/{id} 改名成功")
    void updateCity_rename_success() throws Exception {
        CityConfig c = new CityConfig();
        c.setId(1L);
        c.setCityName("新名字");
        when(configService.updateCity(eq(1L), eq("新名字"), any())).thenReturn(c);

        mockMvc.perform(put("/api/config/cities/1").contentType(MediaType.APPLICATION_JSON)
                .content("{\"cityName\":\"新名字\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cityName").value("新名字"));
    }

    @Test
    @DisplayName("PUT /api/config/cities/{id} 启停状态切换（enabled 为 Boolean）")
    void updateCity_toggleEnabled_boolean_success() throws Exception {
        CityConfig c = new CityConfig();
        c.setId(1L);
        c.setEnabled(false);
        when(configService.updateCity(eq(1L), isNull(), eq(false))).thenReturn(c);

        mockMvc.perform(put("/api/config/cities/1").contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));
    }

    @Test
    @DisplayName("PUT /api/config/cities/{id} enabled 为字符串 'true' 时解析为 Boolean")
    void updateCity_enabledString_true_parsed() throws Exception {
        CityConfig c = new CityConfig();
        c.setId(1L);
        c.setEnabled(true);
        when(configService.updateCity(eq(1L), isNull(), eq(true))).thenReturn(c);

        mockMvc.perform(put("/api/config/cities/1").contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":\"true\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true));
    }

    @Test
    @DisplayName("PUT /api/config/cities/{id} 城市不存在返回 400")
    void updateCity_notFound_returns400() throws Exception {
        when(configService.updateCity(eq(999L), any(), any()))
                .thenThrow(new IllegalArgumentException("城市不存在: id=999"));

        mockMvc.perform(put("/api/config/cities/999").contentType(MediaType.APPLICATION_JSON)
                .content("{\"cityName\":\"x\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("城市不存在: id=999"));
    }

    @Test
    @DisplayName("DELETE /api/config/cities/{id} 删除成功")
    void deleteCity_normal_success() throws Exception {
        doNothing().when(configService).deleteCity(1L);

        mockMvc.perform(delete("/api/config/cities/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("success"));
    }

    @Test
    @DisplayName("DELETE /api/config/cities/{id} 不存在返回 400")
    void deleteCity_notFound_returns400() throws Exception {
        doThrow(new IllegalArgumentException("城市不存在: id=999")).when(configService).deleteCity(999L);

        mockMvc.perform(delete("/api/config/cities/999"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("城市不存在: id=999"));
    }

    @Test
    @DisplayName("GET /api/config/info 返回配置信息")
    void getConfigInfo_returnsInfo() throws Exception {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("analysisModel", "doubao-test");
        info.put("dedupThreshold", 0.9);
        when(configService.getConfigInfo()).thenReturn(info);

        mockMvc.perform(get("/api/config/info"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.analysisModel").value("doubao-test"));
    }

    @Test
    @DisplayName("GET /api/config/system 返回所有系统配置")
    void getAllSystemConfigs_returnsAll() throws Exception {
        SystemConfig c = new SystemConfig();
        c.setId(1L);
        c.setConfigKey("k");
        c.setConfigValue("v");
        when(configService.getAllSystemConfigs()).thenReturn(Collections.singletonList(c));

        mockMvc.perform(get("/api/config/system"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].configKey").value("k"));
    }

    @Test
    @DisplayName("PUT /api/config/system 更新系统配置成功")
    void updateSystemConfig_normal_success() throws Exception {
        SystemConfig c = new SystemConfig();
        c.setId(1L);
        c.setConfigKey("k");
        c.setConfigValue("new");
        when(configService.updateSystemConfig("k", "new")).thenReturn(c);

        mockMvc.perform(put("/api/config/system").contentType(MediaType.APPLICATION_JSON)
                .content("{\"configKey\":\"k\",\"configValue\":\"new\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configValue").value("new"));
    }

    @Test
    @DisplayName("PUT /api/config/system configKey 为空返回 400")
    void updateSystemConfig_emptyKey_returns400() throws Exception {
        mockMvc.perform(put("/api/config/system").contentType(MediaType.APPLICATION_JSON)
                .content("{\"configKey\":\"\",\"configValue\":\"v\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("configKey 不能为空"));
    }

    @Test
    @DisplayName("PUT /api/config/system configKey 为 null 返回 400")
    void updateSystemConfig_nullKey_returns400() throws Exception {
        mockMvc.perform(put("/api/config/system").contentType(MediaType.APPLICATION_JSON)
                .content("{\"configValue\":\"v\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("configKey 不能为空"));
    }

    @Test
    @DisplayName("PUT /api/config/system 配置项不存在返回 400")
    void updateSystemConfig_notExists_returns400() throws Exception {
        when(configService.updateSystemConfig(any(), any()))
                .thenThrow(new IllegalArgumentException("配置项不存在: not-exists"));

        mockMvc.perform(put("/api/config/system").contentType(MediaType.APPLICATION_JSON)
                .content("{\"configKey\":\"not-exists\",\"configValue\":\"v\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("配置项不存在: not-exists"));
    }

    @Test
    @DisplayName("GET /api/config/scheduler-logs 返回调度日志列表")
    void getSchedulerLogs_returnsList() throws Exception {
        SchedulerLog log = new SchedulerLog();
        log.setId(1L);
        log.setTaskName("SEARCH_CRAWL");
        log.setStatus("SUCCESS");
        when(schedulerLogRepository.findTop10ByOrderByStartTimeDesc()).thenReturn(Collections.singletonList(log));

        mockMvc.perform(get("/api/config/scheduler-logs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].taskName").value("SEARCH_CRAWL"))
                .andExpect(jsonPath("$[0].status").value("SUCCESS"));
    }

    // ========== 城市统计接口 ==========

    @Test
    @DisplayName("GET /api/statistics/by-city 返回按数量降序排序的城市统计")
    void getCityStatistics_returnsSorted() throws Exception {
        // 注意：H2 返回的 count 是 Long 类型
        when(webPageInfoRepository.countByCity()).thenReturn(Arrays.asList(
                new Object[]{"北京市", 10L},
                new Object[]{"上海市", 20L}));

        mockMvc.perform(get("/api/statistics/by-city"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].city").value("上海市")) // 20 排在前
                .andExpect(jsonPath("$[0].count").value(20))
                .andExpect(jsonPath("$[1].city").value("北京市"))
                .andExpect(jsonPath("$[1].count").value(10));
    }

    @Test
    @DisplayName("GET /api/statistics/by-type 返回类型统计")
    void getTypeStatistics_returnsList() throws Exception {
        when(webPageInfoRepository.countByType()).thenReturn(Collections.singletonList(
                new Object[]{"政府文件", 5L}));

        mockMvc.perform(get("/api/statistics/by-type"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].type").value("政府文件"))
                .andExpect(jsonPath("$[0].count").value(5));
    }

    // ========== 补充分支覆盖测试 ==========

    @Test
    @DisplayName("GET /api/records minScore 过滤掉低于阈值的记录（覆盖 score < minScore true 分支）")
    void getRecords_filterByMinScore_lowScoreFiltered() throws Exception {
        List<WebPageInfo> records = Arrays.asList(
                buildRecord(1L, "高分", "url1", 80, "政府文件", "北京市"),
                buildRecord(2L, "低分", "url2", 50, "新闻", "上海市"));
        when(webPageInfoRepository.findAll(any(Sort.class))).thenReturn(records);

        mockMvc.perform(get("/api/records").param("minScore", "70"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].title").value("高分"));
    }

    @Test
    @DisplayName("GET /api/records maxScore 过滤掉 null 分数的记录（覆盖 score==null true 分支）")
    void getRecords_filterByMaxScore_nullScoreFiltered() throws Exception {
        List<WebPageInfo> records = Arrays.asList(
                buildRecord(1L, "已评分", "url1", 80, "政府文件", "北京市"),
                buildRecord(2L, "未评分", "url2", null, null, "上海市"));
        when(webPageInfoRepository.findAll(any(Sort.class))).thenReturn(records);

        mockMvc.perform(get("/api/records").param("maxScore", "90"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].title").value("已评分"));
    }

    @Test
    @DisplayName("GET /api/records 多条件组合过滤")
    void getRecords_multipleFilters_combined() throws Exception {
        WebPageInfo r1 = buildRecord(1L, "北京招标", "url1", 80, "政府文件", "北京市");
        r1.setContent("代发工资");
        WebPageInfo r2 = buildRecord(2L, "上海采购", "url2", 60, "新闻", "上海市");
        r2.setContent("其他内容");
        WebPageInfo r3 = buildRecord(3L, "北京采购", "url3", 90, "政府文件", "北京市");
        r3.setContent("代发工资");
        when(webPageInfoRepository.findAll(any(Sort.class))).thenReturn(Arrays.asList(r1, r2, r3));

        // city=北京市 + minScore=85 + type=政府文件 + keyword=代发
        mockMvc.perform(get("/api/records")
                        .param("city", "北京市")
                        .param("minScore", "85")
                        .param("type", "政府文件")
                        .param("keyword", "代发"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].title").value("北京采购"));
    }

    @Test
    @DisplayName("GET /api/records title 和 content 均为 null 时不抛 NPE（覆盖三元 null 分支）")
    void getRecords_nullTitleAndContent_noException() throws Exception {
        WebPageInfo r = buildRecord(1L, null, "url1", 80, "政府文件", "北京市");
        r.setContent(null);
        when(webPageInfoRepository.findAll(any(Sort.class))).thenReturn(Collections.singletonList(r));

        // keyword 过滤时 title/content 为 null → 三元运算符取空串
        mockMvc.perform(get("/api/records").param("keyword", "招标"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    @DisplayName("GET /api/records city 为空字符串时不按城市过滤")
    void getRecords_emptyCityParam_noFilter() throws Exception {
        List<WebPageInfo> records = Arrays.asList(
                buildRecord(1L, "标题1", "url1", 80, "政府文件", "北京市"),
                buildRecord(2L, "标题2", "url2", 60, "新闻", "上海市"));
        when(webPageInfoRepository.findAll(any(Sort.class))).thenReturn(records);

        // city="" 应该不触发过滤，返回全部
        mockMvc.perform(get("/api/records").param("city", ""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));
    }

    @Test
    @DisplayName("GET /api/records type 为空字符串时不按类型过滤")
    void getRecords_emptyTypeParam_noFilter() throws Exception {
        List<WebPageInfo> records = Arrays.asList(
                buildRecord(1L, "标题1", "url1", 80, "政府文件", "北京市"),
                buildRecord(2L, "标题2", "url2", 60, "新闻", "上海市"));
        when(webPageInfoRepository.findAll(any(Sort.class))).thenReturn(records);

        mockMvc.perform(get("/api/records").param("type", ""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));
    }

    @Test
    @DisplayName("GET /api/records 按 publishStart/publishEnd 过滤（含 null publishTime 被过滤）")
    void getRecords_filterByPublishRange() throws Exception {
        WebPageInfo r1 = buildRecord(1L, "标题1", "url1", 80, "政府文件", "北京市");
        r1.setPublishTime("2026-06-01");
        WebPageInfo r2 = buildRecord(2L, "标题2", "url2", 60, "新闻", "上海市");
        r2.setPublishTime("2026-06-15");
        WebPageInfo r3 = buildRecord(3L, "标题3", "url3", 70, "其他", "成都市");
        r3.setPublishTime("2026-07-01");
        WebPageInfo r4 = buildRecord(4L, "标题4", "url4", 90, "政府文件", "北京市");
        r4.setPublishTime(null); // 发布时间为空，按区间过滤时被排除
        when(webPageInfoRepository.findAll(any(Sort.class))).thenReturn(Arrays.asList(r1, r2, r3, r4));

        // 区间 2026-06-10 ~ 2026-06-20 只命中 r2
        mockMvc.perform(get("/api/records")
                        .param("publishStart", "2026-06-10")
                        .param("publishEnd", "2026-06-20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].title").value("标题2"));
    }

    @Test
    @DisplayName("GET /api/records publishStart 为空字符串时不按起始时间过滤")
    void getRecords_emptyPublishStart_noFilter() throws Exception {
        WebPageInfo r1 = buildRecord(1L, "标题1", "url1", 80, "政府文件", "北京市");
        r1.setPublishTime("2026-06-01");
        WebPageInfo r2 = buildRecord(2L, "标题2", "url2", 60, "新闻", "上海市");
        r2.setPublishTime(null);
        when(webPageInfoRepository.findAll(any(Sort.class))).thenReturn(Arrays.asList(r1, r2));

        mockMvc.perform(get("/api/records").param("publishStart", ""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));
    }

    @Test
    @DisplayName("PUT /api/config/cities/{id} 同时传 cityName 和 enabled")
    void updateCity_bothNameAndEnabled_success() throws Exception {
        CityConfig c = new CityConfig();
        c.setId(1L);
        c.setCityName("新名字");
        c.setEnabled(false);
        when(configService.updateCity(eq(1L), eq("新名字"), eq(false))).thenReturn(c);

        mockMvc.perform(put("/api/config/cities/1").contentType(MediaType.APPLICATION_JSON)
                .content("{\"cityName\":\"新名字\",\"enabled\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cityName").value("新名字"))
                .andExpect(jsonPath("$.enabled").value(false));
    }

    @Test
    @DisplayName("PUT /api/config/cities/{id} enabled 为字符串 'false' 时解析为 Boolean false")
    void updateCity_enabledString_false_parsed() throws Exception {
        CityConfig c = new CityConfig();
        c.setId(1L);
        c.setEnabled(false);
        when(configService.updateCity(eq(1L), isNull(), eq(false))).thenReturn(c);

        mockMvc.perform(put("/api/config/cities/1").contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":\"false\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));
    }

    @Test
    @DisplayName("PUT /api/config/cities/{id} 改名重复返回 400")
    void updateCity_duplicateName_returns400() throws Exception {
        when(configService.updateCity(eq(1L), any(), any()))
                .thenThrow(new IllegalStateException("城市名已存在: 北京市"));

        mockMvc.perform(put("/api/config/cities/1").contentType(MediaType.APPLICATION_JSON)
                .content("{\"cityName\":\"北京市\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("城市名已存在: 北京市"));
    }

    @Test
    @DisplayName("POST /api/trigger/search body 中 cities 非空时使用 body 城市列表")
    void triggerSearch_bodyCities_usesBodyCities() throws Exception {
        when(schedulerLogRepository.existsByTaskNameAndStatus("SEARCH_CRAWL", "RUNNING")).thenReturn(false);

        mockMvc.perform(post("/api/trigger/search").contentType(MediaType.APPLICATION_JSON)
                .content("{\"cities\":[\"广州市\",\"深圳市\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("success"));

        verify(asyncTaskExecutor).executeSearchAsync(Arrays.asList("广州市", "深圳市"));
    }

    @Test
    @DisplayName("GET /api/records 分页第一页返回正确切片")
    void getRecords_firstPage_returnsSlice() throws Exception {
        List<WebPageInfo> records = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            records.add(buildRecord((long) (i + 1), "标题" + i, "url" + i, 80, "政府文件", "北京市"));
        }
        when(webPageInfoRepository.findAll(any(Sort.class))).thenReturn(records);

        // page=0, size=2 → 返回前2条，fromIndex < total true 分支
        mockMvc.perform(get("/api/records").param("page", "0").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.totalElements").value(5))
                .andExpect(jsonPath("$.totalPages").value(3));
    }
}
