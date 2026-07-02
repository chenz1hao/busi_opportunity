package com.opportunity;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 主启动类上下文加载测试
 */
@SpringBootTest
@ActiveProfiles("test")
class OpportunityApplicationTest {

    @Test
    void contextLoads() {
        // 验证 Spring 上下文能正常启动
        assertThat(System.getProperty("java.version")).isNotNull();
    }

    @Test
    void mainClassCanBeReferenced() {
        // 验证主类可被反射访问
        assertThat(OpportunityApplication.class.getSimpleName()).isEqualTo("OpportunityApplication");
    }
}
