package com.opportunity.service;

import com.opportunity.entity.CityConfig;
import com.opportunity.entity.SystemConfig;
import com.opportunity.repository.SystemConfigRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 系统配置服务
 * 集中管理系统配置（数据库驱动），城市配置存储在 system_config 表的 search.cities 配置项
 * （CONFIG_VALUE 为逗号分隔的城市列表），并提供配置变更回调机制用于动态调度
 * 城市列表仅从数据库读取，application.yml 不再配置城市列表
 */
@Service
public class ConfigService {

    private static final Logger log = LoggerFactory.getLogger(ConfigService.class);

    // 系统配置键
    public static final String KEY_DEDUP_THRESHOLD = "dedup.threshold";
    public static final String KEY_ANALYSIS_MODEL = "volcengine.analysis-model";
    public static final String KEY_SEARCH_CRON = "scheduler.search-cron";
    public static final String KEY_ANALYSIS_CRON = "scheduler.analysis-cron";
    public static final String KEY_ANALYSIS_BATCH_SIZE = "analysis.batch-size";
    /** 城市列表配置键（逗号分隔的城市名） */
    public static final String KEY_SEARCH_CITIES = "search.cities";

    private final SystemConfigRepository systemRepo;

    /** 默认值（来自 application.yml），仅在首次初始化时写入数据库 */
    @Value("${dedup.threshold:0.90}")
    private double defaultDedupThreshold;

    @Value("${volcengine.analysis-model:deepseek-v4-flash-260425}")
    private String defaultAnalysisModel;

    @Value("${scheduler.search-cron:0 0 8,12,16,20 * * ?}")
    private String defaultSearchCron;

    @Value("${scheduler.analysis-cron:0 0 2 * * ?}")
    private String defaultAnalysisCron;

    @Value("${analysis.batch-size:10}")
    private int defaultAnalysisBatchSize;

    /** 配置变更监听器（用于动态调度重注册） */
    private final List<Runnable> cronChangeListeners = new CopyOnWriteArrayList<>();

    public ConfigService(SystemConfigRepository systemRepo) {
        this.systemRepo = systemRepo;
    }

    /**
     * 启动时初始化默认数据（仅当数据库为空时）
     */
    @PostConstruct
    public void initDefaults() {
        try {
            initSystemConfigIfNeeded();
            log.info("系统配置初始化完成: 启用城市数={}, 系统配置项={}",
                    getActiveCityNames().size(), systemRepo.count());
        } catch (Exception e) {
            log.error("系统配置初始化失败: {}", e.getMessage(), e);
        }
    }

    private void initSystemConfigIfNeeded() {
        saveIfAbsent(KEY_DEDUP_THRESHOLD, String.valueOf(defaultDedupThreshold),
                "去重相似度阈值(0-1)");
        saveIfAbsent(KEY_ANALYSIS_MODEL, defaultAnalysisModel,
                "评分分析使用的模型ID");
        saveIfAbsent(KEY_SEARCH_CRON, defaultSearchCron,
                "搜索抓取定时任务cron表达式");
        saveIfAbsent(KEY_ANALYSIS_CRON, defaultAnalysisCron,
                "评分分析定时任务cron表达式");
        saveIfAbsent(KEY_ANALYSIS_BATCH_SIZE, String.valueOf(defaultAnalysisBatchSize),
                "评分分析单批次线索数(1-50)");
    }

    private void saveIfAbsent(String key, String value, String description) {
        if (!systemRepo.findByConfigKey(key).isPresent()) {
            SystemConfig cfg = new SystemConfig();
            cfg.setConfigKey(key);
            cfg.setConfigValue(value);
            cfg.setDescription(description);
            systemRepo.save(cfg);
        }
    }

    // ========== 城市配置 ==========

    /**
     * 解析 system_config 中 search.cities 的值为城市名列表
     */
    private List<String> parseCitiesValue(String csv) {
        if (csv == null || csv.trim().isEmpty()) {
            return new ArrayList<>();
        }
        List<String> cities = new ArrayList<>();
        for (String s : csv.split(",")) {
            String trimmed = s.trim();
            if (!trimmed.isEmpty()) {
                cities.add(trimmed);
            }
        }
        return cities;
    }

    /** 获取搜索城市列表（全部城市，合并后不再区分启用/禁用） */
    public List<String> getActiveCityNames() {
        return parseCitiesValue(getConfigValue(KEY_SEARCH_CITIES));
    }

    /**
     * 获取所有城市配置（含 id、排序，用于前端编辑）
     * id 为列表索引+1，sortOrder 为列表索引，enabled 固定为 true
     */
    public List<CityConfig> getAllCities() {
        List<String> names = getActiveCityNames();
        SystemConfig cfg = systemRepo.findByConfigKey(KEY_SEARCH_CITIES).orElse(null);
        LocalDateTime updatedTime = (cfg != null) ? cfg.getUpdatedTime() : null;

        List<CityConfig> list = new ArrayList<>(names.size());
        for (int i = 0; i < names.size(); i++) {
            CityConfig c = new CityConfig();
            c.setId((long) (i + 1));
            c.setCityName(names.get(i));
            c.setEnabled(true);
            c.setSortOrder(i);
            c.setUpdatedTime(updatedTime);
            list.add(c);
        }
        return list;
    }

    /** 新增城市 */
    @Transactional
    public CityConfig addCity(String cityName) {
        if (cityName == null || cityName.trim().isEmpty()) {
            throw new IllegalArgumentException("城市名不能为空");
        }
        String name = cityName.trim();
        List<String> cities = getActiveCityNames();
        if (cities.contains(name)) {
            throw new IllegalStateException("城市已存在: " + name);
        }
        cities.add(name);
        saveCities(cities);

        CityConfig result = new CityConfig();
        result.setId((long) cities.size());
        result.setCityName(name);
        result.setEnabled(true);
        result.setSortOrder(cities.size() - 1);
        return result;
    }

    /**
     * 更新城市（改名）
     * 注意：合并后不再支持启停（enabled 参数保留但忽略），id 为列表索引+1
     */
    @Transactional
    public CityConfig updateCity(Long id, String newName, Boolean enabled) {
        List<String> cities = getActiveCityNames();
        int index = id.intValue() - 1;
        if (index < 0 || index >= cities.size()) {
            throw new IllegalArgumentException("城市不存在: id=" + id);
        }
        if (newName != null && !newName.trim().isEmpty()) {
            String name = newName.trim();
            // 改名重名校验（排除自身）
            for (int i = 0; i < cities.size(); i++) {
                if (i != index && cities.get(i).equals(name)) {
                    throw new IllegalStateException("城市名已存在: " + name);
                }
            }
            cities.set(index, name);
            saveCities(cities);
        }
        // enabled 参数保留兼容性但不再生效（合并后所有城市均为启用）

        CityConfig result = new CityConfig();
        result.setId(id);
        result.setCityName(cities.get(index));
        result.setEnabled(true);
        result.setSortOrder(index);
        return result;
    }

    /** 删除城市 */
    @Transactional
    public void deleteCity(Long id) {
        List<String> cities = getActiveCityNames();
        int index = id.intValue() - 1;
        if (index < 0 || index >= cities.size()) {
            throw new IllegalArgumentException("城市不存在: id=" + id);
        }
        cities.remove(index);
        saveCities(cities);
    }

    /**
     * 将城市列表写回 system_config 的 search.cities 配置项
     */
    private void saveCities(List<String> cities) {
        String csv = String.join(",", cities);
        SystemConfig cfg = systemRepo.findByConfigKey(KEY_SEARCH_CITIES)
                .orElseThrow(() -> new IllegalStateException("城市配置项不存在: " + KEY_SEARCH_CITIES));
        cfg.setConfigValue(csv);
        systemRepo.save(cfg);
    }

    // ========== 系统配置 ==========

    /** 获取所有系统配置 */
    public List<SystemConfig> getAllSystemConfigs() {
        return systemRepo.findAll();
    }

    /** 按键获取配置值 */
    public String getConfigValue(String key) {
        return systemRepo.findByConfigKey(key)
                .map(SystemConfig::getConfigValue)
                .orElse(null);
    }

    /** 更新系统配置（返回是否触发了 cron 变更） */
    @Transactional
    public SystemConfig updateSystemConfig(String key, String value) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("配置值不能为空");
        }
        SystemConfig cfg = systemRepo.findByConfigKey(key)
                .orElseThrow(() -> new IllegalArgumentException("配置项不存在: " + key));
        String oldValue = cfg.getConfigValue();
        cfg.setConfigValue(value.trim());
        cfg = systemRepo.save(cfg);

        // 如果是 cron 配置变更，通知监听器重新调度
        if (isCronKey(key) && !value.trim().equals(oldValue)) {
            log.info("cron 配置变更: {} 从 [{}] 改为 [{}]", key, oldValue, value);
            notifyCronChangeListeners();
        }
        return cfg;
    }

    private boolean isCronKey(String key) {
        return KEY_SEARCH_CRON.equals(key) || KEY_ANALYSIS_CRON.equals(key);
    }

    // ========== 便捷 getter ==========

    public double getDedupThreshold() {
        String v = getConfigValue(KEY_DEDUP_THRESHOLD);
        if (v == null) return defaultDedupThreshold;
        try {
            return Double.parseDouble(v.trim());
        } catch (NumberFormatException e) {
            log.warn("去重阈值解析失败: {}，使用默认值 {}", v, defaultDedupThreshold);
            return defaultDedupThreshold;
        }
    }

    /**
     * 获取当前使用的分析模型。
     * 配置值以 '|' 分隔多个可用模型，第一个为当前使用模型。
     */
    public String getAnalysisModel() {
        String v = getConfigValue(KEY_ANALYSIS_MODEL);
        String raw = v != null ? v : defaultAnalysisModel;
        if (raw == null || raw.trim().isEmpty()) {
            return defaultAnalysisModel;
        }
        // 取 '|' 分隔的第一个非空模型作为当前使用模型
        for (String part : raw.split("\\|")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                return trimmed;
            }
        }
        return defaultAnalysisModel;
    }

    public String getSearchCron() {
        String v = getConfigValue(KEY_SEARCH_CRON);
        return v != null ? v : defaultSearchCron;
    }

    public String getAnalysisCron() {
        String v = getConfigValue(KEY_ANALYSIS_CRON);
        return v != null ? v : defaultAnalysisCron;
    }

    /** 获取分析批次大小（每次大模型调用处理的线索数） */
    public int getAnalysisBatchSize() {
        String v = getConfigValue(KEY_ANALYSIS_BATCH_SIZE);
        if (v == null || v.trim().isEmpty()) {
            return defaultAnalysisBatchSize;
        }
        try {
            int size = Integer.parseInt(v.trim());
            // 合法性校验：1~50
            if (size < 1) return 1;
            if (size > 50) return 50;
            return size;
        } catch (NumberFormatException e) {
            log.warn("分析批次大小解析失败: {}，使用默认值 {}", v, defaultAnalysisBatchSize);
            return defaultAnalysisBatchSize;
        }
    }

    /** 配置信息汇总（前端展示用） */
    public Map<String, Object> getConfigInfo() {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("analysisModel", getAnalysisModel());
        info.put("dedupThreshold", getDedupThreshold());
        info.put("searchCron", getSearchCron());
        info.put("analysisCron", getAnalysisCron());
        info.put("analysisBatchSize", getAnalysisBatchSize());
        return info;
    }

    // ========== 动态调度回调 ==========

    /** 注册 cron 变更监听器（Scheduler 在启动时注册，变更时调用以重新调度） */
    public void registerCronChangeListener(Runnable listener) {
        cronChangeListeners.add(listener);
    }

    private void notifyCronChangeListeners() {
        for (Runnable listener : cronChangeListeners) {
            try {
                listener.run();
            } catch (Exception e) {
                log.error("通知 cron 变更监听器失败: {}", e.getMessage(), e);
            }
        }
    }
}
