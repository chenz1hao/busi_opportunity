-- ========================================================================
-- 商机信息抓取与评分系统 - 数据库初始化脚本
-- 适配 MySQL 5.7+ / H2 1.4+
-- 与 JPA Entity 定义完全一致（ddl-auto: update）
-- ========================================================================

-- ----------------------------------------------------------------------
-- 网页信息表：存储抓取到的商机线索及其 AI 分析结果
-- ----------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS web_page_info (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',

    -- 基础信息
    title VARCHAR(500) NOT NULL COMMENT '网页标题',
    content TEXT COMMENT '网页正文',
    url VARCHAR(1000) NOT NULL COMMENT '网页URL',
    publish_time VARCHAR(20) COMMENT '发布时间（yyyy-MM-dd 格式）',
    source_city VARCHAR(100) COMMENT '搜索来源城市',

    -- 去重信息
    title_hash VARCHAR(64) COMMENT '标题 SHA-256 哈希',
    max_similarity_score DOUBLE COMMENT '最大相似度得分（基于内容 SimHash 的相似度）',
    sim_hash BIGINT COMMENT '正文内容的 SimHash 指纹（64 位）',

    -- 大模型分析结果
    province VARCHAR(100) COMMENT '省',
    city VARCHAR(100) COMMENT '市',
    county VARCHAR(100) COMMENT '区县',
    deadline VARCHAR(8) COMMENT '招标截止日期 yyyyMMdd',
    amount VARCHAR(200) COMMENT '金额规模',
    type_flag BOOLEAN COMMENT '是否为招标文件（true=招标/采购/磋商）',
    score INT COMMENT '评分 0-100',
    score_reason VARCHAR(200) COMMENT '评分依据（50 字以内）',
    type VARCHAR(50) COMMENT '类型：政府文件/新闻/其他',

    -- 分析状态
    analysis_start_time TIMESTAMP COMMENT '大模型分析开始时间',
    analysis_end_time TIMESTAMP COMMENT '大模型分析结束时间',

    -- 审计字段
    created_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',

    -- 索引
    INDEX idx_url (url(255)),
    INDEX idx_source_city (source_city),
    INDEX idx_score (score),
    INDEX idx_analysis (score, analysis_start_time),
    INDEX idx_created_time (created_time),
    INDEX idx_title_hash (title_hash)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商机线索及 AI 分析结果';

-- ----------------------------------------------------------------------
-- 系统配置表：键值对存储系统参数（cron、模型、阈值、城市列表等）
-- ----------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS system_config (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    config_key VARCHAR(100) NOT NULL UNIQUE COMMENT '配置键（如 scheduler.search-cron、search.cities）',
    config_value TEXT COMMENT '配置值（支持长文本，城市列表/模型列表等）',
    description VARCHAR(200) COMMENT '配置说明',
    created_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',

    INDEX idx_config_key (config_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='系统配置（键值对）';

-- ----------------------------------------------------------------------
-- 调度日志表：记录每次搜索/分析任务的执行情况
-- ----------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS scheduler_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    task_name VARCHAR(100) NOT NULL COMMENT '任务名称（SEARCH_CRAWL / SCORE_ANALYSIS）',
    trigger_type VARCHAR(10) NOT NULL DEFAULT 'AUTO' COMMENT '触发方式：AUTO=定时自动触发，MANUAL=手动触发',
    start_time TIMESTAMP NOT NULL COMMENT '开始时间',
    end_time TIMESTAMP COMMENT '结束时间',
    status VARCHAR(20) NOT NULL COMMENT '状态：RUNNING / SUCCESS / FAILED',
    total_count INT DEFAULT 0 COMMENT '处理总数（搜索=候选线索数，分析=待分析数）',
    new_count INT DEFAULT 0 COMMENT '新增/成功数（搜索=去重后新增数，分析=成功分析数）',
    error_msg TEXT COMMENT '错误信息',
    created_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '记录创建时间',

    INDEX idx_task_name (task_name),
    INDEX idx_trigger_type (trigger_type),
    INDEX idx_status (status),
    INDEX idx_start_time (start_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='定时任务执行日志';
