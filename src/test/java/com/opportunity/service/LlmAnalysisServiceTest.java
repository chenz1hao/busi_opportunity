package com.opportunity.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opportunity.entity.WebPageInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 大模型分析服务测试
 * 覆盖 7 层校验：HTTP状态码、JSON解析、error字段、output数组、output_text、JSON数组解析、结果非空
 * 以及正常场景、batchAnalyze 兼容入口、extractJsonArray 容错
 */
class LlmAnalysisServiceTest {

    private RestTemplate restTemplate;
    private ConfigService configService;
    private LlmAnalysisService service;

    @BeforeEach
    void setUp() {
        restTemplate = mock(RestTemplate.class);
        configService = mock(ConfigService.class);
        service = new LlmAnalysisService(restTemplate, new ObjectMapper(), configService);
        ReflectionTestUtils.setField(service, "apiKey", "test-ark-key");
        ReflectionTestUtils.setField(service, "baseUrl", "http://localhost:9999/api/v3");
        when(configService.getAnalysisModel()).thenReturn("doubao-test-model");
    }

    private WebPageInfo buildRecord(Long id, String title, String content, String url) {
        WebPageInfo r = new WebPageInfo();
        r.setId(id);
        r.setTitle(title);
        r.setContent(content);
        r.setUrl(url);
        r.setPublishTime("2025-05-30");
        return r;
    }

    /** 构造一个标准成功响应体（text 字段是 JSON 字符串，需转义内部双引号） */
    private String buildSuccessBody(String jsonArrayContent) {
        // 将 jsonArrayContent 作为 JSON 字符串值放入 text 字段
        String escapedText = jsonArrayContent.replace("\"", "\\\"");
        return "{\"output\":[{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\""
                + escapedText + "\"}]}]}";
    }

    // ========== 正常场景 ==========

    @Test
    @DisplayName("正常响应：解析返回 AnalysisResult 列表")
    void analyzeOneBatch_normalResponse_returnsResults() throws Exception {
        WebPageInfo r1 = buildRecord(1L, "标题1", "正文1", "http://a.com/1");
        WebPageInfo r2 = buildRecord(2L, "标题2", "正文2", "http://b.com/2");

        String jsonArray = "[{\"id\":1,\"province\":\"北京市\",\"city\":\"北京市\",\"county\":\"海淀区\","
                + "\"deadline\":\"20250630\",\"amount\":\"500万\",\"type_flag\":true,\"score\":85,"
                + "\"score_reason\":\"高分招标\",\"type\":\"政府文件\"},"
                + "{\"id\":2,\"province\":\"上海市\",\"city\":\"上海市\",\"county\":\"浦东新区\","
                + "\"deadline\":\"20250731\",\"amount\":\"1000万\",\"type_flag\":false,\"score\":60,"
                + "\"score_reason\":\"中等评分\",\"type\":\"新闻\"}]";

        when(restTemplate.exchange(anyString(), any(), any(), eq(String.class)))
                .thenReturn(new ResponseEntity<>(buildSuccessBody(jsonArray), HttpStatus.OK));

        List<LlmAnalysisService.AnalysisResult> results = service.analyzeOneBatch(Arrays.asList(r1, r2));

        assertThat(results).hasSize(2);
        assertThat(results.get(0).getRecordId()).isEqualTo(1L);
        assertThat(results.get(0).getProvince()).isEqualTo("北京市");
        assertThat(results.get(0).getScore()).isEqualTo(85);
        assertThat(results.get(0).getTypeFlag()).isTrue();
        assertThat(results.get(0).getType()).isEqualTo("政府文件");
        assertThat(results.get(1).getRecordId()).isEqualTo(2L);
        assertThat(results.get(1).getAmount()).isEqualTo("1000万");
    }

    @Test
    @DisplayName("大模型返回的 JSON 数组前后带额外说明文字时仍能解析")
    void analyzeOneBatch_jsonArrayWithExtraText_parsesCorrectly() throws Exception {
        WebPageInfo r1 = buildRecord(1L, "标题1", "正文1", "http://a.com/1");
        String contentWithExtra = "以下是分析结果：[{\"id\":1,\"province\":\"北京市\",\"city\":\"北京市\","
                + "\"county\":null,\"deadline\":null,\"amount\":null,\"type_flag\":true,\"score\":80,"
                + "\"score_reason\":\"ok\",\"type\":\"政府文件\"}] 以上即全部结果。";
        when(restTemplate.exchange(anyString(), any(), any(), eq(String.class)))
                .thenReturn(new ResponseEntity<>(buildSuccessBody(contentWithExtra), HttpStatus.OK));

        List<LlmAnalysisService.AnalysisResult> results = service.analyzeOneBatch(Collections.singletonList(r1));
        assertThat(results).hasSize(1);
        assertThat(results.get(0).getProvince()).isEqualTo("北京市");
    }

    @Test
    @DisplayName("AnalysisResult 字段缺失时使用默认值（type 默认 其他）")
    void analyzeOneBatch_missingFields_usesDefaults() throws Exception {
        WebPageInfo r1 = buildRecord(1L, "标题1", "正文1", "http://a.com/1");
        String jsonArray = "[{\"id\":1}]"; // 仅 id
        when(restTemplate.exchange(anyString(), any(), any(), eq(String.class)))
                .thenReturn(new ResponseEntity<>(buildSuccessBody(jsonArray), HttpStatus.OK));

        List<LlmAnalysisService.AnalysisResult> results = service.analyzeOneBatch(Collections.singletonList(r1));
        assertThat(results).hasSize(1);
        assertThat(results.get(0).getType()).isEqualTo("其他"); // 默认值
        assertThat(results.get(0).getScoreReason()).isEmpty();
    }

    @Test
    @DisplayName("大模型返回的 id 超出 batch 范围时不设置 recordId")
    void analyzeOneBatch_idOutOfRange_recordIdNull() throws Exception {
        WebPageInfo r1 = buildRecord(1L, "标题1", "正文1", "http://a.com/1");
        // id=99 超出范围
        String jsonArray = "[{\"id\":99,\"score\":50}]";
        when(restTemplate.exchange(anyString(), any(), any(), eq(String.class)))
                .thenReturn(new ResponseEntity<>(buildSuccessBody(jsonArray), HttpStatus.OK));

        List<LlmAnalysisService.AnalysisResult> results = service.analyzeOneBatch(Collections.singletonList(r1));
        assertThat(results).hasSize(1);
        assertThat(results.get(0).getRecordId()).isNull();
    }

    // ========== 7 层校验失败场景 ==========

    @Test
    @DisplayName("校验1: HTTP 状态码非 2xx 抛 ApiCallException")
    void analyzeOneBatch_non2xxStatus_throwsException() {
        when(restTemplate.exchange(anyString(), any(), any(), eq(String.class)))
                .thenReturn(new ResponseEntity<>("error", HttpStatus.INTERNAL_SERVER_ERROR));

        assertThatThrownBy(() -> service.analyzeOneBatch(Collections.singletonList(buildRecord(1L, "t", "c", "u"))))
                .isInstanceOf(com.opportunity.exception.ApiCallException.class)
                .hasMessageContaining("大模型分析接口")
                .hasMessageContaining("HTTP 500");
    }

    @Test
    @DisplayName("校验2: 响应体非合法 JSON 抛 RuntimeException")
    void analyzeOneBatch_invalidJson_throwsException() {
        when(restTemplate.exchange(anyString(), any(), any(), eq(String.class)))
                .thenReturn(new ResponseEntity<>("not a json", HttpStatus.OK));

        assertThatThrownBy(() -> service.analyzeOneBatch(Collections.singletonList(buildRecord(1L, "t", "c", "u"))))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("JSON解析失败");
    }

    @Test
    @DisplayName("校验3: 响应体包含 error 字段（HTTP 200）抛 RuntimeException")
    void analyzeOneBatch_errorField_throwsException() {
        String body = "{\"error\":{\"code\":\"SetLimitExceeded\",\"message\":\"推理限额\"}}";
        when(restTemplate.exchange(anyString(), any(), any(), eq(String.class)))
                .thenReturn(new ResponseEntity<>(body, HttpStatus.OK));

        assertThatThrownBy(() -> service.analyzeOneBatch(Collections.singletonList(buildRecord(1L, "t", "c", "u"))))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("SetLimitExceeded");
    }

    @Test
    @DisplayName("校验4: output 数组缺失抛 RuntimeException")
    void analyzeOneBatch_missingOutputArray_throwsException() {
        when(restTemplate.exchange(anyString(), any(), any(), eq(String.class)))
                .thenReturn(new ResponseEntity<>("{}", HttpStatus.OK));

        assertThatThrownBy(() -> service.analyzeOneBatch(Collections.singletonList(buildRecord(1L, "t", "c", "u"))))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("output");
    }

    @Test
    @DisplayName("校验4: output 数组为空抛 RuntimeException")
    void analyzeOneBatch_emptyOutputArray_throwsException() {
        when(restTemplate.exchange(anyString(), any(), any(), eq(String.class)))
                .thenReturn(new ResponseEntity<>("{\"output\":[]}", HttpStatus.OK));

        assertThatThrownBy(() -> service.analyzeOneBatch(Collections.singletonList(buildRecord(1L, "t", "c", "u"))))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("output");
    }

    @Test
    @DisplayName("校验5: output_text 文本为空抛 RuntimeException")
    void analyzeOneBatch_emptyOutputText_throwsException() {
        // output 中无 type=message 或 output_text 为空字符串
        String body = "{\"output\":[{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"\"}]}]}";
        when(restTemplate.exchange(anyString(), any(), any(), eq(String.class)))
                .thenReturn(new ResponseEntity<>(body, HttpStatus.OK));

        assertThatThrownBy(() -> service.analyzeOneBatch(Collections.singletonList(buildRecord(1L, "t", "c", "u"))))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("output_text");
    }

    @Test
    @DisplayName("校验5: output 中无 type=message 的元素，output_text 为空抛异常")
    void analyzeOneBatch_noMessageType_throwsException() {
        String body = "{\"output\":[{\"type\":\"reasoning\",\"content\":[{\"type\":\"text\",\"text\":\"思考\"}]}]}";
        when(restTemplate.exchange(anyString(), any(), any(), eq(String.class)))
                .thenReturn(new ResponseEntity<>(body, HttpStatus.OK));

        assertThatThrownBy(() -> service.analyzeOneBatch(Collections.singletonList(buildRecord(1L, "t", "c", "u"))))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("output_text");
    }

    @Test
    @DisplayName("校验6: 内容无法解析为 JSON 数组抛 RuntimeException")
    void analyzeOneBatch_contentNotJsonArray_throwsException() {
        String body = buildSuccessBody("这是一段纯文本，不是JSON");
        when(restTemplate.exchange(anyString(), any(), any(), eq(String.class)))
                .thenReturn(new ResponseEntity<>(body, HttpStatus.OK));

        assertThatThrownBy(() -> service.analyzeOneBatch(Collections.singletonList(buildRecord(1L, "t", "c", "u"))))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("无法解析为JSON数组");
    }

    @Test
    @DisplayName("校验6: 内容为空 JSON 对象（非数组）抛 RuntimeException")
    void analyzeOneBatch_emptyJsonObject_throwsException() {
        // extractJsonArray 找不到 [ ]，尝试直接 readTree → 返回对象节点，不是数组
        String body = buildSuccessBody("{\"key\":\"value\"}");
        when(restTemplate.exchange(anyString(), any(), any(), eq(String.class)))
                .thenReturn(new ResponseEntity<>(body, HttpStatus.OK));

        assertThatThrownBy(() -> service.analyzeOneBatch(Collections.singletonList(buildRecord(1L, "t", "c", "u"))))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("无法解析为JSON数组");
    }

    // ========== batchAnalyze 兼容入口 ==========

    @Test
    @DisplayName("batchAnalyze 入参为 null 返回空列表")
    void batchAnalyze_nullInput_returnsEmpty() {
        assertThat(service.batchAnalyze(null)).isEmpty();
    }

    @Test
    @DisplayName("batchAnalyze 入参为空列表返回空列表")
    void batchAnalyze_emptyInput_returnsEmpty() {
        assertThat(service.batchAnalyze(new ArrayList<>())).isEmpty();
    }

    @Test
    @DisplayName("batchAnalyze 单批失败不影响其他批，返回成功批结果")
    void batchAnalyze_partialFailure_returnsSuccessfulResults() {
        WebPageInfo r1 = buildRecord(1L, "标题1", "正文1", "http://a.com/1");
        WebPageInfo r2 = buildRecord(2L, "标题2", "正文2", "http://b.com/2");

        // 第一次调用失败，第二次成功
        String successJson = "[{\"id\":1,\"score\":80,\"type\":\"政府文件\"}]";
        when(restTemplate.exchange(anyString(), any(), any(), eq(String.class)))
                .thenReturn(new ResponseEntity<>("not json", HttpStatus.OK))      // 第1批失败
                .thenReturn(new ResponseEntity<>(buildSuccessBody(successJson), HttpStatus.OK)); // 第2批成功

        // batchAnalyze 默认每批10条，这里给11条强制分2批
        List<WebPageInfo> records = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            records.add(buildRecord((long) (i + 1), "标题" + i, "正文" + i, "http://a.com/" + i));
        }

        List<LlmAnalysisService.AnalysisResult> results = service.batchAnalyze(records);
        // 第1批失败被 catch，第2批成功，但第2批只有1条记录(id=1)，实际取的是第2批的第1条
        assertThat(results).isNotEmpty();
    }

    @Test
    @DisplayName("batchAnalyze 所有批次都失败返回空列表")
    void batchAnalyze_allFail_returnsEmpty() {
        when(restTemplate.exchange(anyString(), any(), any(), eq(String.class)))
                .thenReturn(new ResponseEntity<>("not json", HttpStatus.OK));

        List<WebPageInfo> records = Collections.singletonList(buildRecord(1L, "t", "c", "u"));
        assertThat(service.batchAnalyze(records)).isEmpty();
    }

    // ========== 补充分支覆盖测试 ==========

    @Test
    @DisplayName("线索字段为 null 时 prompt 中填 '无'（覆盖三元 null 分支）")
    void analyzeOneBatch_nullFields_fillsDefaultInPrompt() throws Exception {
        WebPageInfo r = new WebPageInfo();
        r.setId(1L);
        r.setTitle(null);
        r.setContent(null);
        r.setUrl(null);
        r.setPublishTime(null);

        String jsonArray = "[{\"id\":1,\"score\":50,\"type\":\"其他\"}]";
        when(restTemplate.exchange(anyString(), any(), any(), eq(String.class)))
                .thenReturn(new ResponseEntity<>(buildSuccessBody(jsonArray), HttpStatus.OK));

        List<LlmAnalysisService.AnalysisResult> results = service.analyzeOneBatch(Collections.singletonList(r));
        assertThat(results).hasSize(1);
        assertThat(results.get(0).getScore()).isEqualTo(50);
    }

    @Test
    @DisplayName("content 不是数组时跳过并继续查找其他 outputItem")
    void analyzeOneBatch_contentNotArray_continuesToNextOutputItem() throws Exception {
        // 第一个 outputItem: type=message 但 content 不是数组
        // 第二个 outputItem: type=message 且 content 是数组，包含 output_text
        String body = "{\"output\":[" +
                "{\"type\":\"message\",\"content\":\"not-an-array\"}," +
                "{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"[{\\\"id\\\":1,\\\"score\\\":70,\\\"type\\\":\\\"政府文件\\\"}]\"}]}]}";
        when(restTemplate.exchange(anyString(), any(), any(), eq(String.class)))
                .thenReturn(new ResponseEntity<>(body, HttpStatus.OK));

        List<LlmAnalysisService.AnalysisResult> results =
                service.analyzeOneBatch(Collections.singletonList(buildRecord(1L, "t", "c", "u")));
        assertThat(results).hasSize(1);
        assertThat(results.get(0).getScore()).isEqualTo(70);
    }

    @Test
    @DisplayName("contentItem type 非 output_text 时继续遍历下一个 contentItem")
    void analyzeOneBatch_nonOutputTextType_continuesToNextContentItem() throws Exception {
        // content 数组中第一个 item 是 reasoning_text，第二个才是 output_text
        String body = "{\"output\":[{\"type\":\"message\",\"content\":[" +
                "{\"type\":\"reasoning_text\",\"text\":\"思考过程\"}," +
                "{\"type\":\"output_text\",\"text\":\"[{\\\"id\\\":1,\\\"score\\\":65,\\\"type\\\":\\\"新闻\\\"}]\"}]}]}";
        when(restTemplate.exchange(anyString(), any(), any(), eq(String.class)))
                .thenReturn(new ResponseEntity<>(body, HttpStatus.OK));

        List<LlmAnalysisService.AnalysisResult> results =
                service.analyzeOneBatch(Collections.singletonList(buildRecord(1L, "t", "c", "u")));
        assertThat(results).hasSize(1);
        assertThat(results.get(0).getScore()).isEqualTo(65);
    }

    @Test
    @DisplayName("第一个 outputItem 非 message 类型时继续查找下一个")
    void analyzeOneBatch_firstOutputNotMessage_continuesToNext() throws Exception {
        // 第一个 outputItem type=reasoning（非 message），第二个才是 message
        String body = "{\"output\":[" +
                "{\"type\":\"reasoning\",\"content\":[{\"type\":\"text\",\"text\":\"思考\"}]}," +
                "{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"[{\\\"id\\\":1,\\\"score\\\":90,\\\"type\\\":\\\"政府文件\\\"}]\"}]}]}";
        when(restTemplate.exchange(anyString(), any(), any(), eq(String.class)))
                .thenReturn(new ResponseEntity<>(body, HttpStatus.OK));

        List<LlmAnalysisService.AnalysisResult> results =
                service.analyzeOneBatch(Collections.singletonList(buildRecord(1L, "t", "c", "u")));
        assertThat(results).hasSize(1);
        assertThat(results.get(0).getScore()).isEqualTo(90);
    }

    @Test
    @DisplayName("extractJsonArray 遇到括号内非法 JSON 时返回 null 触发异常")
    void analyzeOneBatch_invalidJsonInBrackets_throwsException() {
        // 括号内是非法 JSON，extractJsonArray catch 分支返回 null
        String body = buildSuccessBody("[invalid json content]");
        when(restTemplate.exchange(anyString(), any(), any(), eq(String.class)))
                .thenReturn(new ResponseEntity<>(body, HttpStatus.OK));

        assertThatThrownBy(() -> service.analyzeOneBatch(Collections.singletonList(buildRecord(1L, "t", "c", "u"))))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("无法解析为JSON数组");
    }

    @Test
    @DisplayName("大模型返回的 id 为 0 时 recordIndex 为 -1，不设置 recordId")
    void analyzeOneBatch_idZero_recordIdNull() throws Exception {
        WebPageInfo r1 = buildRecord(1L, "标题1", "正文1", "http://a.com/1");
        // id=0 → recordIndex = -1，不满足 >= 0 条件
        String jsonArray = "[{\"id\":0,\"score\":50,\"type\":\"其他\"}]";
        when(restTemplate.exchange(anyString(), any(), any(), eq(String.class)))
                .thenReturn(new ResponseEntity<>(buildSuccessBody(jsonArray), HttpStatus.OK));

        List<LlmAnalysisService.AnalysisResult> results = service.analyzeOneBatch(Collections.singletonList(r1));
        assertThat(results).hasSize(1);
        assertThat(results.get(0).getRecordId()).isNull();
        assertThat(results.get(0).getScore()).isEqualTo(50);
    }

    @Test
    @DisplayName("大模型返回的 JSON 数组为空数组时抛异常")
    void analyzeOneBatch_emptyJsonArray_throwsException() {
        String body = buildSuccessBody("[]");
        when(restTemplate.exchange(anyString(), any(), any(), eq(String.class)))
                .thenReturn(new ResponseEntity<>(body, HttpStatus.OK));

        assertThatThrownBy(() -> service.analyzeOneBatch(Collections.singletonList(buildRecord(1L, "t", "c", "u"))))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("无法解析为JSON数组");
    }

    @Test
    @DisplayName("大模型返回 content 中只有左括号无右括号时尝试整体解析")
    void analyzeOneBatch_onlyLeftBracket_parsesAsObject() {
        // content 只有 [ 没有 ]，start>=0 但 end<=start，走 readTree(content) 分支
        // content 本身不是合法 JSON 对象 → 解析为文本节点 → 非 array → 抛异常
        String body = buildSuccessBody("[这是不完整的");
        when(restTemplate.exchange(anyString(), any(), any(), eq(String.class)))
                .thenReturn(new ResponseEntity<>(body, HttpStatus.OK));

        assertThatThrownBy(() -> service.analyzeOneBatch(Collections.singletonList(buildRecord(1L, "t", "c", "u"))))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("响应 body 为 null 时 JSON 解析失败抛异常")
    void analyzeOneBatch_nullBody_throwsException() {
        when(restTemplate.exchange(anyString(), any(), any(), eq(String.class)))
                .thenReturn(new ResponseEntity<>(null, HttpStatus.OK));

        assertThatThrownBy(() -> service.analyzeOneBatch(Collections.singletonList(buildRecord(1L, "t", "c", "u"))))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("JSON解析失败");
    }
}
