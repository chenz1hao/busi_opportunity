package com.opportunity.service;

import com.opportunity.entity.SystemConfig;
import com.opportunity.repository.SystemConfigRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * ConfigService 单元测试（mock 依赖）
 * 城市列表已改为硬编码默认值，不再从 application.yml 读取
 * 覆盖 saveIfAbsent 已存在/不存在分支、initDefaults 异常 catch 等私有方法分支
 */
@ExtendWith(MockitoExtension.class)
class ConfigServiceUnitTest {

    @Mock
    private SystemConfigRepository systemRepo;

    private ConfigService configService;

    @BeforeEach
    void setUp() {
        configService = new ConfigService(systemRepo);
        // 注入 @Value 默认值
        ReflectionTestUtils.setField(configService, "defaultDedupThreshold", 0.90);
        ReflectionTestUtils.setField(configService, "defaultAnalysisModel", "doubao-test");
        ReflectionTestUtils.setField(configService, "defaultSearchCron", "0 0 8 * * ?");
        ReflectionTestUtils.setField(configService, "defaultAnalysisCron", "0 0 2 * * ?");
        ReflectionTestUtils.setField(configService, "defaultAnalysisBatchSize", 10);
    }

    @Test
    @DisplayName("initSystemConfigIfNeeded 系统配置已存在时跳过创建（saveIfAbsent false 分支）")
    void initSystemConfigIfNeeded_alreadyExists_skipsCreation() {
        // 所有系统配置都已存在
        when(systemRepo.findByConfigKey(anyString())).thenReturn(Optional.of(new SystemConfig()));

        configService.initDefaults();

        // saveIfAbsent 发现已存在，不调用 save
        verify(systemRepo, never()).save(any(SystemConfig.class));
    }

    @Test
    @DisplayName("initSystemConfigIfNeeded 系统配置不存在时创建（saveIfAbsent true 分支）")
    void initSystemConfigIfNeeded_notExists_createsNew() {
        // 系统配置都不存在
        when(systemRepo.findByConfigKey(anyString())).thenReturn(Optional.empty());

        configService.initDefaults();

        // 5 个系统配置都应被创建（不含 search.cities，城市完全由数据库管理）
        verify(systemRepo, times(5)).save(any(SystemConfig.class));
    }

    @Test
    @DisplayName("initDefaults 数据库异常时不抛出（catch 分支）")
    void initDefaults_dbException_doesNotThrow() {
        when(systemRepo.findByConfigKey(anyString())).thenThrow(new RuntimeException("DB 连接失败"));

        // initDefaults 的 catch 块吞掉异常，不应抛出
        configService.initDefaults();

        // 验证没有继续执行 save（异常后中断）
        verify(systemRepo, never()).save(any(SystemConfig.class));
    }

    @Test
    @DisplayName("getDedupThreshold 数据库配置存在时返回数据库值")
    void getDedupThreshold_existsInDb_returnsDbValue() {
        SystemConfig cfg = new SystemConfig();
        cfg.setConfigValue("0.85");
        when(systemRepo.findByConfigKey("dedup.threshold")).thenReturn(Optional.of(cfg));

        assertThat(configService.getDedupThreshold()).isEqualTo(0.85);
    }

    @Test
    @DisplayName("getAnalysisModel 数据库配置存在时返回数据库值")
    void getAnalysisModel_existsInDb_returnsDbValue() {
        SystemConfig cfg = new SystemConfig();
        cfg.setConfigValue("custom-model");
        when(systemRepo.findByConfigKey("volcengine.analysis-model")).thenReturn(Optional.of(cfg));

        assertThat(configService.getAnalysisModel()).isEqualTo("custom-model");
    }

    @Test
    @DisplayName("getAnalysisModel 多模型以 | 分隔时返回第一个作为当前使用模型")
    void getAnalysisModel_multipleModels_returnsFirst() {
        SystemConfig cfg = new SystemConfig();
        cfg.setConfigValue("modelA | modelB | modelC");
        when(systemRepo.findByConfigKey("volcengine.analysis-model")).thenReturn(Optional.of(cfg));

        assertThat(configService.getAnalysisModel()).isEqualTo("modelA");
    }

    @Test
    @DisplayName("getSearchCron 数据库配置存在时返回数据库值")
    void getSearchCron_existsInDb_returnsDbValue() {
        SystemConfig cfg = new SystemConfig();
        cfg.setConfigValue("0 0 6 * * ?");
        when(systemRepo.findByConfigKey("scheduler.search-cron")).thenReturn(Optional.of(cfg));

        assertThat(configService.getSearchCron()).isEqualTo("0 0 6 * * ?");
    }

    @Test
    @DisplayName("getAnalysisCron 数据库配置存在时返回数据库值")
    void getAnalysisCron_existsInDb_returnsDbValue() {
        SystemConfig cfg = new SystemConfig();
        cfg.setConfigValue("0 0 3 * * ?");
        when(systemRepo.findByConfigKey("scheduler.analysis-cron")).thenReturn(Optional.of(cfg));

        assertThat(configService.getAnalysisCron()).isEqualTo("0 0 3 * * ?");
    }

    @Test
    @DisplayName("getAnalysisBatchSize 数据库配置存在时返回数据库值")
    void getAnalysisBatchSize_existsInDb_returnsDbValue() {
        SystemConfig cfg = new SystemConfig();
        cfg.setConfigValue("15");
        when(systemRepo.findByConfigKey("analysis.batch-size")).thenReturn(Optional.of(cfg));

        assertThat(configService.getAnalysisBatchSize()).isEqualTo(15);
    }

    @Test
    @DisplayName("updateSystemConfig 修改 analysis-cron 触发监听器")
    void updateSystemConfig_analysisCronChange_triggersListener() {
        SystemConfig cfg = new SystemConfig();
        cfg.setConfigKey("scheduler.analysis-cron");
        cfg.setConfigValue("0 0 2 * * ?");
        when(systemRepo.findByConfigKey("scheduler.analysis-cron")).thenReturn(Optional.of(cfg));
        when(systemRepo.save(any())).thenReturn(cfg);

        int[] callCount = {0};
        configService.registerCronChangeListener(() -> callCount[0]++);

        configService.updateSystemConfig("scheduler.analysis-cron", "0 0 4 * * ?");
        assertThat(callCount[0]).isEqualTo(1);
    }

    @Test
    @DisplayName("updateSystemConfig 修改 search-cron 但值未变化不触发监听器")
    void updateSystemConfig_searchCronSameValue_noTrigger() {
        SystemConfig cfg = new SystemConfig();
        cfg.setConfigKey("scheduler.search-cron");
        cfg.setConfigValue("0 0 8 * * ?");
        when(systemRepo.findByConfigKey("scheduler.search-cron")).thenReturn(Optional.of(cfg));
        when(systemRepo.save(any())).thenReturn(cfg);

        int[] callCount = {0};
        configService.registerCronChangeListener(() -> callCount[0]++);

        configService.updateSystemConfig("scheduler.search-cron", "0 0 8 * * ?");
        assertThat(callCount[0]).isZero();
    }
}
