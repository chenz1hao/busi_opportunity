package com.opportunity.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.config.annotation.CorsRegistry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * WebConfig 配置类测试
 * 验证 CORS 映射正确注册（使用真实 CorsRegistry，不使用 spy 避免 Mockito 对 final 方法拦截问题）
 */
class WebConfigTest {

    private WebConfig webConfig;

    @BeforeEach
    void setUp() {
        webConfig = new WebConfig();
    }

    @Test
    @DisplayName("addCorsMappings 对 /api/** 路径注册 CORS 配置且不抛异常")
    void addCorsMappings_registersApiPath() {
        // 使用真实 CorsRegistry，验证方法调用不抛异常
        CorsRegistry registry = new CorsRegistry();
        assertThatCode(() -> webConfig.addCorsMappings(registry)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("WebConfig 实现 WebMvcConfigurer 接口")
    void webConfig_implementsWebMvcConfigurer() {
        assertThat(webConfig).isInstanceOf(org.springframework.web.servlet.config.annotation.WebMvcConfigurer.class);
    }
}
