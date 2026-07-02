package com.opportunity.service;

import com.opportunity.entity.CityConfig;
import com.opportunity.entity.SystemConfig;
import com.opportunity.repository.SystemConfigRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 系统配置服务测试
 * 使用真实 H2 内存数据库，验证 CRUD、初始化、动态 cron 回调、配置值解析边界
 * 城市配置已合并到 system_config 表的 search.cities 配置项
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ConfigServiceTest {

    @Autowired
    private ConfigService configService;

    @Autowired
    private SystemConfigRepository systemRepo;

    @BeforeEach
    void cleanDb() {
        // 清空 system_config 表（城市配置也存储于此）
        systemRepo.deleteAllInBatch();
    }

    /** 准备 search.cities 配置项，便于城市 CRUD 测试 */
    private void prepareCitiesConfig(String csv) {
        SystemConfig cfg = new SystemConfig();
        cfg.setConfigKey(ConfigService.KEY_SEARCH_CITIES);
        cfg.setConfigValue(csv);
        cfg.setDescription("搜索城市列表");
        systemRepo.save(cfg);
    }

    // ========== 初始化逻辑 ==========

    @Test
    @DisplayName("initDefaults 在空库时导入系统配置（不含城市，城市完全由数据库管理）")
    void initDefaults_emptyDb_importsSystemConfigOnly() {
        configService.initDefaults();

        // 城市配置不再自动导入，首次启动为空列表
        List<String> cities = configService.getActiveCityNames();
        assertThat(cities).isEmpty();

        // 系统配置项应包含 5 个键（不含 search.cities）
        assertThat(systemRepo.findByConfigKey(ConfigService.KEY_DEDUP_THRESHOLD)).isPresent();
        assertThat(systemRepo.findByConfigKey(ConfigService.KEY_ANALYSIS_MODEL)).isPresent();
        assertThat(systemRepo.findByConfigKey(ConfigService.KEY_SEARCH_CRON)).isPresent();
        assertThat(systemRepo.findByConfigKey(ConfigService.KEY_ANALYSIS_CRON)).isPresent();
        assertThat(systemRepo.findByConfigKey(ConfigService.KEY_ANALYSIS_BATCH_SIZE)).isPresent();
        assertThat(systemRepo.findByConfigKey(ConfigService.KEY_SEARCH_CITIES)).isNotPresent();
    }

    @Test
    @DisplayName("initDefaults 已存在的配置项不会重复创建（saveIfAbsent）")
    void initDefaults_nonEmptyDb_skipsExisting() {
        // 预先插入一条配置
        SystemConfig existing = new SystemConfig();
        existing.setConfigKey(ConfigService.KEY_DEDUP_THRESHOLD);
        existing.setConfigValue("0.50");
        existing.setDescription("已存在");
        systemRepo.save(existing);

        configService.initDefaults();

        // 已存在的配置项不会被覆盖
        SystemConfig after = systemRepo.findByConfigKey(ConfigService.KEY_DEDUP_THRESHOLD).orElse(null);
        assertThat(after).isNotNull();
        assertThat(after.getConfigValue()).isEqualTo("0.50");
    }

    // ========== 城市配置 CRUD ==========

    @Test
    @DisplayName("addCity 正常新增城市")
    void addCity_normal_success() {
        prepareCitiesConfig("北京市,上海市");
        CityConfig cfg = configService.addCity("新城市");
        assertThat(cfg.getId()).isNotNull();
        assertThat(cfg.getCityName()).isEqualTo("新城市");
        assertThat(cfg.getEnabled()).isTrue();
        assertThat(cfg.getSortOrder()).isNotNull();
        // 验证已写入配置
        assertThat(configService.getActiveCityNames()).contains("新城市");
    }

    @Test
    @DisplayName("addCity 城市名为 null 抛 IllegalArgumentException")
    void addCity_nullName_throwsException() {
        prepareCitiesConfig("北京市");
        assertThatThrownBy(() -> configService.addCity(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不能为空");
    }

    @Test
    @DisplayName("addCity 城市名为空字符串抛 IllegalArgumentException")
    void addCity_emptyName_throwsException() {
        prepareCitiesConfig("北京市");
        assertThatThrownBy(() -> configService.addCity("   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不能为空");
    }

    @Test
    @DisplayName("addCity 城市名已存在抛 IllegalStateException")
    void addCity_duplicateName_throwsException() {
        prepareCitiesConfig("北京市");
        assertThatThrownBy(() -> configService.addCity("北京市"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("已存在");
    }

    @Test
    @DisplayName("addCity sortOrder 取当前列表长度（末尾追加）")
    void addCity_sortOrderIncrements() {
        prepareCitiesConfig("城市A");
        CityConfig c1 = configService.addCity("城市B");
        assertThat(c1.getSortOrder()).isEqualTo(1); // 0=城市A, 1=城市B
    }

    @Test
    @DisplayName("updateCity 改名成功")
    void updateCity_rename_success() {
        prepareCitiesConfig("旧名字,上海市");
        CityConfig updated = configService.updateCity(1L, "新名字", null);
        assertThat(updated.getCityName()).isEqualTo("新名字");
        assertThat(configService.getActiveCityNames()).contains("新名字").doesNotContain("旧名字");
    }

    @Test
    @DisplayName("updateCity enabled 参数保留兼容但不再生效（始终返回 true）")
    void updateCity_toggleEnabled_alwaysTrue() {
        prepareCitiesConfig("城市X");
        CityConfig updated = configService.updateCity(1L, null, false);
        assertThat(updated.getEnabled()).isTrue(); // 合并后始终启用
    }

    @Test
    @DisplayName("updateCity 改成已存在的城市名抛 IllegalStateException")
    void updateCity_duplicateName_throwsException() {
        prepareCitiesConfig("城市A,城市B");
        assertThatThrownBy(() -> configService.updateCity(2L, "城市A", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("已存在");
    }

    @Test
    @DisplayName("updateCity 改成同名（自身）不抛异常")
    void updateCity_sameName_noException() {
        prepareCitiesConfig("城市A");
        CityConfig updated = configService.updateCity(1L, "城市A", null);
        assertThat(updated.getCityName()).isEqualTo("城市A");
    }

    @Test
    @DisplayName("updateCity id 不存在抛 IllegalArgumentException")
    void updateCity_notFound_throwsException() {
        prepareCitiesConfig("北京市");
        assertThatThrownBy(() -> configService.updateCity(99999L, "新名字", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不存在");
    }

    @Test
    @DisplayName("updateCity 名字为空字符串时跳过改名")
    void updateCity_emptyName_skipsRename() {
        prepareCitiesConfig("原名");
        CityConfig updated = configService.updateCity(1L, "   ", null);
        assertThat(updated.getCityName()).isEqualTo("原名");
    }

    @Test
    @DisplayName("deleteCity 正常删除")
    void deleteCity_normal_success() {
        prepareCitiesConfig("待删除,北京市");
        configService.deleteCity(1L);
        assertThat(configService.getActiveCityNames()).containsExactly("北京市");
    }

    @Test
    @DisplayName("deleteCity id 不存在抛 IllegalArgumentException")
    void deleteCity_notFound_throwsException() {
        prepareCitiesConfig("北京市");
        assertThatThrownBy(() -> configService.deleteCity(99999L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不存在");
    }

    @Test
    @DisplayName("getActiveCityNames 返回逗号分隔的城市列表")
    void getActiveCityNames_returnsParsedCities() {
        prepareCitiesConfig("北京市, 上海市 , ,成都市");
        List<String> names = configService.getActiveCityNames();
        assertThat(names).containsExactly("北京市", "上海市", "成都市");
    }

    @Test
    @DisplayName("getActiveCityNames 配置为空时返回空列表")
    void getActiveCityNames_emptyConfig_returnsEmpty() {
        prepareCitiesConfig("");
        assertThat(configService.getActiveCityNames()).isEmpty();
    }

    @Test
    @DisplayName("getActiveCityNames 配置项不存在时返回空列表")
    void getActiveCityNames_configNotExists_returnsEmpty() {
        assertThat(configService.getActiveCityNames()).isEmpty();
    }

    @Test
    @DisplayName("getAllCities 返回城市列表（含 id、排序）")
    void getAllCities_returnsListWithIdAndOrder() {
        prepareCitiesConfig("北京市,上海市,成都市");
        List<CityConfig> all = configService.getAllCities();
        assertThat(all).hasSize(3);
        assertThat(all.get(0).getId()).isEqualTo(1L);
        assertThat(all.get(0).getCityName()).isEqualTo("北京市");
        assertThat(all.get(0).getSortOrder()).isEqualTo(0);
        assertThat(all.get(2).getId()).isEqualTo(3L);
        assertThat(all.get(2).getSortOrder()).isEqualTo(2);
    }

    @Test
    @DisplayName("getAllCities 城市列表为空时返回空列表")
    void getAllCities_emptyConfig_returnsEmpty() {
        prepareCitiesConfig("");
        assertThat(configService.getAllCities()).isEmpty();
    }

    // ========== 系统配置 CRUD ==========

    @Test
    @DisplayName("getConfigValue 键不存在返回 null")
    void getConfigValue_notExists_returnsNull() {
        assertThat(configService.getConfigValue("not-exists-key")).isNull();
    }

    @Test
    @DisplayName("updateSystemConfig 正常更新")
    void updateSystemConfig_normal_success() {
        SystemConfig cfg = new SystemConfig();
        cfg.setConfigKey("test.key");
        cfg.setConfigValue("old");
        cfg.setDescription("desc");
        systemRepo.save(cfg);

        SystemConfig updated = configService.updateSystemConfig("test.key", "new");
        assertThat(updated.getConfigValue()).isEqualTo("new");
    }

    @Test
    @DisplayName("updateSystemConfig 值为 null 抛 IllegalArgumentException")
    void updateSystemConfig_nullValue_throwsException() {
        assertThatThrownBy(() -> configService.updateSystemConfig("any.key", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不能为空");
    }

    @Test
    @DisplayName("updateSystemConfig 值为空字符串抛 IllegalArgumentException")
    void updateSystemConfig_emptyValue_throwsException() {
        assertThatThrownBy(() -> configService.updateSystemConfig("any.key", "   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不能为空");
    }

    @Test
    @DisplayName("updateSystemConfig 键不存在抛 IllegalArgumentException")
    void updateSystemConfig_keyNotExists_throwsException() {
        assertThatThrownBy(() -> configService.updateSystemConfig("not-exists", "value"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不存在");
    }

    @Test
    @DisplayName("updateSystemConfig 修改 search-cron 触发监听器回调")
    void updateSystemConfig_cronChange_triggersListener() {
        SystemConfig cfg = new SystemConfig();
        cfg.setConfigKey(ConfigService.KEY_SEARCH_CRON);
        cfg.setConfigValue("0 0 8 * * ?");
        cfg.setDescription("desc");
        systemRepo.save(cfg);

        AtomicInteger callCount = new AtomicInteger(0);
        configService.registerCronChangeListener(callCount::incrementAndGet);

        configService.updateSystemConfig(ConfigService.KEY_SEARCH_CRON, "0 0 9 * * ?");
        assertThat(callCount.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("updateSystemConfig 修改非 cron 配置不触发监听器")
    void updateSystemConfig_nonCronChange_doesNotTriggerListener() {
        SystemConfig cfg = new SystemConfig();
        cfg.setConfigKey(ConfigService.KEY_DEDUP_THRESHOLD);
        cfg.setConfigValue("0.90");
        cfg.setDescription("desc");
        systemRepo.save(cfg);

        AtomicInteger callCount = new AtomicInteger(0);
        configService.registerCronChangeListener(callCount::incrementAndGet);

        configService.updateSystemConfig(ConfigService.KEY_DEDUP_THRESHOLD, "0.85");
        assertThat(callCount.get()).isZero();
    }

    @Test
    @DisplayName("updateSystemConfig cron 配置值未变化不触发监听器")
    void updateSystemConfig_sameCronValue_doesNotTriggerListener() {
        SystemConfig cfg = new SystemConfig();
        cfg.setConfigKey(ConfigService.KEY_SEARCH_CRON);
        cfg.setConfigValue("0 0 8 * * ?");
        cfg.setDescription("desc");
        systemRepo.save(cfg);

        AtomicInteger callCount = new AtomicInteger(0);
        configService.registerCronChangeListener(callCount::incrementAndGet);

        configService.updateSystemConfig(ConfigService.KEY_SEARCH_CRON, "0 0 8 * * ?");
        assertThat(callCount.get()).isZero();
    }

    @Test
    @DisplayName("registerCronChangeListener 监听器抛异常不影响其他监听器")
    void notifyCronChangeListeners_oneThrows_othersStillRun() {
        SystemConfig cfg = new SystemConfig();
        cfg.setConfigKey(ConfigService.KEY_ANALYSIS_CRON);
        cfg.setConfigValue("0 0 2 * * ?");
        cfg.setDescription("desc");
        systemRepo.save(cfg);

        AtomicInteger callCount = new AtomicInteger(0);
        configService.registerCronChangeListener(() -> { throw new RuntimeException("boom"); });
        configService.registerCronChangeListener(callCount::incrementAndGet);

        configService.updateSystemConfig(ConfigService.KEY_ANALYSIS_CRON, "0 0 3 * * ?");
        assertThat(callCount.get()).isEqualTo(1);
    }

    // ========== 便捷 getter 边界测试 ==========

    @Test
    @DisplayName("getDedupThreshold 键不存在时返回默认值")
    void getDedupThreshold_notExists_returnsDefault() {
        assertThat(configService.getDedupThreshold()).isEqualTo(0.90);
    }

    @Test
    @DisplayName("getDedupThreshold 非法值时返回默认值")
    void getDedupThreshold_invalidValue_returnsDefault() {
        SystemConfig cfg = new SystemConfig();
        cfg.setConfigKey(ConfigService.KEY_DEDUP_THRESHOLD);
        cfg.setConfigValue("not-a-number");
        cfg.setDescription("desc");
        systemRepo.save(cfg);

        assertThat(configService.getDedupThreshold()).isEqualTo(0.90);
    }

    @Test
    @DisplayName("getAnalysisModel 键不存在时返回默认值")
    void getAnalysisModel_notExists_returnsDefault() {
        assertThat(configService.getAnalysisModel()).isNotEmpty();
    }

    @Test
    @DisplayName("getSearchCron 键不存在时返回默认值")
    void getSearchCron_notExists_returnsDefault() {
        assertThat(configService.getSearchCron()).isNotEmpty();
    }

    @Test
    @DisplayName("getAnalysisCron 键不存在时返回默认值")
    void getAnalysisCron_notExists_returnsDefault() {
        assertThat(configService.getAnalysisCron()).isNotEmpty();
    }

    @Test
    @DisplayName("getAnalysisBatchSize 键不存在时返回默认值")
    void getAnalysisBatchSize_notExists_returnsDefault() {
        assertThat(configService.getAnalysisBatchSize()).isEqualTo(10);
    }

    @Test
    @DisplayName("getAnalysisBatchSize 非法值时返回默认值")
    void getAnalysisBatchSize_invalidValue_returnsDefault() {
        SystemConfig cfg = new SystemConfig();
        cfg.setConfigKey(ConfigService.KEY_ANALYSIS_BATCH_SIZE);
        cfg.setConfigValue("abc");
        cfg.setDescription("desc");
        systemRepo.save(cfg);

        assertThat(configService.getAnalysisBatchSize()).isEqualTo(10);
    }

    @Test
    @DisplayName("getAnalysisBatchSize 值为 0 时返回下限 1")
    void getAnalysisBatchSize_zeroValue_returnsOne() {
        SystemConfig cfg = new SystemConfig();
        cfg.setConfigKey(ConfigService.KEY_ANALYSIS_BATCH_SIZE);
        cfg.setConfigValue("0");
        cfg.setDescription("desc");
        systemRepo.save(cfg);

        assertThat(configService.getAnalysisBatchSize()).isEqualTo(1);
    }

    @Test
    @DisplayName("getAnalysisBatchSize 值为负数时返回下限 1")
    void getAnalysisBatchSize_negativeValue_returnsOne() {
        SystemConfig cfg = new SystemConfig();
        cfg.setConfigKey(ConfigService.KEY_ANALYSIS_BATCH_SIZE);
        cfg.setConfigValue("-5");
        cfg.setDescription("desc");
        systemRepo.save(cfg);

        assertThat(configService.getAnalysisBatchSize()).isEqualTo(1);
    }

    @Test
    @DisplayName("getAnalysisBatchSize 值为 100 时返回上限 50")
    void getAnalysisBatchSize_overMax_returnsFifty() {
        SystemConfig cfg = new SystemConfig();
        cfg.setConfigKey(ConfigService.KEY_ANALYSIS_BATCH_SIZE);
        cfg.setConfigValue("100");
        cfg.setDescription("desc");
        systemRepo.save(cfg);

        assertThat(configService.getAnalysisBatchSize()).isEqualTo(50);
    }

    @Test
    @DisplayName("getAnalysisBatchSize 值为 25 时正常返回")
    void getAnalysisBatchSize_validValue_returnsValue() {
        SystemConfig cfg = new SystemConfig();
        cfg.setConfigKey(ConfigService.KEY_ANALYSIS_BATCH_SIZE);
        cfg.setConfigValue("25");
        cfg.setDescription("desc");
        systemRepo.save(cfg);

        assertThat(configService.getAnalysisBatchSize()).isEqualTo(25);
    }

    @Test
    @DisplayName("getAnalysisBatchSize 值为空字符串时返回默认值")
    void getAnalysisBatchSize_emptyValue_returnsDefault() {
        SystemConfig cfg = new SystemConfig();
        cfg.setConfigKey(ConfigService.KEY_ANALYSIS_BATCH_SIZE);
        cfg.setConfigValue("   ");
        cfg.setDescription("desc");
        systemRepo.save(cfg);

        assertThat(configService.getAnalysisBatchSize()).isEqualTo(10);
    }

    @Test
    @DisplayName("getConfigInfo 返回完整的配置信息汇总")
    void getConfigInfo_returnsAllFields() {
        for (String key : new String[]{
                ConfigService.KEY_DEDUP_THRESHOLD,
                ConfigService.KEY_ANALYSIS_MODEL,
                ConfigService.KEY_SEARCH_CRON,
                ConfigService.KEY_ANALYSIS_CRON,
                ConfigService.KEY_ANALYSIS_BATCH_SIZE}) {
            SystemConfig cfg = new SystemConfig();
            cfg.setConfigKey(key);
            cfg.setConfigValue("1");
            cfg.setDescription("desc");
            systemRepo.save(cfg);
        }

        java.util.Map<String, Object> info = configService.getConfigInfo();
        assertThat(info).containsKeys("analysisModel", "dedupThreshold", "searchCron", "analysisCron", "analysisBatchSize");
    }

    @Test
    @DisplayName("getAllSystemConfigs 返回所有系统配置")
    void getAllSystemConfigs_returnsAll() {
        SystemConfig c1 = new SystemConfig();
        c1.setConfigKey("k1");
        c1.setConfigValue("v1");
        c1.setDescription("d1");
        systemRepo.save(c1);

        SystemConfig c2 = new SystemConfig();
        c2.setConfigKey("k2");
        c2.setConfigValue("v2");
        c2.setDescription("d2");
        systemRepo.save(c2);

        List<SystemConfig> all = configService.getAllSystemConfigs();
        assertThat(all).hasSize(2);
    }
}
