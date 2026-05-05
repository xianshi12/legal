-- 清空法条检索模块相关数据（MySQL）
-- 执行前请备份数据库。
--
-- 若删完后希望启动时不再自动写入内置示例条，请设置环境变量或配置：
--   LAW_SEED_DATA_ON_EMPTY=false
--   或 application.yml 中 app.law.seed-data-on-empty: false
--
-- 删除顺序：子表 / RAG 中法条派生文档（doc_id 形如 law-<数字>）→ law_article

SET NAMES utf8mb4;

-- 法条标签关联、正文扩展表（schema 未声明外键，需手工先删以免孤儿行）
DELETE FROM law_article_tag;
DELETE FROM law_article_text;

-- RAG：由法规 bootstrap / indexLawArticle 写入的文档（doc_id = law-{articleId}）
DELETE FROM rag_document_chunk WHERE doc_id LIKE 'law-%';
DELETE FROM rag_document WHERE doc_id LIKE 'law-%';

-- 同步审计（可选；注释掉下一行可保留历史任务记录）
DELETE FROM law_sync_audit;

DELETE FROM law_article;
