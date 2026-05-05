-- 法条检索：轻量映射（正文不入库）。可重复执行；重复列等错误由 continue-on-error 忽略。
ALTER TABLE law_article ADD COLUMN external_ref VARCHAR(128) NULL COMMENT '外部映射键，如 npc:bbbs' AFTER source_url;
ALTER TABLE law_article MODIFY content TEXT NULL COMMENT '不持久化全文';
ALTER TABLE law_article MODIFY interpretation TEXT NULL COMMENT '运行时生成';

ALTER TABLE law_article ADD COLUMN stable_article_key VARCHAR(256) NULL COMMENT '稳定业务键：lawName + normalizedArticleNo + source';
ALTER TABLE law_article ADD COLUMN normalized_article_no VARCHAR(64) NULL COMMENT '规范化条号，如第577条';
ALTER TABLE law_article ADD COLUMN article_ordinal INT NULL COMMENT '条号序数，用于第577条/第五百七十七条互认';
ALTER TABLE law_article ADD COLUMN article_summary VARCHAR(1024) NULL COMMENT '列表短摘要，不存全文';
ALTER TABLE law_article ADD COLUMN interpretation_summary VARCHAR(1024) NULL COMMENT '列表短解读';
ALTER TABLE law_article ADD COLUMN keyword_index VARCHAR(2048) NULL COMMENT '长度可控的检索关键词/ngram字段';
ALTER TABLE law_article ADD COLUMN sort_weight INT NOT NULL DEFAULT 0 COMMENT '排序权重';
ALTER TABLE law_article ADD COLUMN version_no VARCHAR(64) NOT NULL DEFAULT 'current' COMMENT '版本号或版本标识';
ALTER TABLE law_article ADD COLUMN effective_to DATE NULL COMMENT '失效/废止日期';
ALTER TABLE law_article ADD COLUMN replaced_by_article_id BIGINT NULL COMMENT '替代/修订后条文ID';
ALTER TABLE law_article ADD COLUMN current_effective TINYINT(1) NOT NULL DEFAULT 1 COMMENT '对用户默认展示是否现行有效';
ALTER TABLE law_article ADD COLUMN content_hash VARCHAR(64) NULL COMMENT '全文SHA-256';
ALTER TABLE law_article ADD COLUMN content_ref VARCHAR(512) NULL COMMENT '全文存储引用，如 db:articleId 或 oss key';

CREATE INDEX idx_law_article_effective_query ON law_article (current_effective, status, level, category, article_ordinal);
CREATE INDEX idx_law_article_norm_no ON law_article (law_name, normalized_article_no, article_ordinal);
CREATE INDEX idx_law_article_version ON law_article (stable_article_key, version_no, effective_date, effective_to);
CREATE INDEX idx_law_article_keyword ON law_article (keyword_index(191));

CREATE TABLE IF NOT EXISTS law_article_text (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    article_id BIGINT NOT NULL UNIQUE,
    content_ref VARCHAR(512) NOT NULL,
    full_content LONGTEXT NULL,
    full_interpretation LONGTEXT NULL,
    raw_html LONGTEXT NULL,
    content_hash VARCHAR(64) NULL,
    version_no VARCHAR(64) NOT NULL DEFAULT 'current',
    source_etag VARCHAR(128) NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    INDEX idx_law_article_text_hash (content_hash),
    INDEX idx_law_article_text_ref (content_ref)
);

CREATE TABLE IF NOT EXISTS law_tag (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    code VARCHAR(128) NOT NULL UNIQUE,
    name VARCHAR(128) NOT NULL,
    tag_type VARCHAR(64) NOT NULL DEFAULT 'topic',
    parent_id BIGINT NULL,
    sort_order INT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    INDEX idx_law_tag_tree (tag_type, parent_id, sort_order)
);

CREATE TABLE IF NOT EXISTS law_article_tag (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    article_id BIGINT NOT NULL,
    tag_id BIGINT NOT NULL,
    weight INT NOT NULL DEFAULT 1,
    created_at DATETIME NOT NULL,
    UNIQUE KEY uk_law_article_tag (article_id, tag_id),
    INDEX idx_law_article_tag_tag (tag_id, article_id)
);

CREATE TABLE IF NOT EXISTS law_sync_audit (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    provider VARCHAR(64) NOT NULL,
    task_key VARCHAR(256) NULL,
    status VARCHAR(64) NOT NULL,
    fetched_pages INT NOT NULL DEFAULT 0,
    upserted_articles INT NOT NULL DEFAULT 0,
    skipped_items INT NOT NULL DEFAULT 0,
    failed_items INT NOT NULL DEFAULT 0,
    message TEXT NULL,
    started_at DATETIME NOT NULL,
    finished_at DATETIME NULL,
    INDEX idx_law_sync_audit_provider_time (provider, started_at)
);
