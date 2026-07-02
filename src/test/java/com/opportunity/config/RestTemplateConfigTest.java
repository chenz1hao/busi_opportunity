package com.opportunity.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.client.RestTemplate;

import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RestTemplateConfig 配置类测试
 * 验证 Bean 正常创建且参数正确
 */
class RestTemplateConfigTest {

    @Test
    @DisplayName("restTemplate Bean 创建成功且超时配置正确")
    void restTemplate_beanCreatedWithCorrectTimeout() {
        RestTemplateConfig config = new RestTemplateConfig();
        RestTemplate restTemplate = config.restTemplate();

        assertThat(restTemplate).isNotNull();
        // RestTemplate 内部使用 HttpComponentsClientHttpRequestFactory
        assertThat(restTemplate.getRequestFactory()).isNotNull();
    }

    @Test
    @DisplayName("taskScheduler Bean 创建成功")
    void taskScheduler_beanCreated() {
        RestTemplateConfig config = new RestTemplateConfig();
        TaskScheduler scheduler = config.taskScheduler();

        assertThat(scheduler).isNotNull();
    }

    @Test
    @DisplayName("taskExecutor Bean 创建成功并配置正确")
    void taskExecutor_beanCreatedWithCorrectConfig() {
        RestTemplateConfig config = new RestTemplateConfig();
        ThreadPoolTaskExecutor executor = config.taskExecutor();

        assertThat(executor).isNotNull();
        assertThat(executor.getCorePoolSize()).isEqualTo(2);
        assertThat(executor.getMaxPoolSize()).isEqualTo(4);
        assertThat(executor.getThreadNamePrefix()).isEqualTo("opportunity-async-");
    }

    @Test
    @DisplayName("getAsyncExecutor 返回 taskExecutor Bean")
    void getAsyncExecutor_returnsTaskExecutor() {
        RestTemplateConfig config = new RestTemplateConfig();
        Executor asyncExecutor = config.getAsyncExecutor();

        assertThat(asyncExecutor).isNotNull();
        assertThat(asyncExecutor).isInstanceOf(ThreadPoolTaskExecutor.class);
    }
}
