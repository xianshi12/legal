-- 已有库升级：增加「制定机关」列（与 schema.sql 中新库结构对齐）。执行一次即可。
ALTER TABLE law_article ADD COLUMN issuing_body VARCHAR(256) NULL COMMENT '制定机关' AFTER level;
