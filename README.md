## AI 智能法律助手（前后端分离）

面向法律咨询、法规检索、文书与合同处理等场景的 Web 应用：前端 Vue 3，后端 Spring Boot，集成 DeepSeek、通义千问、百度千帆 OCR 等能力；支持本地无密钥降级以便联调 UI。

### 技术栈概览

| 层级 | 技术 |
| --- | --- |
| 前端 | Vue 3、Vite、Element Plus、Pinia、Vue Router |
| 后端 | Spring Boot 3、MyBatis-Plus、Spring AI（OpenAI 兼容）、Redis、RabbitMQ |
| 数据与检索 | MySQL、Redis；RAG 使用 PostgreSQL + pgvector（可选） |
| 对象存储 | MinIO（可选，默认见配置） |
| 文档与 OCR | Apache Tika、百度千帆 deepseek-ocr |

### 目录结构

- `frontend/`：Vue 3 + Element Plus + Pinia
- `backend/`：Spring Boot 3.x + MyBatis-Plus + Redis + Spring AI（DeepSeek OpenAI 兼容接入）

### Phase 1（已交付）- 模块 1：智能法律咨询

- **会话管理**：MySQL 存会话元数据（`chat_session`），Redis 存会话消息历史
- **聊天对话**：`POST /api/chat/message/send`（支持 `multipart/form-data` + 多文件）
- **文件上传**：支持拖拽上传图片 / PDF / Word / TXT；图片走百度千帆 `deepseek-ocr`，文档走 Apache Tika 文本提取
- **AI 接入**：Spring AI OpenAI Starter + DeepSeek（OpenAI 兼容 API）
  - 未配置 `DEEPSEEK_API_KEY` 时，会返回本地演示回答，便于先联调 UI

### Phase 2（本地已完善）- 模块 2：法律法规检索

- **混合检索**：`GET /api/laws/search` 支持关键词、案由、条文编号、分类、效力状态、地域和排序筛选。
- **通俗解读**：检索结果返回法条原文、解读、适用场景、例外提示、来源和相关度标签。
- **向量模型**：通过 OpenAI 兼容 `/embeddings` 接入阿里云百炼 `text-embedding-v4`，默认 1024 维，未配置密钥时自动退回精确检索。
- **实时法规库**：`GET /api/laws/search?live=true` 会先按当前关键词从国家法律法规数据库 `https://flk.npc.gov.cn/search` 补抓少量最新条目，再返回本地混合检索结果。
- **同步更新**：`POST /api/laws/sync` 支持 `{ "provider": "npc-flk" }` 或第三方法规页面 URL，接口会先投递 RabbitMQ 后台任务并立即返回 `taskId`；`GET /api/laws/sync/{taskId}` 可查看审计状态。NPC 手动同步单次只处理 1 部新法规，`LAW_NPC_FLK_SCHEDULED_ENABLED=true` 与 `LAW_NPC_FLK_CRON` 可开启定时同步。

### Phase 3（本地已完善）- 模块 3：法律文书生成

- **基础文书**：`GET /api/documents/templates` 提供借条、欠条、收条、委托书等模板和必填字段。
- **专业文书**：支持起诉状、答辩状、律师函、离婚协议书、劳动合同等模板，返回关键条款提示。
- **输出模式**：`POST /api/documents/generate` 支持标准生成、优化增强、风险标注三种输出模式。
- **上传优化**：`POST /api/documents/optimize` 支持上传已有文书或粘贴正文，自动优化表述、补充缺失条款并标注风险。
- **模型降级**：未配置 DeepSeek API Key 时返回本地模板草稿，便于前后端联调。

### Phase 4（本地已完善）- 模块 4：合同风险审查

- **上传审查**：`POST /api/contracts/review` 支持 Word、PDF、TXT 合同上传，也支持粘贴合同正文。
- **风险分级**：返回高风险、中风险、低风险统计，标注条款位置、原条款、问题说明、法律依据和修改建议。
- **对比修订**：生成修改前/修改后对比项，并提供整合后的修订建议文本。
- **模型接入**：合同审查使用阿里云百炼 OpenAI 兼容接口 `qwen3.6-plus`；模型不可用时使用本地规则兜底，保持前后端可联调。

### Phase 5（本地已完善）- 模块 5：案例相似匹配

- **案情匹配**：`GET /api/cases/match` 支持输入案情描述，按语义相似度和关键词相关度匹配同类案例。
- **案例筛选**：支持地区、判决时间、法院级别筛选，优先展示高度相似案例。
- **维权参考**：返回法院判决结果、裁判要点、胜诉关键证据、案号、法院和审级信息。
- **向量复用**：复用 `text-embedding-v4` 向量模型；模型不可用时自动退回关键词和案由规则匹配。

### Phase 6（本地已完善）- 模块 6：流程指引

- **流程模板**：`GET /api/guides/templates` 提供劳动仲裁、民事起诉、民间借贷、租房押金、工伤认定、离婚诉讼、消费维权、合同解除等流程。
- **AI 指引**：`POST /api/guides/generate` 支持下拉流程和用户自定义流程，调用 DeepSeek 生成分步说明、材料清单、注意事项和证据清单。
- **时效计算**：`GET /api/guides/limitation` 自动计算劳动仲裁 1 年、一般民事/民间借贷 3 年、工伤认定 1 年等关键期限。
- **模型降级**：未配置 DeepSeek API Key 时返回内置流程模板和本地自定义流程兜底结果。

### Phase 7（本地已完善）- 模块 7：RAG 知识库联调底座

- **统一召回策略**：`RagKnowledgeService.searchTop` 对业务模块采用「本模块知识 + 法规基础库 + 案例基础库 + 通用知识」的检索顺序；法规模块检索 `law + all`，案例模块检索 `case + law + all`。
- **手动沉淀基础库**：法规和案例入库不会自动写入 RAG；需要时可通过 `POST /api/rag/bootstrap-foundation` 对既有法规和案例批量补建知识库。
- **智能咨询增强**：聊天模块会把 RAG 片段合入候选依据和模型上下文，提示词要求优先使用知识库依据，不足时明确说明资料不足。
- **文书/合同/流程增强**：文书生成、合同审查、流程指引均在模型提示词中注入 RAG 片段，并在响应中返回 `ragReferences`，便于前端展示或接口联调核对来源。
- **法规/案例引用增强**：法条检索和案例匹配响应继续返回 `ragReferences`，用于把检索结果与知识库证据链关联起来。

### 运行环境与中间件

后端默认在 `backend/src/main/resources/application.yml` 中指向一套开发机地址（可通过环境变量全部覆盖）。本地或服务器需按需准备：

- **MySQL**：默认连接 `${MYSQL_HOST:192.168.100.128}:${MYSQL_PORT:3307}`，库名默认 `legal_assistant`
- **Redis**：`${REDIS_HOST}`、`REDIS_PORT`、`REDIS_PASSWORD`（可选）、`REDIS_DATABASE`
- **RabbitMQ**：法规同步异步任务；`RABBITMQ_HOST`、`RABBITMQ_PORT`、`RABBITMQ_USER`、`RABBITMQ_PASSWORD`
- **PostgreSQL（pgvector）**：RAG 向量存储；`PGVECTOR_HOST`、`PGVECTOR_PORT`、`PGVECTOR_DB`、`PGVECTOR_USER`、`PGVECTOR_PASSWORD`
- **MinIO**：附件/对象存储相关；`MINIO_ENDPOINT`、`MINIO_ACCESS_KEY`、`MINIO_SECRET_KEY`、`MINIO_BUCKET`

可使用 Docker 自行编排上述服务；连接信息与本仓库默认值不一致时，请以环境变量或根目录 / 后端目录下的 `.env` / `.env.properties` 为准（Spring 已配置 `optional:file` 导入）。

### 启动后端

进入 `backend/`，准备 **JDK 17** 与 **Maven**，然后：

```bash
mvn spring-boot:run
```

默认 HTTP 端口：**8081**。

初始化 MySQL：执行 `backend/sql/init_mysql.sql`（若与项目自带 `schema.sql` 流程并存，以实际部署为准）。

### 启动前端

**方式一（推荐）**：在仓库根目录执行（脚本转发到 `frontend/`）：

```bash
npm install --prefix frontend
npm run dev
```

**方式二**：进入 `frontend/` 后执行 `npm install` 与 `npm run dev`。

开发服务器默认 **http://localhost:5173**（若端口占用会自动顺延）；`/api` 由 Vite 代理到 **http://localhost:8081**，长耗时接口代理超时约 **180 秒**。

生产构建：`npm run build`（根目录或 `frontend/` 均可，根目录对应 `npm run build`）。

### 环境变量说明（示例）

分组列出常用变量；完整默认值见 `application.yml`。

**DeepSeek（聊天 / 文书 / 流程等）**

- `DEEPSEEK_API_KEY`
- `DEEPSEEK_BASE_URL`（默认 `https://api.deepseek.com`）
- `DEEPSEEK_MODEL`（默认 `deepseek-v4-flash`）

**百度千帆 OCR（聊天附件图片）**

- `QIANFAN_OCR_API_KEY`
- `QIANFAN_OCR_BASE_URL`（默认 `https://qianfan.baidubce.com/v2/chat/completions`）
- `QIANFAN_OCR_MODEL`（默认 `deepseek-ocr`）

**阿里云百炼（合同审查、Embedding）**

- `QWEN_API_KEY`
- `QWEN_BASE_URL`（默认 `https://dashscope.aliyuncs.com/compatible-mode/v1`）
- `QWEN_CHAT_MODEL`（默认 `qwen3.6-plus`）
- `QWEN_EMBEDDING_MODEL`（默认 `text-embedding-v4`）

**法规爬虫与定时任务**

- `LAW_CRAWLER_SEED_URLS`（多个 URL 用英文逗号分隔）
- `LAW_CRAWLER_CRON`（默认每天 03:00）

**数据库与中间件（覆盖 `application.yml` 中的默认主机与端口）**

- `MYSQL_HOST`、`MYSQL_PORT`、`MYSQL_DB`、`MYSQL_USER`、`MYSQL_PASSWORD`
- `REDIS_HOST`、`REDIS_PORT`、`REDIS_PASSWORD`（可选）
- `RABBITMQ_HOST`、`RABBITMQ_PORT`、`RABBITMQ_USER`、`RABBITMQ_PASSWORD`
- `PGVECTOR_HOST`、`PGVECTOR_PORT`、`PGVECTOR_DB`、`PGVECTOR_USER`、`PGVECTOR_PASSWORD`
- `MINIO_ENDPOINT`、`MINIO_ACCESS_KEY`、`MINIO_SECRET_KEY`、`MINIO_BUCKET`

将变量写入系统环境，或在项目根目录 / `backend/` 放置 `.env` / `.env.properties` 均可（后者为可选加载）。
