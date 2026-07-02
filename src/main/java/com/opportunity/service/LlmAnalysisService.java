package com.opportunity.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opportunity.entity.WebPageInfo;
import com.opportunity.exception.ApiCallException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 通用大模型评分分析服务
 * 使用火山引擎 ARK Chat Completions API 进行线索分析
 */
@Service
public class LlmAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(LlmAnalysisService.class);

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final ConfigService configService;

    @Value("${volcengine.ark-api-key}")
    private String apiKey;

    @Value("${volcengine.ark-base-url}")
    private String baseUrl;

    /**
     * 线索价值评估模型 —— 评分规则说明
     */
    private static final String SCORING_RULES =
            "【线索价值评估模型 - 评分规则】\n" +
            "总分100分，由以下维度加权计算：\n\n" +
            "1. 文件类型分（权重40%）：\n" +
            "   - 招标公告/采购公告/竞争性磋商公告：40分\n" +
            "   - 其他政府文件/通知：20分\n" +
            "   - 新闻/行业资讯：10分\n" +
            "   - 其他：5分\n\n" +
            "2. 金额规模分（权重25%）：\n" +
            "   - 1000万以上：25分\n" +
            "   - 500万-1000万：20分\n" +
            "   - 100万-500万：15分\n" +
            "   - 100万以下：10分\n" +
            "   - 无法识别金额：5分\n\n" +
            "3. 时效性分（权重20%）：\n" +
            "   - 发布日期在7天内：20分\n" +
            "   - 发布日期在30天内：15分\n" +
            "   - 发布日期在90天内：10分\n" +
            "   - 超过90天或无法识别：5分\n\n" +
            "4. 行政区划明确度（权重10%）：\n" +
            "   - 精确到区县级：10分\n" +
            "   - 精确到市级：7分\n" +
            "   - 仅精确到省级或无法识别：3分\n\n" +
            "5. 发布主体权威性（权重5%）：\n" +
            "   - 政府机关/事业单位：5分\n" +
            "   - 国有企业：4分\n" +
            "   - 其他：2分";

    public LlmAnalysisService(RestTemplate restTemplate, ObjectMapper objectMapper, ConfigService configService) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.configService = configService;
    }

    /**
     * 批量分析未评分的记录（兼容旧调用，内部按 batch 切分）
     */
    public List<AnalysisResult> batchAnalyze(List<WebPageInfo> records) {
        List<AnalysisResult> results = new ArrayList<>();
        if (records == null || records.isEmpty()) {
            return results;
        }

        // 默认每批 10 条（仅作为兼容入口，外部推荐直接调用 analyzeOneBatch）
        int batchSize = 10;
        for (int i = 0; i < records.size(); i += batchSize) {
            int end = Math.min(i + batchSize, records.size());
            List<WebPageInfo> batch = records.subList(i, end);
            try {
                results.addAll(analyzeOneBatch(batch));
            } catch (Exception e) {
                log.error("批量分析失败, batch=[{}, {}], error={}", i, end, e.getMessage(), e);
            }
        }

        return results;
    }

    /**
     * 分析单批记录（一次大模型调用）
     * 调用方负责控制 batch 大小，建议从 ConfigService.getAnalysisBatchSize() 获取
     * @return 该批的分析结果
     */
    public List<AnalysisResult> analyzeOneBatch(List<WebPageInfo> batch) throws Exception {
        List<AnalysisResult> results = new ArrayList<>();
        String url = baseUrl + "/responses";

        // 构建批量分析的提示词
        StringBuilder prompt = new StringBuilder();
        prompt.append(SCORING_RULES).append("\n\n");
        prompt.append("请逐一分析以下线索，对每条线索输出一个JSON对象。所有JSON对象放在一个JSON数组中返回。\n\n");

        for (int i = 0; i < batch.size(); i++) {
            WebPageInfo record = batch.get(i);
            prompt.append("【线索").append(i + 1).append("】\n");
            prompt.append("标题：").append(record.getTitle() != null ? record.getTitle() : "无").append("\n");
            prompt.append("正文：").append(record.getContent() != null ? record.getContent() : "无").append("\n");
            prompt.append("URL：").append(record.getUrl() != null ? record.getUrl() : "无").append("\n");
            prompt.append("发布时间：").append(record.getPublishTime() != null ? record.getPublishTime() : "无").append("\n\n");
        }

        prompt.append("请输出JSON数组格式，每个元素包含以下字段：\n");
        prompt.append("{\"id\": 线索编号, \"province\": \"省\", \"city\": \"市\", \"county\": \"区县\", ");
        prompt.append("\"deadline\": \"yyyyMMdd或null\", \"amount\": \"金额或null\", ");
        prompt.append("\"type_flag\": true/false, \"score\": 0-100整数, ");
        prompt.append("\"score_reason\": \"50字以内\", \"type\": \"政府文件/新闻/其他\"}\n");
        prompt.append("只返回JSON数组，不要有任何其他文字说明。");

        Map<String, Object> requestBody = new HashMap<>();
        // 模型名从数据库读取，支持前端实时修改
        String analysisModel = configService.getAnalysisModel();
        requestBody.put("model", analysisModel);
        log.info("使用分析模型: {}", analysisModel);
        // Responses API: input 直接传字符串（官方最简形式）
        requestBody.put("input", prompt.toString());
        // 关闭深度思考，避免 reasoning 内容干扰 JSON 输出
        Map<String, String> thinking = new HashMap<>();
        thinking.put("type", "disabled");
        requestBody.put("thinking", thinking);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);

        // 请求摘要（用于错误信息）
        String requestSummary = "POST " + url + ", model=" + analysisModel + ", batch=" + batch.size() + "条线索";

        ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, entity, String.class);
        String body = response.getBody();
        log.info("大模型完整返回: {}", body);

        // 1. 校验 HTTP 状态码（restTemplate 默认对 4xx/5xx 抛异常，这里二次防护）
        if (!response.getStatusCode().is2xxSuccessful()) {
            throw new ApiCallException("大模型分析接口", requestSummary,
                    response.getStatusCodeValue(), body);
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(body);
        } catch (Exception e) {
            throw new RuntimeException("大模型响应JSON解析失败: " + e.getMessage() + ", body=" + truncate(body, 500), e);
        }

        // 2. 校验响应体是否包含 error 字段（如限流、模型未开通等业务错误，HTTP 可能仍是 200）
        JsonNode errorNode = root.path("error");
        if (!errorNode.isMissingNode() && !errorNode.isNull()) {
            String errorCode = errorNode.path("code").asText("");
            String errorMsg = errorNode.path("message").asText("");
            throw new RuntimeException("大模型接口返回错误: code=" + errorCode + ", message=" + errorMsg);
        }

        // 3. 校验 output 数组存在且非空
        JsonNode outputArray = root.path("output");
        if (!outputArray.isArray() || outputArray.isEmpty()) {
            throw new RuntimeException("大模型响应缺少 output 数组或为空, body=" + truncate(body, 500));
        }

        // 4. 提取 message 中的 output_text
        // Responses API 返回格式: output 数组中 type="message" 的元素的 content 数组中 type="output_text" 的 text 字段
        String content = "";
        for (JsonNode outputItem : outputArray) {
            if ("message".equals(outputItem.path("type").asText())) {
                JsonNode contentArray = outputItem.path("content");
                if (contentArray.isArray()) {
                    for (JsonNode contentItem : contentArray) {
                        if ("output_text".equals(contentItem.path("type").asText())) {
                            content = contentItem.path("text").asText();
                            break;
                        }
                    }
                }
            }
            if (!content.isEmpty()) break;
        }

        // 5. 校验提取到的文本内容非空
        if (content == null || content.trim().isEmpty()) {
            throw new RuntimeException("大模型响应未返回有效文本内容(output_text 为空), body=" + truncate(body, 500));
        }
        log.info("大模型返回内容: {}", content);

        // 6. 解析JSON数组
        JsonNode analysisArray = extractJsonArray(content);
        if (analysisArray == null || !analysisArray.isArray() || analysisArray.isEmpty()) {
            throw new RuntimeException("大模型返回内容无法解析为JSON数组: " + truncate(content, 500));
        }

        for (int i = 0; i < analysisArray.size(); i++) {
            JsonNode item = analysisArray.get(i);
            AnalysisResult result = new AnalysisResult();
            result.setRecordIndex(item.path("id").asInt() - 1); // 线索编号从1开始

            if (result.getRecordIndex() >= 0 && result.getRecordIndex() < batch.size()) {
                result.setRecordId(batch.get(result.getRecordIndex()).getId());
            }

            result.setProvince(item.path("province").asText(null));
            result.setCity(item.path("city").asText(null));
            result.setCounty(item.path("county").asText(null));
            result.setDeadline(item.path("deadline").asText(null));
            result.setAmount(item.path("amount").asText(null));
            result.setTypeFlag(item.path("type_flag").asBoolean());
            result.setScore(item.path("score").asInt());
            result.setScoreReason(item.path("score_reason").asText(""));
            result.setType(item.path("type").asText("其他"));

            results.add(result);
        }

        // 7. 校验至少解析出一条结果
        if (results.isEmpty()) {
            throw new RuntimeException("大模型返回内容解析后无有效结果: " + truncate(content, 500));
        }

        return results;
    }

    /** 截断字符串到指定长度 */
    private static String truncate(String str, int maxLen) {
        if (str == null) return "";
        return str.length() > maxLen ? str.substring(0, maxLen) + "..." : str;
    }

    /**
     * 从大模型返回的文本中提取JSON数组
     */
    private JsonNode extractJsonArray(String content) {
        try {
            // 查找 JSON 数组的起始位置
            int start = content.indexOf('[');
            int end = content.lastIndexOf(']');
            if (start >= 0 && end > start) {
                String jsonStr = content.substring(start, end + 1);
                return objectMapper.readTree(jsonStr);
            }
            // 如果没有数组，尝试直接解析为对象
            return objectMapper.readTree(content);
        } catch (Exception e) {
            log.error("解析JSON失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 分析结果内部类
     */
    public static class AnalysisResult {
        private int recordIndex;
        private Long recordId;
        private String province;
        private String city;
        private String county;
        private String deadline;
        private String amount;
        private Boolean typeFlag;
        private Integer score;
        private String scoreReason;
        private String type;

        public int getRecordIndex() { return recordIndex; }
        public void setRecordIndex(int recordIndex) { this.recordIndex = recordIndex; }

        public Long getRecordId() { return recordId; }
        public void setRecordId(Long recordId) { this.recordId = recordId; }

        public String getProvince() { return province; }
        public void setProvince(String province) { this.province = province; }

        public String getCity() { return city; }
        public void setCity(String city) { this.city = city; }

        public String getCounty() { return county; }
        public void setCounty(String county) { this.county = county; }

        public String getDeadline() { return deadline; }
        public void setDeadline(String deadline) { this.deadline = deadline; }

        public String getAmount() { return amount; }
        public void setAmount(String amount) { this.amount = amount; }

        public Boolean getTypeFlag() { return typeFlag; }
        public void setTypeFlag(Boolean typeFlag) { this.typeFlag = typeFlag; }

        public Integer getScore() { return score; }
        public void setScore(Integer score) { this.score = score; }

        public String getScoreReason() { return scoreReason; }
        public void setScoreReason(String scoreReason) { this.scoreReason = scoreReason; }

        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
    }
}