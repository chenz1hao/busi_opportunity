-- 网页信息表
CREATE TABLE IF NOT EXISTS web_page_info (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    -- 基础信息
    title VARCHAR(500) NOT NULL COMMENT '网页标题',
    content TEXT COMMENT '网页正文',
    url VARCHAR(1000) NOT NULL COMMENT '网页URL',
    publish_time VARCHAR(20) COMMENT '发布时间',
    source_city VARCHAR(100) COMMENT '搜索来源城市',
    -- 去重信息
    title_hash VARCHAR(64) COMMENT '标题哈希',
    max_similarity_score DOUBLE COMMENT '最大相似度得分（基于内容 SimHash 的相似度）',
    sim_hash BIGINT COMMENT '正文内容的 SimHash 指纹（64 位）',
    -- 大模型分析结果
    province VARCHAR(100) COMMENT '省',
    city VARCHAR(100) COMMENT '市',
    county VARCHAR(100) COMMENT '区县',
    deadline VARCHAR(8) COMMENT '招标截止日期 yyyyMMdd',
    amount VARCHAR(200) COMMENT '金额规模',
    type_flag BOOLEAN COMMENT '是否为招标文件',
    score INT COMMENT '评分 0-100',
    score_reason VARCHAR(200) COMMENT '评分依据',
    type VARCHAR(50) COMMENT '类型: 政府文件/新闻/其他',
    -- 分析状态
    analysis_start_time TIMESTAMP COMMENT '大模型分析开始时间',
    analysis_end_time TIMESTAMP COMMENT '大模型分析结束时间',
    -- 审计字段
    created_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间',

    INDEX idx_url (url(255)),
    INDEX idx_source_city (source_city),
    INDEX idx_score (score),
    INDEX idx_analysis (score, analysis_start_time),
    INDEX idx_created_time (created_time),
    INDEX idx_title_hash (title_hash)
);

-- 系统配置表
CREATE TABLE IF NOT EXISTS system_config (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    config_key VARCHAR(100) NOT NULL UNIQUE COMMENT '配置键',
    config_value VARCHAR(1000) COMMENT '配置值',
    description VARCHAR(500) COMMENT '配置说明',
    created_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 定时任务执行日志
CREATE TABLE IF NOT EXISTS scheduler_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_name VARCHAR(100) NOT NULL COMMENT '任务名称',
    start_time TIMESTAMP NOT NULL COMMENT '开始时间',
    end_time TIMESTAMP COMMENT '结束时间',
    status VARCHAR(20) NOT NULL COMMENT '状态: RUNNING/SUCCESS/FAILED',
    total_count INT DEFAULT 0 COMMENT '处理总数',
    new_count INT DEFAULT 0 COMMENT '新增数',
    error_msg TEXT COMMENT '错误信息',
    created_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);