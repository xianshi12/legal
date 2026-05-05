-- 删除内置 / 离线示例法条（与 LawArticleService.deleteSeedAndBuiltinArticles 条件一致）
-- 执行前请备份。若删完后表中无任何法条且希望启动时不再自动写入示例条，请设置：
--   LAW_SEED_DATA_ON_EMPTY=false  或  application.yml 中 app.law.seed-data-on-empty: false

DELETE FROM law_article
WHERE source_url LIKE 'seed://%'
   OR source_name = '内置基础库';
