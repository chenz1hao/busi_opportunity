package com.opportunity.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SimHash 局部敏感哈希服务测试
 * 覆盖边界：null/空文本、相同文本、近似文本、完全不同文本、海明距离、相似度、getMaxSimilarity
 */
class SimHashServiceTest {

    private SimHashService service;

    @BeforeEach
    void setUp() {
        service = new SimHashService();
    }

    // ========== computeSimHash 测试 ==========

    @Test
    @DisplayName("computeSimHash null 文本返回 null")
    void computeSimHash_null_returnsNull() {
        assertThat(service.computeSimHash(null)).isNull();
    }

    @Test
    @DisplayName("computeSimHash 空字符串返回 null")
    void computeSimHash_empty_returnsNull() {
        assertThat(service.computeSimHash("")).isNull();
    }

    @Test
    @DisplayName("computeSimHash 仅含标点符号（清洗后为空）返回 null")
    void computeSimHash_onlyPunctuation_returnsNull() {
        assertThat(service.computeSimHash("！！！，。；【】")).isNull();
    }

    @Test
    @DisplayName("computeSimHash 相同文本返回相同指纹")
    void computeSimHash_sameText_returnsSameHash() {
        String text = "北京市朝阳区人民政府采购中心关于办公设备采购项目的公开招标公告";
        Long h1 = service.computeSimHash(text);
        Long h2 = service.computeSimHash(text);
        assertThat(h1).isNotNull();
        assertThat(h1).isEqualTo(h2);
    }

    @Test
    @DisplayName("computeSimHash 不同文本返回不同指纹")
    void computeSimHash_differentText_returnsDifferentHash() {
        Long h1 = service.computeSimHash("招标公告办公设备采购项目详细说明内容");
        Long h2 = service.computeSimHash("运维服务云平台建设方案技术规范书");
        assertThat(h1).isNotEqualTo(h2);
    }

    @Test
    @DisplayName("computeSimHash 单字符文本可计算指纹")
    void computeSimHash_singleChar_returnsHash() {
        Long h = service.computeSimHash("a");
        assertThat(h).isNotNull();
    }

    // ========== hammingDistance 测试 ==========

    @Test
    @DisplayName("hammingDistance 两个 null 返回 64")
    void hammingDistance_bothNull_returnsBits() {
        assertThat(service.hammingDistance(null, null)).isEqualTo(64);
    }

    @Test
    @DisplayName("hammingDistance 任一为 null 返回 64")
    void hammingDistance_oneNull_returnsBits() {
        assertThat(service.hammingDistance(123L, null)).isEqualTo(64);
        assertThat(service.hammingDistance(null, 123L)).isEqualTo(64);
    }

    @Test
    @DisplayName("hammingDistance 相同指纹返回 0")
    void hammingDistance_sameHash_returnsZero() {
        Long h = service.computeSimHash("测试文本内容");
        assertThat(service.hammingDistance(h, h)).isZero();
    }

    @Test
    @DisplayName("hammingDistance 不同指纹返回大于 0 的距离")
    void hammingDistance_differentHash_returnsPositive() {
        Long h1 = service.computeSimHash("招标公告办公设备采购项目详细说明内容");
        Long h2 = service.computeSimHash("运维服务云平台建设方案技术规范书");
        assertThat(service.hammingDistance(h1, h2)).isGreaterThan(0);
    }

    // ========== similarity 测试 ==========

    @Test
    @DisplayName("similarity 任一为 null 返回 0.0")
    void similarity_null_returnsZero() {
        assertThat(service.similarity(null, 1L)).isEqualTo(0.0);
        assertThat(service.similarity(1L, null)).isEqualTo(0.0);
    }

    @Test
    @DisplayName("similarity 相同指纹返回 1.0")
    void similarity_sameHash_returnsOne() {
        Long h = service.computeSimHash("测试文本内容");
        assertThat(service.similarity(h, h)).isEqualTo(1.0);
    }

    @Test
    @DisplayName("similarity 近似文本相似度高于完全不同文本")
    void similarity_approximateHigherThanDifferent() {
        Long base = service.computeSimHash("北京市朝阳区人民政府采购中心关于办公设备采购项目的公开招标公告内容详情");
        // 近似：仅末尾几个字不同
        Long approx = service.computeSimHash("北京市朝阳区人民政府采购中心关于办公设备采购项目的公开招标公告内容说明");
        // 完全不同
        Long diff = service.computeSimHash("云计算运维服务平台技术架构设计实施方案与验收标准");
        assertThat(service.similarity(base, approx)).isGreaterThan(service.similarity(base, diff));
    }

    // ========== getMaxSimilarity 测试 ==========

    @Test
    @DisplayName("getMaxSimilarity newHash 为 null 返回 0.0")
    void getMaxSimilarity_nullHash_returnsZero() {
        assertThat(service.getMaxSimilarity(null, Arrays.asList(1L, 2L))).isEqualTo(0.0);
    }

    @Test
    @DisplayName("getMaxSimilarity 列表为 null 返回 0.0")
    void getMaxSimilarity_nullList_returnsZero() {
        assertThat(service.getMaxSimilarity(1L, null)).isEqualTo(0.0);
    }

    @Test
    @DisplayName("getMaxSimilarity 列表为空返回 0.0")
    void getMaxSimilarity_emptyList_returnsZero() {
        assertThat(service.getMaxSimilarity(1L, Collections.emptyList())).isEqualTo(0.0);
    }

    @Test
    @DisplayName("getMaxSimilarity 列表含 null 元素时自动跳过")
    void getMaxSimilarity_listWithNulls_skipsNulls() {
        Long target = service.computeSimHash("招标公告办公设备采购项目详细说明内容");
        Long same = target; // 完全相同
        List<Long> existing = Arrays.asList(null, same, null);
        assertThat(service.getMaxSimilarity(target, existing)).isEqualTo(1.0);
    }

    @Test
    @DisplayName("getMaxSimilarity 返回与已有指纹库的最大相似度")
    void getMaxSimilarity_multipleHashes_returnsMax() {
        Long target = service.computeSimHash("北京市朝阳区人民政府采购中心关于办公设备采购项目的公开招标公告");
        Long approx = service.computeSimHash("北京市朝阳区人民政府采购中心关于办公设备采购项目的公开招标公告内容详情");
        Long diff = service.computeSimHash("云计算运维服务平台技术架构设计实施方案与验收标准");
        double max = service.getMaxSimilarity(target, Arrays.asList(diff, approx));
        assertThat(max).isGreaterThan(0.5);
    }

    @Test
    @DisplayName("getMaxSimilarity 所有指纹都不相似时返回较低值")
    void getMaxSimilarity_allDissimilar_returnsLow() {
        // 使用较长且语义完全不同的文本，避免短文本 SimHash 噪声
        Long target = service.computeSimHash("招标公告办公设备采购项目详细说明内容包含技术参数与验收标准");
        Long d1 = service.computeSimHash("天气预报明日降雨概率较高请市民注意出行安全防范");
        Long d2 = service.computeSimHash("体育赛事直播篮球足球排球乒乓球羽毛球网球决赛精彩回顾");
        double max = service.getMaxSimilarity(target, Arrays.asList(d1, d2));
        assertThat(max).isLessThan(0.9);
    }
}
