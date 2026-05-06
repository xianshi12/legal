# AI 智能法律助手

面向法律咨询、法规检索、文书与合同处理等场景的 **前后端分离** Web 应用：后端 **Spring Boot + Spring AI**，前端 **Vue 3**；集成 **DeepSeek**、**阿里云百炼（通义）**、**百度千帆 OCR** 等能力；支持未配置密钥时的本地降级，便于先联调界面与接口。

---

## 项目介绍

本仓库实现一套 **AI 智能法律助手**：覆盖智能咨询、法律法规混合检索、法律文书生成、合同风险审查、类案匹配、维权流程指引与 **RAG 知识库** 联调底座。业务数据以 **MySQL** 为主，会话消息与热点数据使用 **Redis**；法规同步等长耗时任务通过 **RabbitMQ** 异步执行；可选 **PostgreSQL + pgvector** 承载向量检索与 RAG；附件与对象存储对接 **MinIO**（可替换为兼容 S3 的实现）。

---

## 系统架构

### 法规同步异步流程（RabbitMQ）

法规同步类接口采用 **投递消息 → 立即返回任务 ID → 后台消费** 的模式，避免 HTTP 长时间阻塞：

```
POST /api/laws/sync（或其他触发入口）
        → 校验参数并入队（RabbitMQ）
        → 立即返回 taskId
                    ↓
            Consumer 消费消息
                    ↓
            抓取 / 解析 / 入库（耗时操作）
                    ↓
            更新任务状态（供 GET /api/laws/sync/{taskId} 查询）
```

其他模块（如聊天、检索）多为同步 REST；**RAG** 检索与写入独立配置于 PostgreSQL/pgvector，与 MySQL 业务库并存。

> 架构示意图可在文档或 Wiki 中补充配图。

---

## 技术栈

### 后端

| 技术 | 版本 | 说明 |
| --- | --- | --- |
| Spring Boot | 3.4.x | 应用框架 |
| Java | 17 | 开发语言 |
| Spring AI | 1.0.0（BOM） | AI 抽象与接入（OpenAI 兼容等） |
| MyBatis-Plus | 3.5.7 | ORM |
| MySQL | - | 主业务库 |
| Redis | - | 会话消息与缓存等 |
| RabbitMQ | - | 法规同步等异步任务 |
| PostgreSQL + pgvector | - | RAG 向量存储（可选） |
| MinIO | - | 对象存储（S3 兼容） |
| Apache Tika | 2.9.2 | 文档文本提取 |
| SpringDoc OpenAPI | 2.8.6 | API 文档 |
| Maven | - | 构建工具 |

### 技术选型常见问题

- **为什么业务库用 MySQL，向量用 PostgreSQL + pgvector？**  
  业务实体与法条、案例等结构化数据沿用成熟的关系型建模与运维习惯；向量检索单独落在 PG，避免把向量与复杂业务 schema 绑在同一套迁移节奏上，需要时也可单独扩缩容。

- **为什么引入 Redis？**  
  聊天会话消息、部分热点与队列外的缓存场景适合用 Redis；与 RabbitMQ 分工明确：**Redis** 偏会话与缓存，**RabbitMQ** 偏可靠异步任务。

- **为什么用 RabbitMQ 而不是只靠 Redis Stream？**  
  法规同步、批量抓取等任务耗时与失败重试场景更适合成熟 MQ 的投递与消费语义；与「InterviewGuide」类项目选用 Redis Stream 一样，本质是 **按团队栈与运维成本选型**，此处优先 AMQP。

- **为什么用 Maven？**  
  后端工程与 Spring Boot 官方示例一致性好，依赖与 CI 集成路径清晰；若团队统一 Gradle，也可迁移，不在本 README 展开。

### 前端

| 技术 | 版本 | 说明 |
| --- | --- | --- |
| Vue | 3.5.x | UI 框架 |
| Vite | 8.x | 构建工具 |
| Element Plus | 2.13.x | 组件库 |
| Pinia | 3.x | 状态管理 |
| Vue Router | 4.6.x | 路由 |
| Axios | 1.15.x | HTTP 客户端 |

---

## 功能特性

### 模块一：智能法律咨询

- **会话管理**：MySQL 存会话元数据，Redis 存会话消息历史  
- **对话接口**：支持发送消息与附件（`multipart/form-data`）  
- **附件处理**：图片走百度千帆 **deepseek-ocr**，文档走 **Apache Tika** 抽取正文  
- **模型接入**：Spring AI + DeepSeek（OpenAI 兼容）；未配置 `DEEPSEEK_API_KEY` 时可返回本地演示文案，便于联调  

### 模块二：法律法规检索

- **混合检索**：关键词、案由、条文编号、分类、效力状态、地域与排序等筛选  
- **通俗解读**：返回法条原文、解读与适用提示等（可按配置走截取规则或大模型）  
- **向量检索**：通过兼容 OpenAI 的 Embeddings 接入百炼 `text-embedding-v4`（默认 1024 维）；未配置密钥时可退回关键词等方式  
- **实时补抓**：支持从国家法律法规数据库等来源按关键词补充少量最新条目后再与本地结果合并  
- **异步同步**：`POST /api/laws/sync` 入队 RabbitMQ，轮询 `GET /api/laws/sync/{taskId}` 查看状态；支持定时与 NPC 专线配置（详见 `application.yml`）  

### 模块三：法律文书生成

- **模板与字段**：借条、欠条、起诉状、律师函、劳动合同等多类模板及必填字段提示  
- **生成与优化**：标准生成、优化增强、风险标注等模式；支持上传或粘贴正文后优化  

### 模块四：合同风险审查

- **上传 / 粘贴**：Word、PDF、TXT 或纯文本  
- **风险分级**：高 / 中 / 低风险统计与条款级说明、建议  
- **模型**：合同审查可走阿里云百炼 OpenAI 兼容接口（如 `qwen3.6-plus`）；不可用时有规则兜底  

### 模块五：案例相似匹配

- **语义 + 规则**：案情描述匹配类案，支持地区、时间、法院级别等筛选  
- **向量复用**：与法条检索共用 Embedding 配置；不可用时回落关键词与案由规则  

### 模块六：流程指引

- **流程模板**：劳动仲裁、民事起诉、民间借贷、租房押金、工伤、离婚、消费维权等  
- **AI 指引**：生成分步说明、材料清单、注意事项与证据提示  
- **时效计算**：如仲裁 / 诉讼时效等关键期限辅助计算  

### 模块七：RAG 知识库底座

- **统一召回**：各业务模块可按策略组合「本模块知识 + 法规 + 案例 + 通用知识」  
- **基础库沉淀**：法规 / 案例入库与 RAG 写入可分开；提供 bootstrap 类接口批量补建（见代码与接口说明）  
- **引用透出**：咨询、文书、合同、流程等响应可携带 `ragReferences` 便于溯源展示  

---

## TODO

- [ ] 按实际上线需求细化各模块权限与审计日志  
- [ ] 前端统一补充截图与演示视频链接  
- [ ] 可按环境拆分默认 `application.yml` 与文档中的占位主机  

---

## 效果展示

> 以下路径为占位，请将截图放入 `docs/images/` 或替换为实际 URL。

| 模块 | 说明 |
| --- | --- |
| 工作台 / 总览 | ![总览](./docs/images/dashboard.png) |
| 智能咨询 | ![咨询](./docs/images/chat.png) |
| 法规检索 | ![法规](./docs/images/law-search.png) |
| 文书生成 | ![文书](./docs/images/document.png) |
| 合同审查 | ![合同](./docs/images/contract.png) |
| 案例匹配 | ![案例](./docs/images/case.png) |
| 流程指引 | ![流程](./docs/images/guide.png) |
| RAG 知识库 | ![RAG](./docs/images/rag.png) |

---

## 项目结构

```
legalAssistant/
├── backend/                              # 后端（Spring Boot）
│   ├── sql/
│   │   └── init_mysql.sql                # MySQL 初始化脚本（按需）
│   ├── src/main/java/com/fatongai/legalassistant/
│   │   ├── ai/                           # 模型调用、OCR、提示词等
│   │   ├── chat/                         # 智能咨询 / 会话
│   │   ├── law/                          # 法规检索、同步、爬虫
│   │   ├── document/                     # 法律文书生成
│   │   ├── contract/                     # 合同审查
│   │   ├── casebase/                     # 案例匹配
│   │   ├── guide/                        # 流程指引
│   │   ├── rag/                          # RAG 向量检索与写入
│   │   ├── file/                         # 附件存储与文本提取
│   │   ├── config/                       # 配置
│   │   └── common/                       # 通用响应等
│   └── src/main/resources/
│       ├── application.yml               # 应用配置（支持 .env 覆盖）
│       └── schema.sql                    # 表结构（与 sql init 配合）
│
├── frontend/                             # 前端（Vue 3 + Vite）
│   ├── src/
│   │   ├── api/                          # 接口封装
│   │   ├── components/                   # 公共组件
│   │   ├── views/                        # 页面
│   │   ├── router/                       # 路由
│   │   └── constants/                    # 常量与配置
│   ├── package.json
│   └── vite.config.js
│
└── README.md
```

---

## 快速开始

### 环境要求

| 依赖 | 版本 | 必需 |
| --- | --- | --- |
| JDK | 17+ | 是 |
| Maven | 3.8+ | 是 |
| Node.js | 18+ | 是 |
| MySQL | 5.7+ / 8.x | 是 |
| Redis | 6+ | 是 |
| RabbitMQ | 3.x+ | 是（法规异步同步等） |
| PostgreSQL + pgvector | - | 否（启用 RAG 向量能力时建议） |
| MinIO（或兼容 S3） | - | 否（按上传与存储配置） |

### 1. 克隆项目

```bash
git clone <你的仓库地址>
cd legalAssistant
```

### 2. 初始化数据库

创建库并执行初始化脚本（名称与账号以你的环境为准）：

```bash
# 示例：导入 backend/sql/init_mysql.sql
mysql -u root -p legal_assistant < backend/sql/init_mysql.sql
```

Spring Boot 可通过 `schema.sql` 等在启动时辅助建表，具体以 `application.yml` 中 `spring.sql.init` 为准。

### 3. 配置环境变量

常用变量如下（完整默认值见 `backend/src/main/resources/application.yml`，支持根目录或 `backend/` 下 `.env` / `.env.properties` 可选加载）。

**DeepSeek（咨询 / 文书 / 流程等）**

- `DEEPSEEK_API_KEY`
- `DEEPSEEK_BASE_URL`（默认 `https://api.deepseek.com`）
- `DEEPSEEK_MODEL`（默认 `deepseek-v4-flash`）

**百度千帆 OCR（聊天图片附件）**

- `QIANFAN_OCR_API_KEY`
- `QIANFAN_OCR_BASE_URL`（默认 `https://qianfan.baidubce.com/v2/chat/completions`）
- `QIANFAN_OCR_MODEL`（默认 `deepseek-ocr`）

**阿里云百炼（合同审查、Embedding）**

- `QWEN_API_KEY`
- `QWEN_BASE_URL`（默认 `https://dashscope.aliyuncs.com/compatible-mode/v1`）
- `QWEN_CHAT_MODEL`（默认 `qwen3.6-plus`）
- `QWEN_EMBEDDING_MODEL`（默认 `text-embedding-v4`）

**中间件与存储**

- `MYSQL_HOST`、`MYSQL_PORT`、`MYSQL_DB`、`MYSQL_USER`、`MYSQL_PASSWORD`
- `REDIS_HOST`、`REDIS_PORT`、`REDIS_PASSWORD`（可选）、`REDIS_DATABASE`
- `RABBITMQ_HOST`、`RABBITMQ_PORT`、`RABBITMQ_USER`、`RABBITMQ_PASSWORD`
- `PGVECTOR_HOST`、`PGVECTOR_PORT`、`PGVECTOR_DB`、`PGVECTOR_USER`、`PGVECTOR_PASSWORD`
- `MINIO_ENDPOINT`、`MINIO_ACCESS_KEY`、`MINIO_SECRET_KEY`、`MINIO_BUCKET`

### 4. 修改应用配置

编辑 `backend/src/main/resources/application.yml`，将数据源、Redis、RabbitMQ、MinIO、pgvector 等指向你的环境；法规爬虫与 NPC 同步相关项在同一文件中均有注释说明。

### 5. 启动服务

**后端：**

```bash
cd backend
mvn spring-boot:run
```

默认端口：**8081**。

**前端：**

在仓库根目录：

```bash
npm install --prefix frontend
npm run dev
```

或进入 `frontend/` 执行 `npm install` 与 `npm run dev`。

开发服务器默认 **http://localhost:5173**；`/api` 由 Vite 代理到 **http://localhost:8081**（超时等见 `frontend/vite.config.js`）。

生产构建：`npm run build`（在根目录或 `frontend/` 执行均可）。

---

## 使用场景

| 用户角色 | 使用场景 |
| --- | --- |
| 普通用户 / 当事人 | 法律咨询、文书起草辅助、合同自查、类案与流程参考 |
| 法务 / 律师（内部工具） | 法规检索、批量同步、知识库沉淀与办案辅助 |
| 产品 / 研发 | 联调 AI 与 RAG、扩展新模板与新数据源 |

---

## 常见问题

**Q：聊天没有 AI 回复，只有固定话术？**  
检查是否配置了 `DEEPSEEK_API_KEY`；未配置时项目会故意降级为本地演示响应。

**Q：图片附件无法识别？**  
确认 `QIANFAN_OCR_API_KEY` 及网络可达；注意千帆侧频控，配置中有最小间隔与重试相关项。

**Q：法规同步一直 pending 或失败？**  
检查 RabbitMQ 是否连通、队列与消费者是否正常；查看后端日志中 `NpcFlkLawImporter`、`LawSyncQueueService` 等关键字。

**Q：向量检索不生效？**  
确认 `QWEN_API_KEY`、PostgreSQL 已安装 **pgvector** 扩展，且 `app.rag.pgvector` 配置正确。

**Q：前端接口跨域或超时？**  
开发环境依赖 Vite 代理；长耗时接口可在 `vite.config.js` 中调整 `proxy` 超时。

---

## License

以仓库内 `LICENSE` 文件为准（若未添加则说明尚未指定）。
