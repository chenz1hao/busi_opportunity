package com.opportunity.entity;

import javax.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "web_page_info")
public class WebPageInfo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 网页标题 */
    @Column(name = "title", nullable = false, length = 500)
    private String title;

    /** 网页正文 */
    @Column(name = "content", columnDefinition = "TEXT")
    private String content;

    /** 网页URL */
    @Column(name = "url", nullable = false, length = 1000)
    private String url;

    /** 发布时间 */
    @Column(name = "publish_time", length = 20)
    private String publishTime;

    /** 搜索来源城市 */
    @Column(name = "source_city", length = 100)
    private String sourceCity;

    /** 标题哈希 */
    @Column(name = "title_hash", length = 64)
    private String titleHash;

    /** 最大相似度得分（基于内容 SimHash 的相似度，1 - 海明距离/64） */
    @Column(name = "max_similarity_score")
    private Double maxSimilarityScore;

    /** 正文内容的 SimHash 指纹（64 位），用于内容级去重 */
    @Column(name = "sim_hash")
    private Long simHash;

    // ===== 大模型分析结果 =====

    /** 省 */
    @Column(name = "province", length = 100)
    private String province;

    /** 市 */
    @Column(name = "city", length = 100)
    private String city;

    /** 区县 */
    @Column(name = "county", length = 100)
    private String county;

    /** 招标截止日期 yyyyMMdd */
    @Column(name = "deadline", length = 8)
    private String deadline;

    /** 金额规模 */
    @Column(name = "amount", length = 200)
    private String amount;

    /** 是否为招标文件 */
    @Column(name = "type_flag")
    private Boolean typeFlag;

    /** 评分 0-100 */
    @Column(name = "score")
    private Integer score;

    /** 评分依据 */
    @Column(name = "score_reason", length = 200)
    private String scoreReason;

    /** 类型: 政府文件/新闻/其他 */
    @Column(name = "type", length = 50)
    private String type;

    /** 大模型分析开始时间 */
    @Column(name = "analysis_start_time")
    private LocalDateTime analysisStartTime;

    /** 大模型分析结束时间 */
    @Column(name = "analysis_end_time")
    private LocalDateTime analysisEndTime;

    /** 创建时间 */
    @Column(name = "created_time")
    private LocalDateTime createdTime;

    /** 更新时间 */
    @Column(name = "updated_time")
    private LocalDateTime updatedTime;

    @PrePersist
    protected void onCreate() {
        this.createdTime = LocalDateTime.now();
        this.updatedTime = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedTime = LocalDateTime.now();
    }

    // ===== Getters & Setters =====

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

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

    public String getTitleHash() { return titleHash; }
    public void setTitleHash(String titleHash) { this.titleHash = titleHash; }

    public Double getMaxSimilarityScore() { return maxSimilarityScore; }
    public void setMaxSimilarityScore(Double maxSimilarityScore) { this.maxSimilarityScore = maxSimilarityScore; }

    public Long getSimHash() { return simHash; }
    public void setSimHash(Long simHash) { this.simHash = simHash; }

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

    public LocalDateTime getAnalysisStartTime() { return analysisStartTime; }
    public void setAnalysisStartTime(LocalDateTime analysisStartTime) { this.analysisStartTime = analysisStartTime; }

    public LocalDateTime getAnalysisEndTime() { return analysisEndTime; }
    public void setAnalysisEndTime(LocalDateTime analysisEndTime) { this.analysisEndTime = analysisEndTime; }

    public LocalDateTime getCreatedTime() { return createdTime; }
    public void setCreatedTime(LocalDateTime createdTime) { this.createdTime = createdTime; }

    public LocalDateTime getUpdatedTime() { return updatedTime; }
    public void setUpdatedTime(LocalDateTime updatedTime) { this.updatedTime = updatedTime; }
}