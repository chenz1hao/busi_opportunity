package com.opportunity.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opportunity.exception.ApiCallException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.*;

/**
 * 火山引擎联网搜索服务（豆包搜索Custom版）
 * 接口文档: https://www.volcengine.com/docs/87772/2272953
 * 接口地址: https://open.feedcoopapi.com/search_api/web_search
 * 认证方式: Authorization: Bearer <API_KEY>
 */
@Service
public class VolcengineSearchService {

    private static final Logger log = LoggerFactory.getLogger(VolcengineSearchService.class);

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Value("${volcengine.api-key}")
    private String apiKey;

    @Value("${volcengine.search-url}")
    private String searchUrl;

    public VolcengineSearchService(RestTemplate restTemplate, ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 搜索商机信息
     * @param cityName 城市名称
     * @return 搜索结果列表
     * @throws ApiCallException 当接口返回非200响应码时抛出，携带接口名/请求摘要/响应摘要
     */
    public List<SearchResult> searchOpportunities(String cityName) {
        String query = buildSearchQuery(cityName);
        log.info("搜索城市: {}, 查询语句: {}", cityName, query);

        String responseBody = callSearchApi(query);
        return parseSearchResults(responseBody, cityName);
    }

    /**
     * 构建搜索提示词
     * 城市名称在代码中实现拼接
     */
    private String buildSearchQuery(String cityName) {
        return cityName + "代发、工资代发、薪酬发放、银行代发、财政代发、农民工工资专户等相关的政府机关、事业单位、国有企业招标公告、采购公告、竞争性磋商公告，不包含流标、废标、终止、更正公告、补遗、中标结果、合同公示等过时或非招标阶段的信息";
    }

    /** 单次请求最大重试次数（针对限流 700429） */
    private static final int MAX_RETRY = 3;
    /** 限流时首次等待毫秒数（指数退避：2s → 4s → 8s）——非 final 以便测试中缩短等待 */
    private long retryBaseWaitMs = 2000L;

    /**
     * 调用豆包搜索Custom版接口
     * POST https://open.feedcoopapi.com/search_api/web_search
     * 遇到限流(700429)时自动指数退避重试
     * @throws ApiCallException 当接口返回非200响应码时抛出
     */
    private String callSearchApi(String query) {
        // 构建请求体
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("Query", query);
        requestBody.put("SearchType", "web");
        requestBody.put("Count", 20);

        // 过滤条件: 仅返回有正文和URL的结果
        Map<String, Object> filter = new HashMap<>();
        filter.put("NeedContent", true);
        filter.put("NeedUrl", true);
        requestBody.put("Filter", filter);

        // 需要摘要（用于大模型场景）
        requestBody.put("NeedSummary", true);

        // 时间范围: 一年内
        requestBody.put("TimeRange", "OneYear");

        // 请求头
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);

        // 请求摘要（用于错误信息，控制长度）
        String requestSummary = "POST " + searchUrl + ", Query=" + truncate(query, 100);

        log.info("调用联网搜索接口: url={}", searchUrl);

        // 指数退避重试：遇到限流 700429 自动等待后重试
        Exception lastException = null;
        for (int attempt = 0; attempt <= MAX_RETRY; attempt++) {
            try {
                ResponseEntity<String> response = restTemplate.exchange(searchUrl, HttpMethod.POST, entity, String.class);

                if (!response.getStatusCode().is2xxSuccessful()) {
                    String body = response.getBody();
                    log.error("搜索接口返回非200状态: status={}, body={}", response.getStatusCode(), body);
                    throw new ApiCallException("联网搜索接口", requestSummary,
                            response.getStatusCodeValue(), body);
                }

                // 检查响应体是否包含限流错误码 700429
                String body = response.getBody();
                if (body != null && body.contains("700429")) {
                    if (attempt < MAX_RETRY) {
                        long waitMs = retryBaseWaitMs * (1L << attempt); // 2s, 4s, 8s
                        log.warn("触发搜索接口限流(700429), 第{}次重试, 等待{}ms后重试", attempt + 1, waitMs);
                        try { Thread.sleep(waitMs); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); throw new RuntimeException(ie); }
                        continue;
                    } else {
                        log.error("搜索接口限流, 已达最大重试次数 {}", MAX_RETRY);
                        throw new ApiCallException("联网搜索接口", requestSummary,
                                response.getStatusCodeValue(), "限流(700429), 已重试" + MAX_RETRY + "次仍失败");
                    }
                }

                return body;
            } catch (ApiCallException e) {
                throw e; // 非200和限流耗尽直接抛出，不重试
            } catch (Exception e) {
                lastException = e;
                // 网络异常也尝试重试
                if (attempt < MAX_RETRY) {
                    long waitMs = retryBaseWaitMs * (1L << attempt);
                    log.warn("搜索接口调用异常: {}, 第{}次重试, 等待{}ms", e.getMessage(), attempt + 1, waitMs);
                    try { Thread.sleep(waitMs); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); throw new RuntimeException(ie); }
                }
            }
        }
        // 网络异常重试耗尽
        throw new ApiCallException("联网搜索接口", requestSummary, 0,
                "网络异常, 已重试" + MAX_RETRY + "次仍失败: " + (lastException != null ? lastException.getMessage() : "unknown"),
                lastException);
    }

    /** 截断字符串到指定长度 */
    private static String truncate(String str, int maxLen) {
        if (str == null) return "";
        return str.length() > maxLen ? str.substring(0, maxLen) + "..." : str;
    }

    /**
     * 解析搜索结果
     * 响应格式:
     * {
     *   "ResponseMetadata": {...},
     *   "Result": {
     *     "ResultCount": 2,
     *     "WebResults": [
     *       {
     *         "Id": "...",
     *         "Title": "标题",
     *         "SiteName": "站点名",
     *         "Url": "https://...",
     *         "Snippet": "简短片段",
     *         "Summary": "相关摘要",
     *         "Content": "正文",
     *         "PublishTime": "2025-05-30T19:35:24+08:00"
     *       }
     *     ]
     *   }
     * }
     */
    private List<SearchResult> parseSearchResults(String responseBody, String sourceCity) {
        List<SearchResult> results = new ArrayList<>();
        if (responseBody == null || responseBody.isEmpty()) {
            log.warn("搜索响应为空");
            return results;
        }

        try {
            JsonNode root = objectMapper.readTree(responseBody);

            // 检查错误信息（ResponseMetadata.Error 路径，如配额耗尽 10406）
            JsonNode errorNode = root.path("ResponseMetadata").path("Error");
            if (!errorNode.isMissingNode() && !errorNode.isNull()) {
                String code = errorNode.path("Code").asText("");
                String message = errorNode.path("Message").asText("");
                log.error("搜索接口返回错误: code={}, message={}", code, message);
                throw new ApiCallException("联网搜索接口",
                        "城市=" + sourceCity, 200,
                        "Code=" + code + ", Message=" + message);
            }

            // 检查顶层错误（部分错误直接返回在顶层，如 {"Code":"10406","Message":"..."}）
            JsonNode topCode = root.path("Code");
            JsonNode topMessage = root.path("Message");
            if (!topCode.isMissingNode() && !topCode.isNull()
                    && root.path("Result").isMissingNode()) {
                String code = topCode.asText("");
                String message = topMessage.asText("");
                log.error("搜索接口返回顶层错误: code={}, message={}", code, message);
                throw new ApiCallException("联网搜索接口",
                        "城市=" + sourceCity, 200,
                        "Code=" + code + ", Message=" + message);
            }

            // 解析 Result.WebResults
            JsonNode resultNode = root.path("Result");
            if (resultNode.isMissingNode() || resultNode.isNull()) {
                log.warn("搜索结果Result为空");
                return results;
            }

            int resultCount = resultNode.path("ResultCount").asInt(0);
            log.info("搜索到 {} 条结果", resultCount);

            JsonNode webResults = resultNode.path("WebResults");
            if (webResults.isArray()) {
                for (JsonNode item : webResults) {
                    SearchResult result = new SearchResult();
                    result.setTitle(getTextValue(item, "Title"));
                    result.setUrl(getTextValue(item, "Url"));
                    // 优先使用Summary（500-1000字，适合大模型场景），其次Content，最后Snippet
                    String summary = getTextValue(item, "Summary");
                    String content = getTextValue(item, "Content");
                    String snippet = getTextValue(item, "Snippet");
                    if (summary != null && !summary.isEmpty()) {
                        result.setContent(summary);
                    } else if (content != null && !content.isEmpty()) {
                        result.setContent(content);
                    } else {
                        result.setContent(snippet);
                    }
                    result.setPublishTime(formatPublishTime(getTextValue(item, "PublishTime")));
                    result.setSourceCity(sourceCity);

                    // 只保留有标题和URL的有效结果
                    if (result.getTitle() != null && !result.getTitle().isEmpty()
                            && result.getUrl() != null && !result.getUrl().isEmpty()) {
                        results.add(result);
                    }
                }
            }

        } catch (ApiCallException e) {
            // 业务错误（如配额耗尽）向上传播，让调用方记录到调度日志
            throw e;
        } catch (Exception e) {
            log.error("解析搜索结果失败: {}", e.getMessage(), e);
        }
        return results;
    }

    /**
     * 安全获取JSON文本值
     */
    private String getTextValue(JsonNode node, String fieldName) {
        JsonNode value = node.get(fieldName);
        if (value == null || value.isNull()) {
            return null;
        }
        return value.asText();
    }

    /**
     * 格式化发布时间
     * 输入格式: 2025-05-30T19:35:24+08:00 (ISO格式)
     * 输出格式: 2025-05-30
     */
    private String formatPublishTime(String publishTime) {
        if (publishTime == null || publishTime.isEmpty()) {
            return null;
        }
        try {
            // ISO格式: 2025-05-30T19:35:24+08:00 -> 取日期部分
            if (publishTime.contains("T")) {
                return publishTime.substring(0, 10);
            }
            return publishTime;
        } catch (Exception e) {
            return publishTime;
        }
    }

    /**
     * 搜索结果内部类
     */
    public static class SearchResult {
        private String title;
        private String content;
        private String url;
        private String publishTime;
        private String sourceCity;

        public String getTitle() { return title; }
        public void setTitle(String title) { this.title = title; }

        public String getContent() { return content; }
        public void setContent(String content) { this.content = content; }

        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }

        public String getPublishTime() { return publishTime; }
        public void setPublishTime(String publishTime) { this.publishTime = publishTime; }

        public String getSourceCity() { return sourceCity; }
        public void setSourceCity(String sourceCity) { this.sourceCity = sourceCity; }
    }
}