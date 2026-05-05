# 模块 2：法律法规检索后端说明

## 存储模型

核心策略是“轻量主表 + 映射表 + 大文本延迟加载”：

- `law_article`：稳定 ID、法规名、条号、层级、效力状态、公布/施行/失效日期、版本号、内容哈希、正文引用、短摘要、短解读、关键词索引、排序权重、来源元数据。
- `law_article_text`：按 `article_id` 存全文、完整解读、原始 HTML 等大字段；列表和聚合查询不读取该表。
- `law_tag` / `law_article_tag`：主题、案由与条文多对多映射。
- `law_sync_audit`：同步任务审计，包括 provider、任务 key、成功/失败统计与消息。

版本关系通过 `stable_article_key + version_no + effective_date/effective_to + current_effective + replaced_by_article_id` 表达。默认搜索只展示现行有效；明确筛选历史状态或传 `includeHistory=true` 时才返回历史版本。

## API

- `GET /api/laws/search`、`POST /api/laws/search`：分页搜索；支持关键词、案由/标签、条文编号、分类、效力、位阶、机关、地域、日期范围、排序。
- `GET /api/laws/{id}`：详情懒加载全文、完整解读、元数据、来源和版本字段。
- `GET /api/laws/tags`：标签/案由树。
- `GET /api/laws/{id}/versions`：同一稳定条文的版本历史。
- `POST /api/laws/sync`：可插拔同步入口，当前支持 NPC 法律法规数据库与 URL 抓取；请求投递 RabbitMQ 后立即返回 `taskId`，由后台消费者写入并更新 `law_sync_audit`。
- `GET /api/laws/sync/{taskId}`：查询后台同步任务审计状态。
- `POST /api/laws/maintenance/reindex`：重建轻量摘要、条号规范化、关键词索引字段。

## 检索实现

当前使用 MySQL 轻量列 `LIKE + 短摘要/关键词索引 + 标签映射`，避免列表查询扫大字段；条号检索由 `LawArticleCitationNormalizer` 将简称/全称、中文数字/阿拉伯数字统一为 `lawNameHint + articleOrdinal + normalizedArticleNo`。后续接入 Elasticsearch/OpenSearch 时，可在服务层保留同一请求/响应模型，仅把候选召回替换为外部 `SearchProvider`。

## 解读约束

详情解读绑定当前详情返回的条文、版本号和效力状态。非现行有效条文会在解读前追加效力提示；若存在 `replaced_by_article_id`，会标明替代条文。界面返回 `disclaimer`，提示通俗解读不构成法律意见，正式引用以来源机关公布文本为准。
