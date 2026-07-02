package com.opportunity.exception;

/**
 * 接口调用异常
 * 当外部接口返回非200响应码时抛出，携带结构化的错误信息方便排查
 */
public class ApiCallException extends RuntimeException {

    /** 接口名称（如：联网搜索接口、大模型分析接口） */
    private final String apiName;
    /** 请求摘要（接口URL + 请求体关键信息） */
    private final String requestSummary;
    /** HTTP 状态码 */
    private final int statusCode;
    /** 响应摘要（响应体前 N 个字符） */
    private final String responseSummary;

    public ApiCallException(String apiName, String requestSummary, int statusCode, String responseSummary) {
        super(formatMessage(apiName, requestSummary, statusCode, responseSummary));
        this.apiName = apiName;
        this.requestSummary = requestSummary;
        this.statusCode = statusCode;
        this.responseSummary = responseSummary;
    }

    public ApiCallException(String apiName, String requestSummary, int statusCode, String responseSummary, Throwable cause) {
        super(formatMessage(apiName, requestSummary, statusCode, responseSummary), cause);
        this.apiName = apiName;
        this.requestSummary = requestSummary;
        this.statusCode = statusCode;
        this.responseSummary = responseSummary;
    }

    /**
     * 格式化错误信息：接口名 + 请求摘要 + 状态码 + 响应摘要
     * 控制总长度，确保日志可读
     */
    private static String formatMessage(String apiName, String requestSummary, int statusCode, String responseSummary) {
        StringBuilder sb = new StringBuilder();
        sb.append("[").append(apiName).append("] ");
        sb.append("HTTP ").append(statusCode).append("; ");
        sb.append("请求: ").append(truncate(requestSummary, 200)).append("; ");
        sb.append("响应: ").append(truncate(responseSummary, 500));
        return sb.toString();
    }

    /** 截断字符串到指定长度，超长加省略号 */
    private static String truncate(String str, int maxLen) {
        if (str == null) return "";
        return str.length() > maxLen ? str.substring(0, maxLen) + "..." : str;
    }

    public String getApiName() { return apiName; }
    public String getRequestSummary() { return requestSummary; }
    public int getStatusCode() { return statusCode; }
    public String getResponseSummary() { return responseSummary; }
}
