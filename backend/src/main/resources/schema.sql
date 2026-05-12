CREATE TABLE IF NOT EXISTS chat_session (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    session_id VARCHAR(64) NOT NULL UNIQUE,
    user_id BIGINT NOT NULL,
    title VARCHAR(255) NOT NULL,
    last_message_time DATETIME NOT NULL,
    created_at DATETIME NOT NULL,
    INDEX idx_chat_session_user_last_time (user_id, last_message_time)
);

CREATE TABLE IF NOT EXISTS auth_user (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    role VARCHAR(32) NOT NULL,
    phone VARCHAR(20) NOT NULL,
    password_hash VARCHAR(128) NOT NULL,
    password_salt VARCHAR(64) NOT NULL,
    license_no VARCHAR(128) NULL,
    display_name VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    UNIQUE KEY uk_auth_user_phone_role (phone, role),
    INDEX idx_auth_user_phone (phone),
    INDEX idx_auth_user_role_status (role, status)
);

CREATE TABLE IF NOT EXISTS law_article (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    law_name VARCHAR(255) NOT NULL,
    article_no VARCHAR(64) NOT NULL,
    title VARCHAR(512) NOT NULL,
    content TEXT NULL COMMENT '不持久化全文，展示时按 external_ref/source_url 解析',
    interpretation TEXT NULL COMMENT '展示时现算，一般不入库',
    scenarios TEXT,
    exceptions_text TEXT,
    cause_of_action VARCHAR(512),
    category VARCHAR(64),
    level VARCHAR(64),
    issuing_body VARCHAR(256),
    region VARCHAR(64),
    status VARCHAR(64),
    source_name VARCHAR(128),
    source_url VARCHAR(1024),
    external_ref VARCHAR(128) NULL COMMENT '轻量映射键，如 npc:bbbs',
    stable_article_key VARCHAR(256) NULL,
    normalized_article_no VARCHAR(64) NULL,
    article_ordinal INT NULL,
    article_summary VARCHAR(1024) NULL,
    interpretation_summary VARCHAR(1024) NULL,
    keyword_index VARCHAR(2048) NULL,
    sort_weight INT NOT NULL DEFAULT 0,
    version_no VARCHAR(64) NOT NULL DEFAULT 'current',
    effective_to DATE NULL,
    replaced_by_article_id BIGINT NULL,
    current_effective TINYINT(1) NOT NULL DEFAULT 1,
    content_hash VARCHAR(64) NULL,
    content_ref VARCHAR(512) NULL,
    publish_date DATE,
    effective_date DATE,
    source_updated_at DATETIME,
    vector_model VARCHAR(128),
    vector_json LONGTEXT,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    UNIQUE KEY uk_law_article_unique (law_name, article_no, source_url(191)),
    INDEX idx_law_article_query (law_name, article_no, category, status, region),
    INDEX idx_law_article_external_ref (external_ref),
    INDEX idx_law_article_effective_query (current_effective, status, level, category, article_ordinal),
    INDEX idx_law_article_norm_no (law_name, normalized_article_no, article_ordinal),
    INDEX idx_law_article_version (stable_article_key, version_no, effective_date, effective_to),
    INDEX idx_law_article_keyword (keyword_index(191)),
    FULLTEXT KEY ft_law_article_meta (law_name, article_no, title, scenarios, cause_of_action)
);

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

CREATE TABLE IF NOT EXISTS legal_case (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    case_no VARCHAR(128) NOT NULL UNIQUE,
    title VARCHAR(512) NOT NULL,
    case_type VARCHAR(128),
    cause VARCHAR(128),
    region VARCHAR(64),
    court_name VARCHAR(255),
    court_level VARCHAR(64),
    trial_level VARCHAR(64),
    judgment_date DATE,
    judgment_result TEXT,
    adjudication_points TEXT,
    key_evidence TEXT,
    facts TEXT,
    source_name VARCHAR(128),
    source_url VARCHAR(1024),
    vector_model VARCHAR(128),
    vector_json LONGTEXT,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    INDEX idx_legal_case_filter (region, court_level, judgment_date, cause),
    FULLTEXT KEY ft_legal_case_text (title, case_type, cause, judgment_result, adjudication_points, key_evidence, facts)
);

CREATE TABLE IF NOT EXISTS rag_document (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    doc_id VARCHAR(64) NOT NULL UNIQUE,
    title VARCHAR(512) NOT NULL,
    original_name VARCHAR(512),
    source_type VARCHAR(64),
    module_scope VARCHAR(64),
    business_type VARCHAR(128),
    tags VARCHAR(1024),
    content_type VARCHAR(128),
    file_size BIGINT,
    status VARCHAR(64) NOT NULL,
    chunk_count INT NOT NULL DEFAULT 0,
    error_message TEXT,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    INDEX idx_rag_document_status (status, module_scope, business_type),
    INDEX idx_rag_document_updated (updated_at)
);

CREATE TABLE IF NOT EXISTS rag_document_chunk (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    doc_id VARCHAR(64) NOT NULL,
    chunk_index INT NOT NULL,
    title VARCHAR(512) NOT NULL,
    content MEDIUMTEXT NOT NULL,
    summary VARCHAR(1024),
    keywords VARCHAR(1024),
    source_type VARCHAR(64),
    module_scope VARCHAR(64),
    business_type VARCHAR(128),
    vector_model VARCHAR(128),
    vector_json LONGTEXT,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    UNIQUE KEY uk_rag_chunk_doc_index (doc_id, chunk_index),
    INDEX idx_rag_chunk_doc (doc_id),
    INDEX idx_rag_chunk_scope (module_scope, business_type),
    FULLTEXT KEY ft_rag_chunk_text (title, content, summary, keywords)
);
