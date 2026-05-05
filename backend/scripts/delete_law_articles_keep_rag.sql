-- 清空法条检索模块业务表数据，保留 RAG 知识库（rag_document / rag_document_chunk 全部不动）
-- 执行前请备份数据库。
--
-- 若删完后希望启动时不再自动写入内置示例条：
--   LAW_SEED_DATA_ON_EMPTY=false
--   或 app.law.seed-data-on-empty: false
--
-- 删除顺序：law_article_tag / law_article_text → law_sync_audit → law_article
-- （不删除 rag_*；law_tag 标签字典默认保留）

SET NAMES utf8mb4;

DELETE FROM law_article_tag;
DELETE FROM law_article_text;

-- 同步审计（可选；注释掉可保留 NPC 等同步任务历史）
DELETE FROM law_sync_audit;

DELETE FROM law_article;
