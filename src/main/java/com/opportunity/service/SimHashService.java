package com.opportunity.service;

import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * SimHash 局部敏感哈希服务（64 位）
 * 用于对线索网页正文主体进行内容级去重，相比标题级余弦相似度更合理：
 * SimHash 对局部修改不敏感，相同/近似文本的海明距离很小。
 *
 * 相似度定义：1 - hammingDistance / 64，取值 [0, 1]：
 *  - 完全相同 → 海明距离 0 → 相似度 1.0
 *  - 完全不同 → 海明距离 64 → 相似度 0.0
 */
@Service
public class SimHashService {

    private static final int BITS = 64;

    /**
     * 计算文本的 64 位 SimHash 指纹
     * @param text 文本（正文主体）
     * @return 64 位指纹；text 为 null/空时返回 null
     */
    public Long computeSimHash(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        // 分词并统计词频（字符级 bigram + unigram）
        Map<String, Integer> features = tokenize(text);
        if (features.isEmpty()) {
            return null;
        }

        // 64 维投票累加器
        int[] accum = new int[BITS];
        for (Map.Entry<String, Integer> e : features.entrySet()) {
            long hash = hash64(e.getKey());
            int weight = e.getValue();
            for (int i = 0; i < BITS; i++) {
                if (((hash >>> i) & 1L) == 1L) {
                    accum[i] += weight;
                } else {
                    accum[i] -= weight;
                }
            }
        }

        // 投票结果打包为 long
        long fingerprint = 0L;
        for (int i = 0; i < BITS; i++) {
            if (accum[i] > 0) {
                fingerprint |= (1L << i);
            }
        }
        return fingerprint;
    }

    /**
     * 计算两个指纹的海明距离
     */
    public int hammingDistance(Long a, Long b) {
        if (a == null || b == null) {
            return BITS;
        }
        return Long.bitCount(a ^ b);
    }

    /**
     * 计算两个指纹的相似度 [0, 1]
     */
    public double similarity(Long a, Long b) {
        if (a == null || b == null) {
            return 0.0;
        }
        return 1.0 - hammingDistance(a, b) / (double) BITS;
    }

    /**
     * 获取新内容指纹与已有指纹库的最大相似度
     * @param newHash 新内容指纹
     * @param existingHashes 已有指纹列表（可能含 null，自动跳过）
     * @return 最大相似度；列表为空或全 null 时返回 0.0
     */
    public double getMaxSimilarity(Long newHash, List<Long> existingHashes) {
        if (newHash == null || existingHashes == null || existingHashes.isEmpty()) {
            return 0.0;
        }
        double max = 0.0;
        for (Long existing : existingHashes) {
            if (existing == null) {
                continue;
            }
            double sim = similarity(newHash, existing);
            if (sim > max) {
                max = sim;
            }
        }
        return max;
    }

    /**
     * 分词：字符级 bigram + unigram，预处理去除非中文/数字/字母字符
     * 与原余弦相似度方案保持一致的粒度，便于平滑迁移
     */
    private Map<String, Integer> tokenize(String text) {
        Map<String, Integer> freq = new HashMap<>();
        String cleaned = text.replaceAll("[^\\u4e00-\\u9fa5a-zA-Z0-9]", "");
        if (cleaned.isEmpty()) {
            return freq;
        }
        // bigram
        for (int i = 0; i < cleaned.length() - 1; i++) {
            freq.merge(cleaned.substring(i, i + 2), 1, Integer::sum);
        }
        // unigram 补充
        for (int i = 0; i < cleaned.length(); i++) {
            freq.merge(cleaned.substring(i, i + 1), 1, Integer::sum);
        }
        return freq;
    }

    /**
     * 64 位字符串哈希（自包含，不依赖外部库）
     * 采用多项式累加 + MurmurHash3 finalizer 混合，保证良好的位雪崩效应，
     * 避免无关特征哈希值过度聚集导致 SimHash 指纹误相似。
     */
    private long hash64(String s) {
        long h = 0L;
        for (int i = 0; i < s.length(); i++) {
            h = h * 31L + s.charAt(i);
        }
        // MurmurHash3 finalizer（avalanche），让每一位输入变化均匀影响所有输出位
        h ^= (h >>> 33);
        h *= 0xff51afd7ed558ccdL;
        h ^= (h >>> 33);
        h *= 0xc4ceb9fe1a85ec53L;
        h ^= (h >>> 33);
        return h;
    }
}
