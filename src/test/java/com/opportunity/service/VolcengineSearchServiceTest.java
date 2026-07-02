package com.opportunity.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opportunity.exception.ApiCallException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 火山引擎搜索服务测试
 * 覆盖：正常搜索、限流重试、空响应、错误响应、解析失败、发布时间格式化、优先取 Summary
 *
 * 注意：restTemplate.exchange 有多个重载（String url / URI url），
 * 必须使用 anyString() 匹配第一个参数以精确匹配 String 重载，
 * 否则 Mockito 会 stub 错误的重载导致返回 null（NPE）。
 */
class VolcengineSearchServiceTest {

    private RestTemplate restTemplate;
    private VolcengineSearchService service;

    @BeforeEach
    void setUp() {
        restTemplate = mock(RestTemplate.class);
        service = new VolcengineSearchService(restTemplate, new ObjectMapper());
        ReflectionTestUtils.setField(service, "apiKey", "test-key");
        ReflectionTestUtils.setField(service, "searchUrl", "http://localhost:9999/search");
        // 测试中将重试等待缩短到 1ms，避免指数退避导致测试耗时过长
        ReflectionTestUtils.setField(service, "retryBaseWaitMs", 1L);
    }

    // ========== 正常场景 ==========

    @Test
    @DisplayName("正常搜索返回结果，优先使用 Summary 字段")
    void searchOpportunities_normalResponse_returnsResults() {
        String body = "{"
                + "\"Result\":{"
                + "\"ResultCount\":2,"
                + "\"WebResults\":["
                + "{\"Title\":\"北京招标公告\",\"Url\":\"http://a.com/1\",\"Summary\":\"摘要1\",\"Content\":\"正文1\",\"Snippet\":\"片段1\",\"PublishTime\":\"2025-05-30T19:35:24+08:00\"},"
                + "{\"Title\":\"上海采购公告\",\"Url\":\"http://b.com/2\",\"Summary\":\"\",\"Content\":\"正文2\",\"Snippet\":\"片段2\",\"PublishTime\":\"2025-06-01T10:00:00+08:00\"}"
                + "]}}";
        when(restTemplate.exchange(anyString(), any(), any(), eq(String.class)))
                .thenReturn(new ResponseEntity<>(body, HttpStatus.OK));

        List<VolcengineSearchService.SearchResult> results = service.searchOpportunities("北京市");

        assertThat(results).hasSize(2);
        assertThat(results.get(0).getTitle()).isEqualTo("北京招标公告");
        assertThat(results.get(0).getContent()).isEqualTo("摘要1"); // 优先 Summary
        assertThat(results.get(0).getSourceCity()).isEqualTo("北京市");
        assertThat(results.get(0).getPublishTime()).isEqualTo("2025-05-30");
        // Summary 为空，退回 Content
        assertThat(results.get(1).getContent()).isEqualTo("正文2");
        assertThat(results.get(1).getPublishTime()).isEqualTo("2025-06-01");
    }

    @Test
    @DisplayName("Summary 与 Content 均为空时使用 Snippet")
    void searchOpportunities_onlySnippetAvailable() {
        String body = "{"
                + "\"Result\":{"
                + "\"ResultCount\":1,"
                + "\"WebResults\":["
                + "{\"Title\":\"标题1\",\"Url\":\"http://a.com/1\",\"Summary\":\"\",\"Content\":\"\",\"Snippet\":\"片段内容\",\"PublishTime\":\"\"}"
                + "]}}";
        when(restTemplate.exchange(anyString(), any(), any(), eq(String.class)))
                .thenReturn(new ResponseEntity<>(body, HttpStatus.OK));

        List<VolcengineSearchService.SearchResult> results = service.searchOpportunities("北京市");
        assertThat(results).hasSize(1);
        assertThat(results.get(0).getContent()).isEqualTo("片段内容");
        assertThat(results.get(0).getPublishTime()).isNull();
    }

    @Test
    @DisplayName("标题或URL为空的记录被过滤掉")
    void searchOpportunities_emptyTitleOrUrl_filtered() {
        String body = "{"
                + "\"Result\":{"
                + "\"ResultCount\":3,"
                + "\"WebResults\":["
                + "{\"Title\":\"\",\"Url\":\"http://a.com/1\",\"Summary\":\"s1\"},"
                + "{\"Title\":\"标题2\",\"Url\":\"\",\"Summary\":\"s2\"},"
                + "{\"Title\":\"标题3\",\"Url\":\"http://c.com/3\",\"Summary\":\"s3\"}"
                + "]}}";
        when(restTemplate.exchange(anyString(), any(), any(), eq(String.class)))
                .thenReturn(new ResponseEntity<>(body, HttpStatus.OK));

        List<VolcengineSearchService.SearchResult> results = service.searchOpportunities("北京市");
        assertThat(results).hasSize(1);
        assertThat(results.get(0).getTitle()).isEqualTo("标题3");
    }

    // ========== 边界与异常场景 ==========

    @Test
    @DisplayName("响应体为 null 时返回空列表")
    void searchOpportunities_nullBody_returnsEmpty() {
        when(restTemplate.exchange(anyString(), any(), any(), eq(String.class)))
                .thenReturn(new ResponseEntity<>(null, HttpStatus.OK));

        List<VolcengineSearchService.SearchResult> results = service.searchOpportunities("北京市");
        assertThat(results).isEmpty();
    }

    @Test
    @DisplayName("响应体为空字符串时返回空列表")
    void searchOpportunities_emptyBody_returnsEmpty() {
        when(restTemplate.exchange(anyString(), any(), any(), eq(String.class)))
                .thenReturn(new ResponseEntity<>("", HttpStatus.OK));

        assertThat(service.searchOpportunities("北京市")).isEmpty();
    }

    @Test
    @DisplayName("响应包含 Error 字段（含 700429）触发限流重试耗尽后抛 ApiCallException")
    void searchOpportunities_responseWithError_returnsEmpty() {
        String body = "{\"ResponseMetadata\":{\"Error\":{\"Code\":\"700429\",\"Message\":\"限流\"}}}";
        when(restTemplate.exchange(anyString(), any(), any(), eq(String.class)))
                .thenReturn(new ResponseEntity<>(body, HttpStatus.OK));

        // body 含 "700429" 触发限流重试，重试耗尽后抛 ApiCallException
        assertThatThrownBy(() -> service.searchOpportunities("北京市"))
                .isInstanceOf(ApiCallException.class);
    }

    @Test
    @DisplayName("HTTP 200 但 ResponseMetadata.Error 含业务错误（如配额耗尽 10406）抛 ApiCallException")
    void searchOpportunities_businessErrorInMetadata_throwsApiCallException() {
        String body = "{\"ResponseMetadata\":{\"Error\":{"
                + "\"CodeN\":10406,\"Code\":\"10406\","
                + "\"Message\":\"Free quota has been exhausted.\"}}}";
        when(restTemplate.exchange(anyString(), any(), any(), eq(String.class)))
                .thenReturn(new ResponseEntity<>(body, HttpStatus.OK));

        assertThatThrownBy(() -> service.searchOpportunities("北京市"))
                .isInstanceOf(ApiCallException.class)
                .hasMessageContaining("联网搜索接口")
                .hasMessageContaining("Code=10406")
                .hasMessageContaining("Free quota has been exhausted");
    }

    @Test
    @DisplayName("HTTP 200 但顶层含错误码（如 {Code:10406,Message:...}）抛 ApiCallException")
    void searchOpportunities_topLevelError_throwsApiCallException() {
        String body = "{\"CodeN\":10406,\"Code\":\"10406\","
                + "\"Message\":\"Free quota has been exhausted.\"}";
        when(restTemplate.exchange(anyString(), any(), any(), eq(String.class)))
                .thenReturn(new ResponseEntity<>(body, HttpStatus.OK));

        assertThatThrownBy(() -> service.searchOpportunities("北京市"))
                .isInstanceOf(ApiCallException.class)
                .hasMessageContaining("联网搜索接口")
                .hasMessageContaining("Code=10406");
    }

    @Test
    @DisplayName("Result 节点缺失时返回空列表")
    void searchOpportunities_missingResultNode_returnsEmpty() {
        String body = "{\"ResponseMetadata\":{}}";
        when(restTemplate.exchange(anyString(), any(), any(), eq(String.class)))
                .thenReturn(new ResponseEntity<>(body, HttpStatus.OK));

        assertThat(service.searchOpportunities("北京市")).isEmpty();
    }

    @Test
    @DisplayName("WebResults 不是数组时返回空列表")
    void searchOpportunities_webResultsNotArray_returnsEmpty() {
        String body = "{\"Result\":{\"ResultCount\":0,\"WebResults\":\"not-an-array\"}}";
        when(restTemplate.exchange(anyString(), any(), any(), eq(String.class)))
                .thenReturn(new ResponseEntity<>(body, HttpStatus.OK));

        assertThat(service.searchOpportunities("北京市")).isEmpty();
    }

    @Test
    @DisplayName("JSON 解析失败时返回空列表")
    void searchOpportunities_invalidJson_returnsEmpty() {
        when(restTemplate.exchange(anyString(), any(), any(), eq(String.class)))
                .thenReturn(new ResponseEntity<>("not a json", HttpStatus.OK));

        assertThat(service.searchOpportunities("北京市")).isEmpty();
    }

    @Test
    @DisplayName("restTemplate 抛异常时重试耗尽后抛 ApiCallException")
    void searchOpportunities_restTemplateThrows_returnsEmpty() {
        when(restTemplate.exchange(anyString(), any(), any(), eq(String.class)))
                .thenThrow(new RuntimeException("network error"));

        // 网络异常重试耗尽后抛 ApiCallException
        assertThatThrownBy(() -> service.searchOpportunities("北京市"))
                .isInstanceOf(ApiCallException.class);
    }

    // ========== 限流重试场景 ==========

    @Test
    @DisplayName("遇到 700429 限流并最终成功返回")
    void searchOpportunities_rateLimitThenSuccess_returnsResults() {
        String rateLimitBody = "{\"CodeN\":700429,\"Code\":\"700429\",\"Message\":\"Request frequency exceeded\"}";
        String successBody = "{\"Result\":{\"ResultCount\":1,\"WebResults\":[{\"Title\":\"标题\",\"Url\":\"http://a.com/1\",\"Summary\":\"摘要\"}]}}";

        when(restTemplate.exchange(anyString(), any(), any(), eq(String.class)))
                .thenReturn(new ResponseEntity<>(rateLimitBody, HttpStatus.OK))
                .thenReturn(new ResponseEntity<>(successBody, HttpStatus.OK));

        List<VolcengineSearchService.SearchResult> results = service.searchOpportunities("北京市");
        assertThat(results).hasSize(1);
        assertThat(results.get(0).getTitle()).isEqualTo("标题");
    }

    @Test
    @DisplayName("持续 700429 限流达到最大重试次数后抛 ApiCallException")
    void searchOpportunities_persistentRateLimit_returnsRateLimitBody() {
        String rateLimitBody = "{\"CodeN\":700429,\"Code\":\"700429\",\"Message\":\"Request frequency exceeded\"}";

        // 持续返回限流，重试 4 次（attempt 0,1,2,3）
        when(restTemplate.exchange(anyString(), any(), any(), eq(String.class)))
                .thenReturn(new ResponseEntity<>(rateLimitBody, HttpStatus.OK));

        // 限流重试耗尽后抛 ApiCallException
        assertThatThrownBy(() -> service.searchOpportunities("北京市"))
                .isInstanceOf(ApiCallException.class)
                .hasMessageContaining("联网搜索接口")
                .hasMessageContaining("限流");
    }

    @Test
    @DisplayName("网络异常时重试耗尽后抛 ApiCallException")
    void searchOpportunities_networkErrorAllRetries_returnsEmpty() {
        when(restTemplate.exchange(anyString(), any(), any(), eq(String.class)))
                .thenThrow(new RuntimeException("connection refused"));

        // 网络异常重试耗尽后抛 ApiCallException
        assertThatThrownBy(() -> service.searchOpportunities("北京市"))
                .isInstanceOf(ApiCallException.class)
                .hasMessageContaining("联网搜索接口")
                .hasMessageContaining("网络异常");
    }

    @Test
    @DisplayName("网络异常重试后成功返回")
    void searchOpportunities_networkErrorThenSuccess_returnsResults() {
        String successBody = "{\"Result\":{\"ResultCount\":1,\"WebResults\":[{\"Title\":\"标题\",\"Url\":\"http://a.com/1\",\"Summary\":\"摘要\"}]}}";

        when(restTemplate.exchange(anyString(), any(), any(), eq(String.class)))
                .thenThrow(new RuntimeException("first fail"))
                .thenReturn(new ResponseEntity<>(successBody, HttpStatus.OK));

        List<VolcengineSearchService.SearchResult> results = service.searchOpportunities("北京市");
        assertThat(results).hasSize(1);
    }

    @Test
    @DisplayName("HTTP 状态非 2xx 时抛 ApiCallException（携带状态码和响应摘要）")
    void searchOpportunities_non2xxStatus_attemptsParse() {
        String body = "{\"Result\":{\"ResultCount\":1,\"WebResults\":[{\"Title\":\"标题\",\"Url\":\"http://a.com/1\",\"Summary\":\"摘要\"}]}}";
        when(restTemplate.exchange(anyString(), any(), any(), eq(String.class)))
                .thenReturn(new ResponseEntity<>(body, HttpStatus.INTERNAL_SERVER_ERROR));

        // 非 2xx 现在直接抛 ApiCallException，携带接口名、状态码、响应摘要
        assertThatThrownBy(() -> service.searchOpportunities("北京市"))
                .isInstanceOf(ApiCallException.class)
                .hasMessageContaining("联网搜索接口")
                .hasMessageContaining("HTTP 500")
                .hasMessageContaining("请求:")
                .hasMessageContaining("响应:");
    }

    // ========== 发布时间格式化 ==========

    @Test
    @DisplayName("发布时间不含 T 时直接返回原值")
    void searchOpportunities_publishTimeWithoutT_returnsOriginal() {
        String body = "{\"Result\":{\"ResultCount\":1,\"WebResults\":[{\"Title\":\"t\",\"Url\":\"http://a.com/1\",\"Summary\":\"s\",\"PublishTime\":\"2025-05-30\"}]}}";
        when(restTemplate.exchange(anyString(), any(), any(), eq(String.class)))
                .thenReturn(new ResponseEntity<>(body, HttpStatus.OK));

        List<VolcengineSearchService.SearchResult> results = service.searchOpportunities("北京市");
        assertThat(results.get(0).getPublishTime()).isEqualTo("2025-05-30");
    }

    @Test
    @DisplayName("发布时间为 null 时返回 null")
    void searchOpportunities_publishTimeNull_returnsNull() {
        String body = "{\"Result\":{\"ResultCount\":1,\"WebResults\":[{\"Title\":\"t\",\"Url\":\"http://a.com/1\",\"Summary\":\"s\"}]}}";
        when(restTemplate.exchange(anyString(), any(), any(), eq(String.class)))
                .thenReturn(new ResponseEntity<>(body, HttpStatus.OK));

        List<VolcengineSearchService.SearchResult> results = service.searchOpportunities("北京市");
        assertThat(results.get(0).getPublishTime()).isNull();
    }

    // ========== 补充分支覆盖测试 ==========

    @Test
    @DisplayName("Summary/Content 为 JSON null 时使用 Snippet（覆盖 getTextValue isNull + 短路分支）")
    void searchOpportunities_nullSummaryAndContent_usesSnippet() {
        // Summary 和 Content 显式为 JSON null → getTextValue 返回 null
        String body = "{\"Result\":{\"ResultCount\":1,\"WebResults\":["
                + "{\"Title\":\"标题\",\"Url\":\"http://a.com/1\","
                + "\"Summary\":null,\"Content\":null,\"Snippet\":\"片段\"}]}}";
        when(restTemplate.exchange(anyString(), any(), any(), eq(String.class)))
                .thenReturn(new ResponseEntity<>(body, HttpStatus.OK));

        List<VolcengineSearchService.SearchResult> results = service.searchOpportunities("北京市");
        assertThat(results).hasSize(1);
        assertThat(results.get(0).getContent()).isEqualTo("片段");
    }

    @Test
    @DisplayName("Title 为 JSON null 时该记录被过滤掉（覆盖 title==null 短路分支）")
    void searchOpportunities_nullTitle_filtered() {
        String body = "{\"Result\":{\"ResultCount\":1,\"WebResults\":["
                + "{\"Title\":null,\"Url\":\"http://a.com/1\",\"Summary\":\"s\"}]}}";
        when(restTemplate.exchange(anyString(), any(), any(), eq(String.class)))
                .thenReturn(new ResponseEntity<>(body, HttpStatus.OK));

        List<VolcengineSearchService.SearchResult> results = service.searchOpportunities("北京市");
        assertThat(results).isEmpty();
    }

    @Test
    @DisplayName("Url 为 JSON null 时该记录被过滤掉（覆盖 url==null 短路分支）")
    void searchOpportunities_nullUrl_filtered() {
        String body = "{\"Result\":{\"ResultCount\":1,\"WebResults\":["
                + "{\"Title\":\"标题\",\"Url\":null,\"Summary\":\"s\"}]}}";
        when(restTemplate.exchange(anyString(), any(), any(), eq(String.class)))
                .thenReturn(new ResponseEntity<>(body, HttpStatus.OK));

        List<VolcengineSearchService.SearchResult> results = service.searchOpportunities("北京市");
        assertThat(results).isEmpty();
    }

    @Test
    @DisplayName("PublishTime 含 T 但长度不足 10 时 formatPublishTime catch 分支返回原值")
    void searchOpportunities_shortPublishTimeWithT_returnsOriginal() {
        // publishTime = "T" 包含 T 但 substring(0,10) 抛 StringIndexOutOfBoundsException → catch 返回原值
        String body = "{\"Result\":{\"ResultCount\":1,\"WebResults\":["
                + "{\"Title\":\"标题\",\"Url\":\"http://a.com/1\",\"Summary\":\"s\",\"PublishTime\":\"T\"}]}}";
        when(restTemplate.exchange(anyString(), any(), any(), eq(String.class)))
                .thenReturn(new ResponseEntity<>(body, HttpStatus.OK));

        List<VolcengineSearchService.SearchResult> results = service.searchOpportunities("北京市");
        assertThat(results).hasSize(1);
        // catch 分支返回原始 publishTime
        assertThat(results.get(0).getPublishTime()).isEqualTo("T");
    }

    @Test
    @DisplayName("响应 body 为 null 时不触发限流检查直接返回 null")
    void searchOpportunities_nullBody_skipsRateLimitCheck() {
        when(restTemplate.exchange(anyString(), any(), any(), eq(String.class)))
                .thenReturn(new ResponseEntity<>(null, HttpStatus.OK));

        // body==null → body!=null 短路为 false → 不检查 700429 → return null → parseSearchResults 返回空
        assertThat(service.searchOpportunities("北京市")).isEmpty();
    }

    @Test
    @DisplayName("限流达到最大重试次数后抛 ApiCallException（覆盖 attempt==MAX_RETRY 限流分支）")
    void searchOpportunities_rateLimitMaxRetry_returnsRateLimitBody() {
        // 持续限流，attempt=0,1,2 重试，attempt=3 时抛 ApiCallException
        String rateLimitBody = "{\"CodeN\":700429,\"Code\":\"700429\",\"Message\":\"Request frequency exceeded\"}";
        when(restTemplate.exchange(anyString(), any(), any(), eq(String.class)))
                .thenReturn(new ResponseEntity<>(rateLimitBody, HttpStatus.OK));

        // 限流重试耗尽后抛 ApiCallException
        assertThatThrownBy(() -> service.searchOpportunities("北京市"))
                .isInstanceOf(ApiCallException.class);
    }

    @Test
    @DisplayName("PublishTime 字段为 JSON null 时返回 null（覆盖 getTextValue isNull）")
    void searchOpportunities_publishTimeJsonNull_returnsNull() {
        String body = "{\"Result\":{\"ResultCount\":1,\"WebResults\":["
                + "{\"Title\":\"t\",\"Url\":\"http://a.com/1\",\"Summary\":\"s\",\"PublishTime\":null}]}}";
        when(restTemplate.exchange(anyString(), any(), any(), eq(String.class)))
                .thenReturn(new ResponseEntity<>(body, HttpStatus.OK));

        List<VolcengineSearchService.SearchResult> results = service.searchOpportunities("北京市");
        assertThat(results).hasSize(1);
        assertThat(results.get(0).getPublishTime()).isNull();
    }
}
