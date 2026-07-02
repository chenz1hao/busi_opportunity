package com.opportunity.controller;

import com.opportunity.entity.CityConfig;
import com.opportunity.entity.SystemConfig;
import com.opportunity.entity.WebPageInfo;
import com.opportunity.entity.SchedulerLog;
import com.opportunity.repository.SchedulerLogRepository;
import com.opportunity.repository.WebPageInfoRepository;
import com.opportunity.service.AsyncTaskExecutor;
import com.opportunity.service.ConfigService;
import com.opportunity.service.OpportunityService;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * 商机信息 REST API 控制器
 */
@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class OpportunityController {

    private final WebPageInfoRepository webPageInfoRepository;
    private final OpportunityService opportunityService;
    private final SchedulerLogRepository schedulerLogRepository;
    private final ConfigService configService;
    private final AsyncTaskExecutor asyncTaskExecutor;

    public OpportunityController(WebPageInfoRepository webPageInfoRepository,
                                 OpportunityService opportunityService,
                                 SchedulerLogRepository schedulerLogRepository,
                                 ConfigService configService,
                                 AsyncTaskExecutor asyncTaskExecutor) {
        this.webPageInfoRepository = webPageInfoRepository;
        this.opportunityService = opportunityService;
        this.schedulerLogRepository = schedulerLogRepository;
        this.configService = configService;
        this.asyncTaskExecutor = asyncTaskExecutor;
    }

    // ========== 统计接口 ==========

    @GetMapping("/statistics")
    public ResponseEntity<Map<String, Object>> getStatistics() {
        return ResponseEntity.ok(opportunityService.getStatistics());
    }

    // ========== 记录查询接口 ==========

    @GetMapping("/records")
    public ResponseEntity<Map<String, Object>> getRecords(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String city,
            @RequestParam(required = false) Integer minScore,
            @RequestParam(required = false) Integer maxScore,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String publishStart,
            @RequestParam(required = false) String publishEnd) {

        List<WebPageInfo> allRecords = webPageInfoRepository.findAll(Sort.by(Sort.Direction.DESC, "createdTime"));

        // 内存过滤
        List<WebPageInfo> filtered = new ArrayList<>();
        for (WebPageInfo record : allRecords) {
            if (city != null && !city.isEmpty() && !city.equals(record.getSourceCity())) continue;
            if (minScore != null && (record.getScore() == null || record.getScore() < minScore)) continue;
            if (maxScore != null && (record.getScore() == null || record.getScore() > maxScore)) continue;
            if (type != null && !type.isEmpty() && !type.equals(record.getType())) continue;
            // 发布时间过滤：publishTime 存储为 yyyy-MM-dd 字符串，按字典序比较即可
            if (publishStart != null && !publishStart.isEmpty()
                    && (record.getPublishTime() == null || record.getPublishTime().compareTo(publishStart) < 0)) continue;
            if (publishEnd != null && !publishEnd.isEmpty()
                    && (record.getPublishTime() == null || record.getPublishTime().compareTo(publishEnd) > 0)) continue;
            if (keyword != null && !keyword.isEmpty()) {
                String title = record.getTitle() != null ? record.getTitle() : "";
                String content = record.getContent() != null ? record.getContent() : "";
                if (!title.contains(keyword) && !content.contains(keyword)) continue;
            }
            filtered.add(record);
        }

        // 分页
        int total = filtered.size();
        int fromIndex = page * size;
        int toIndex = Math.min(fromIndex + size, total);
        List<WebPageInfo> pageData = fromIndex < total ? filtered.subList(fromIndex, toIndex) : Collections.emptyList();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("content", pageData);
        result.put("totalElements", total);
        result.put("totalPages", (int) Math.ceil((double) total / size));
        result.put("number", page);
        result.put("size", size);

        return ResponseEntity.ok(result);
    }

    @GetMapping("/records/{id}")
    public ResponseEntity<WebPageInfo> getRecord(@PathVariable Long id) {
        return webPageInfoRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /** 获取线索中实际存在的去重城市列表（用于线索筛选下拉，避免修改搜索城市后历史线索查不到） */
    @GetMapping("/records/cities")
    public ResponseEntity<List<String>> getRecordCities() {
        return ResponseEntity.ok(webPageInfoRepository.findDistinctCities());
    }

    // ========== 手动触发接口 ==========

    @PostMapping("/trigger/search")
    public ResponseEntity<Map<String, String>> triggerSearch(@RequestBody(required = false) Map<String, List<String>> body) {
        Map<String, String> result = new HashMap<>();
        // 校验：当前是否有未完成的搜索任务
        if (schedulerLogRepository.existsByTaskNameAndStatus("SEARCH_CRAWL", "RUNNING")) {
            result.put("status", "error");
            result.put("message", "当前已有搜索任务正在执行中，请稍后再试");
            return ResponseEntity.ok(result);
        }
        List<String> targetCities = (body != null && body.containsKey("cities") && !body.get("cities").isEmpty())
                ? body.get("cities")
                : configService.getActiveCityNames();
        // 异步下发，立即返回
        asyncTaskExecutor.executeSearchAsync(targetCities);
        result.put("status", "success");
        result.put("message", "搜索任务已下发，请稍后查看");
        return ResponseEntity.ok(result);
    }

    @PostMapping("/trigger/analysis")
    public ResponseEntity<Map<String, String>> triggerAnalysis() {
        Map<String, String> result = new HashMap<>();
        // 校验：当前是否有未完成的分析任务
        if (schedulerLogRepository.existsByTaskNameAndStatus("SCORE_ANALYSIS", "RUNNING")) {
            result.put("status", "error");
            result.put("message", "当前已有分析任务正在执行中，请稍后再试");
            return ResponseEntity.ok(result);
        }
        // 异步下发，立即返回
        asyncTaskExecutor.executeAnalysisAsync();
        result.put("status", "success");
        result.put("message", "分析任务已下发，请稍后查看");
        return ResponseEntity.ok(result);
    }

    // ========== 配置接口 ==========

    @GetMapping("/config/cities")
    public ResponseEntity<List<String>> getCities() {
        return ResponseEntity.ok(configService.getActiveCityNames());
    }

    /** 获取所有城市配置（含禁用、排序、ID，用于编辑） */
    @GetMapping("/config/cities/all")
    public ResponseEntity<List<CityConfig>> getAllCities() {
        return ResponseEntity.ok(configService.getAllCities());
    }

    /** 新增城市 */
    @PostMapping("/config/cities")
    public ResponseEntity<?> addCity(@RequestBody Map<String, String> body) {
        try {
            String name = body.get("cityName");
            CityConfig cfg = configService.addCity(name);
            return ResponseEntity.ok(cfg);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(errorBody(e.getMessage()));
        }
    }

    /** 更新城市（改名 / 启停） */
    @PutMapping("/config/cities/{id}")
    public ResponseEntity<?> updateCity(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        try {
            String newName = body.get("cityName") != null ? String.valueOf(body.get("cityName")) : null;
            Boolean enabled = null;
            Object enabledObj = body.get("enabled");
            if (enabledObj instanceof Boolean) {
                enabled = (Boolean) enabledObj;
            } else if (enabledObj != null) {
                enabled = Boolean.parseBoolean(String.valueOf(enabledObj));
            }
            CityConfig cfg = configService.updateCity(id, newName, enabled);
            return ResponseEntity.ok(cfg);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(errorBody(e.getMessage()));
        }
    }

    /** 删除城市 */
    @DeleteMapping("/config/cities/{id}")
    public ResponseEntity<?> deleteCity(@PathVariable Long id) {
        try {
            configService.deleteCity(id);
            Map<String, String> result = new HashMap<>();
            result.put("status", "success");
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(errorBody(e.getMessage()));
        }
    }

    @GetMapping("/config/info")
    public ResponseEntity<Map<String, Object>> getConfigInfo() {
        return ResponseEntity.ok(configService.getConfigInfo());
    }

    /** 获取所有系统配置项（用于编辑） */
    @GetMapping("/config/system")
    public ResponseEntity<List<SystemConfig>> getAllSystemConfigs() {
        return ResponseEntity.ok(configService.getAllSystemConfigs());
    }

    /** 更新系统配置项 */
    @PutMapping("/config/system")
    public ResponseEntity<?> updateSystemConfig(@RequestBody Map<String, String> body) {
        try {
            String key = body.get("configKey");
            String value = body.get("configValue");
            if (key == null || key.trim().isEmpty()) {
                return ResponseEntity.badRequest().body(errorBody("configKey 不能为空"));
            }
            SystemConfig cfg = configService.updateSystemConfig(key, value);
            return ResponseEntity.ok(cfg);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(errorBody(e.getMessage()));
        }
    }

    @GetMapping("/config/scheduler-logs")
    public ResponseEntity<List<SchedulerLog>> getSchedulerLogs() {
        return ResponseEntity.ok(schedulerLogRepository.findTop10ByOrderByStartTimeDesc());
    }

    // ========== 城市统计 ==========

    @GetMapping("/statistics/by-city")
    public ResponseEntity<List<Map<String, Object>>> getCityStatistics() {
        List<Object[]> rawStats = webPageInfoRepository.countByCity();
        List<Map<String, Object>> stats = new ArrayList<>();
        for (Object[] raw : rawStats) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("city", raw[0]);
            item.put("count", raw[1]);
            stats.add(item);
        }
        stats.sort((a, b) -> ((Long) b.get("count")).compareTo((Long) a.get("count")));
        return ResponseEntity.ok(stats);
    }

    @GetMapping("/statistics/by-type")
    public ResponseEntity<List<Map<String, Object>>> getTypeStatistics() {
        List<Object[]> rawStats = webPageInfoRepository.countByType();
        List<Map<String, Object>> stats = new ArrayList<>();
        for (Object[] raw : rawStats) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("type", raw[0]);
            item.put("count", raw[1]);
            stats.add(item);
        }
        return ResponseEntity.ok(stats);
    }

    private Map<String, String> errorBody(String message) {
        Map<String, String> err = new HashMap<>();
        err.put("status", "error");
        err.put("message", message);
        return err;
    }
}
