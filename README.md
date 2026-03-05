# SuperBizAgent

> 基于 Spring Boot + AI Agent 的智能问答与运维系统

## 📖 项目简介

企业级智能业务代理系统，包含两大核心模块：

### 1. RAG 智能问答
集成 Milvus 向量数据库和阿里云 DashScope，提供基于检索增强生成的智能问答能力，支持多轮对话和流式输出。

### 2. AIOps 智能运维
基于 AI Agent 的自动化运维系统，采用 Planner-Executor-Replanner 架构，实现告警分析、日志查询、智能诊断和报告生成。

## 🚀 核心特性

- ✅ **RAG 问答**: 向量检索 + Rerank 重排 + 多轮对话 + 流式输出 ⭐ UPDATED
- ✅ **AIOps 运维**: 智能诊断 + 多 Agent 协作 + 自动报告
- ✅ **工具集成**: 文档检索、告警查询、日志分析、时间工具
- ✅ **会话持久化**: Redis + MySQL 混合存储，异步写入，高并发优化
- ✅ **语义压缩**: 智能对话摘要，自动压缩长对话历史，节省 Token
- ✅ **线程池优化**: 专用线程池配置，支持监控告警，高流量可靠性保障
- ✅ **版本控制**: 文档上传支持版本管理，非阻塞更新
- ✅ **用户反馈**: AI回复可标记不满意，持续优化服务质量 ⭐ NEW
- ✅ **会话管理**: 刷新页面保持当前对话，支持修改会话标题 ⭐ NEW
- ✅ **软删除**: 会话删除采用软删除，数据可恢复 ⭐ NEW
- ✅ **Web 界面**: 提供测试界面和 RESTful API

## 🛠️ 技术栈

| 技术 | 版本 | 说明 |
|------|------|------|
| Java | 17 | 开发语言 |
| Spring Boot | 3.2.0 | 应用框架 |
| MyBatis-Plus | 3.5.9 | ORM 框架 |
| Spring AI | - | AI Agent 框架 |
| DashScope | 2.22.10 | 阿里云 AI 服务（含Rerank重排） |
| Milvus | 2.6.10 | 向量数据库 |
| Redis | - | 缓存数据库 |
| MySQL | 8.0+ | 关系型数据库 |
| Lettuce | - | Redis 客户端 |

## 📦 核心模块

```
SuperBizAgent/
├── src/main/java/org/example/
│   ├── controller/
│   │   ├── ChatController.java        # 统一接口控制器 ⭐
│   │   ├── FileUploadController.java  # 文件上传控制器 ⭐
│   │   └── MonitorController.java     # 监控接口控制器 ⭐ NEW
│   ├── service/
│   │   ├── ChatService.java           # 对话服务 ⭐
│   │   ├── ConversationService.java  # 对话持久化服务 ⭐
│   │   ├── ConversationSummaryService.java  # 语义压缩服务 ⭐ NEW
│   │   ├── ThreadPoolMonitorService.java    # 线程池监控服务 ⭐ NEW
│   │   ├── RedisCacheService.java     # Redis 缓存服务 ⭐
│   │   ├── AiOpsService.java          # AIOps 服务 ⭐
│   │   ├── RagService.java            # RAG 服务
│   │   └── Vector*.java               # 向量服务
│   ├── entity/                         # 实体类
│   │   ├── ChatSession.java           # 会话实体 ⭐
│   │   ├── ChatMessage.java           # 消息实体 ⭐
│   │   ├── ChatSummary.java           # 摘要实体 ⭐ NEW
│   │   └── DocMetadata.java           # 文档元数据 ⭐
│   ├── mapper/                         # MyBatis Mapper
│   │   ├── ChatSessionMapper.java     # 会话 Mapper ⭐
│   │   ├── ChatMessageMapper.java     # 消息 Mapper ⭐
│   │   ├── ChatSummaryMapper.java     # 摘要 Mapper ⭐ NEW
│   │   └── DocMetadataMapper.java     # 文档 Mapper ⭐
│   ├── agent/tool/                    # Agent 工具集
│   │   ├── DateTimeTools.java         # 时间工具
│   │   ├── InternalDocsTools.java     # 文档检索
│   │   ├── QueryMetricsTools.java     # 告警查询
│   │   └── QueryLogsTools.java        # 日志查询
│   ├── config/                        # 配置类
│   │   ├── RedisConfig.java           # Redis 配置 ⭐
│   │   ├── AsyncConfig.java           # 异步任务配置 ⭐ NEW
│   │   ├── ThreadPoolConfig.java      # 统一线程池配置 ⭐ NEW
│   │   └── DocumentChunkConfig.java   # 文档分片配置
│   └── util/
│       └── FileUpdateLockManager.java # 文件更新锁管理 ⭐
├── src/main/resources/
│   ├── db/
│   │   └── schema_chat.sql            # 对话存储表结构 ⭐ NEW
│   ├── lua/
│   │   └── add_message.lua            # Redis 消息原子操作脚本 ⭐ NEW
│   ├── static/                        # Web 界面
│   └── application.yml                # 应用配置
└── aiops-docs/                        # 运维文档库
```

## 📡 核心接口

### 1. 智能问答接口

**流式对话（推荐）**
```bash
POST /api/chat_stream
Content-Type: application/json

{
  "Id": "session-123",
  "Question": "什么是向量数据库？"
}
```
支持 SSE 流式输出、自动工具调用、多轮对话。

**普通对话**
```bash
POST /api/chat
Content-Type: application/json

{
  "Id": "session-123",
  "Question": "什么是向量数据库？"
}
```
一次性返回完整结果，支持工具调用和多轮对话。

### 2. AIOps 智能运维接口

```bash
POST /api/ai_ops
```
自动执行告警分析流程，生成运维报告（SSE 流式输出）。

### 3. 会话管理 ⭐ NEW

```bash
# 获取会话信息
GET /api/chat/session/{sessionId}

# 获取最近会话列表
GET /api/chat/sessions/recent?limit=20

# 清空会话历史
POST /api/chat/clear
Content-Type: application/json
{
  "Id": "session-123"
}
```

### 4. 文件管理 ⭐ UPDATED

```bash
# 上传文件（支持版本控制，非阻塞更新）
POST /api/upload
Content-Type: multipart/form-data

# 获取文件列表
GET /api/files/list
```

### 5. 健康检查

```bash
GET /milvus/health
```

## ⚙️ 核心配置

### application.yml

```yaml
server:
  port: 9900

# Milvus 向量数据库
milvus:
  host: localhost
  port: 19530

# 阿里云 DashScope
spring:
  ai:
    dashscope:
      api-key: "${DASHSCOPE_API_KEY}" # 环境变量
  datasource:
    driver-class-name: com.mysql.cj.jdbc.Driver
    url: jdbc:mysql://localhost:3306/ai_ops
    username: root
    password: 123456
  data:
    redis:
      host: localhost
      port: 6379
      password: ""

# 对话存储配置
conversation:
  cache:
    expire-days: 7  # Redis 缓存过期时间（天）
    recent-sessions-limit: 100
  summary:
    enabled: true              # 是否启用语义压缩
    compress-threshold: 10     # 压缩阈值（消息条数，10条=5轮对话）
    compress-batch-size: 10    # 每次压缩的消息条数
    lock-timeout-seconds: 30   # 分布式锁超时时间（秒）

# RAG 配置
rag:
  top-k: 3
  model: "qwen3-max"

# 文档分片
document:
  chunk:
    max-size: 800
    overlap: 100

# Prometheus 配置
prometheus:
  mock-enabled: true  # Mock 模式，无 Prometheus 时启用
```

### 环境变量

```bash
export DASHSCOPE_API_KEY=your-api-key
```

## 🚀 快速开始

### 1. 环境准备

```bash
# 设置 API Key
export DASHSCOPE_API_KEY=your-api-key
```

### 2. 数据库初始化

```bash
# 创建数据库和表结构
mysql -u root -p < src/main/resources/db/schema_chat.sql
```

### 3. 启动应用

**方式一：Docker Compose（推荐）**
```bash
# 启动所有服务（包括向量数据库、MySQL、Redis）
docker compose up -d
```

**方式二：手动启动**
```bash
# 1. 启动向量数据库
docker compose up -d -f vector-database.yml

# 2. 启动 MySQL 和 Redis
docker compose up -d mysql redis

# 3. 启动应用
mvn clean install
mvn spring-boot:run
```

### 4. 使用示例

**Web 界面**
```
http://localhost:9900
```

**命令行**
```bash
# 智能问答
curl -X POST http://localhost:9900/api/chat \
  -H "Content-Type: application/json" \
  -d '{"Id":"test","Question":"什么是向量数据库？"}'

# 获取会话列表
curl http://localhost:9900/api/chat/sessions/recent?limit=20

# 上传文档
curl -X POST http://localhost:9900/api/upload \
  -F "file=@document.txt"

# 健康检查
curl http://localhost:9900/milvus/health
```

## 📊 架构亮点

### 对话持久化架构 ⭐ NEW

```
┌─────────────────────────────────────────────────────────────┐
│                    对话存储架构                                  │
├─────────────────────────────────────────────────────────────┤
│                                                                  │
│   写入流程:                                                      │
│   用户请求 → Redis 同步写入 (~1-3ms) → 返回响应                │
│                → MySQL 异步写入 (不阻塞)                       │
│                                                                  │
│   读取流程:                                                      │
│   请求 → Redis 命中 → 直接返回                                  │
│        Redis 未命中 → MySQL 查询 → 回填 Redis → 返回          │
│                                                                  │
│   性能优化:                                                      │
│   • Lua 脚本原子操作                                           │
│   • Pipeline 批量读取                                          │
│   • 异步 MySQL 持久化                                          │
│   • 连接池管理                                                 │
│                                                                  │
└─────────────────────────────────────────────────────────────┘
```

### 文件版本控制 ⭐

```
上传新文件 → MD5 校验
    ↓
  内容相同？ → 跳过处理
    ↓ 否
  创建新版本 (status=0, is_current=false)
    ↓
  向量化 + 索引 Milvus
    ↓
  完成后切换版本 → is_current=true
```

### 线程池优化架构 ⭐ NEW

```
┌─────────────────────────────────────────────────────────────┐
│                    统一线程池配置                              │
├─────────────────────────────────────────────────────────────┤
│                                                              │
│   根据 IO 密集型任务特性优化线程池参数：                        │
│   核心线程数 = CPU 核心数 × 2                                  │
│   最大线程数 = CPU 核心数 × 4                                  │
│                                                              │
│   专用线程池：                                                │
│   ┌─────────────────────────────────────────────────────┐    │
│   │ sseExecutor (SSE 流式响应)                           │    │
│   │ - core: 32, max: 64, queue: 64                       │    │
│   │ - 拒绝策略: CallerRunsPolicy                         │    │
│   │ - 用途: 处理长时间 SSE 连接                           │    │
│   └─────────────────────────────────────────────────────┘    │
│   ┌─────────────────────────────────────────────────────┐    │
│   │ vectorExecutor (文档向量化)                         │    │
│   │ - core: 8, max: 16, queue: 20                        │    │
│   │ - 拒绝策略: AbortPolicy                              │    │
│   │ - 用途: 控制并发 Milvus 索引                         │    │
│   └─────────────────────────────────────────────────────┘    │
│   ┌─────────────────────────────────────────────────────┐    │
│   │ persistExecutor (MySQL 持久化)                      │    │
│   │ - core: 32, max: 64, queue: 200                      │    │
│   │ - 拒绝策略: CallerRunsPolicy                         │    │
│   │ - 用途: 高频轻量任务，有界队列缓冲                   │    │
│   └─────────────────────────────────────────────────────┘    │
│   ┌─────────────────────────────────────────────────────┐    │
│   │ summaryExecutor (语义压缩)                          │    │
│   │ - core: 2, max: 5, queue: 50                         │    │
│   │ - 拒绝策略: CallerRunsPolicy                         │    │
│   │ - 用途: LLM 调用压缩，可降级处理                     │    │
│   └─────────────────────────────────────────────────────┘    │
│                                                              │
└─────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────┐
│                    线程池监控体系                              │
├─────────────────────────────────────────────────────────────┤
│                                                              │
│   监控功能：                                                 │
│   • 定时日志：每 30 秒输出线程池状态                         │
│   • HTTP 接口：GET /api/monitor/thread-pools                 │
│   • 自动告警：队列使用率 ≥ 70% 警告，≥ 90% 错误             │
│                                                              │
│   监控指标：                                                 │
│   • 活跃线程数 / 线程池大小                                  │
│   • 队列当前大小 / 队列容量                                  │
│   • 队列使用率（百分比）                                     │
│   • 已完成任务数 / 总任务数                                  │
│                                                              │
└─────────────────────────────────────────────────────────────┘
```

### 语义压缩架构 ⭐ NEW

```
┌─────────────────────────────────────────────────────────────┐
│                    语义压缩流程                                │
├─────────────────────────────────────────────────────────────┤
│                                                              │
│   每5轮对话 → 异步触发压缩                                   │
│       │                                                      │
│       ▼                                                      │
│   Redis 分布式锁（会话级别互斥）                              │
│       │                                                      │
│       ▼                                                      │
│   压缩智能体（独立配置，低温度）                              │
│       │                                                      │
│       ├─ 旧摘要 + 新增5轮 → 增量更新摘要                      │
│       │                                                      │
│       └─ 保存到 chat_summary 表（session_id 唯一）            │
│                                                              │
│   数据结构:                                                  │
│   ┌─────────────────────────────────────────────────────┐   │
│   │ chat_summary (与会话一对一)                          │   │
│   │ - session_id (UNIQUE)                               │   │
│   │ - content (摘要内容)                                 │   │
│   │ - version (版本号)                                   │   │
│   │ - compressed_count (已压缩消息数)                     │   │
│   └─────────────────────────────────────────────────────┘   │
│                                                              │
│   最终上下文 = 系统指令 + 历史摘要 + 最近15轮完整对话           │
│                                                              │
└─────────────────────────────────────────────────────────────┘
```

## 📈 更新日志

### v1.5.0 (2026-03-05)

**新增功能：**
- ✨ **Rerank 重排服务**: 使用阿里云百炼 gte-rerank 模型对向量检索结果进行语义重排

**技术优化：**
- 🔧 升级 DashScope SDK: 2.17.0 → 2.22.10（支持 Rerank API）
- 🔧 新增 RerankService: 使用 SDK 方式调用重排服务
- 🔧 VectorSearchService 集成重排: 召回阶段多取 3 倍结果 → Rerank重排 → 返回 topK

**检索流程：**
```
用户问题 → 向量化 → Milvus 召回(topK*3) → Rerank重排(topK) → 返回结果
```

**配置新增：**
```yaml
dashscope:
  rerank:
    model: "gte-rerank"
    top-n: 3
```

### v1.4.0 (2026-03-03)

### v1.4.0 (2026-03-03)

**新增功能：**
- ✨ **用户反馈功能**: AI回复消息下方新增反馈按钮，用户可标记不满意回复
- ✨ **刷新页面保存状态**: 当前聊天内容自动保存到 localStorage，刷新页面后自动恢复
- ✨ **会话软删除**: 删除会话采用软删除方式，刷新后不再出现
- ✨ **修改会话标题**: 聊天窗口顶部显示会话标题，支持点击修改

**技术优化：**
- 🔧 修复 Redis ZSet 序列化问题：ZSet 操作改用 StringRedisTemplate
- 🔧 修复会话标题自动更新：首条消息自动设置为会话标题
- 🔧 修复会话加载逻辑：切换会话时从后端获取最新标题
- 🔧 修复标题更新一致性问题：手动修改标题不会被自动覆盖

**前端优化：**
- 🔧 新增聊天标题栏 UI：显示/编辑会话标题
- 🔧 优化反馈按钮：点击后禁用，防止重复提交
- 🔧 优化消息渲染：普通聊天、流式聊天、AIOps 均支持反馈和状态保存

**数据一致性：**
- 🔧 修改标题采用同步写穿（Write-Through）：先 MySQL 后 Redis，失败回滚
- 🔧 新增 `deleted` 字段支持软删除

**数据库变更：**
- `chat_session` 表新增 `deleted` 字段（TINYINT，0-否，1-是）

**新增接口：**
```bash
# 修改会话标题
PUT /api/chat/session/{sessionId}/title
Content-Type: application/json
{"title": "新标题"}

# 软删除会话
DELETE /api/chat/session/{sessionId}

# 获取会话消息
GET /api/chat/session/{sessionId}/messages
```

### v1.3.1 (2026-03-01)

**新增功能：**
- ✨ **异步重试机制**: MySQL 持久化失败自动重试，提升数据可靠性

**技术优化：**
- 🔧 新增 `persistWithRetry()`：消息持久化重试，指数退避策略
- 🔧 新增 `clearWithRetry()`：清空历史重试
- 🔧 新增 `isRetryableException()`：智能判断可重试异常类型
- 🔧 区分可重试异常（连接、超时）与不可重试异常（约束冲突）

**重试策略：**
- 最大重试次数: 3 次
- 退避策略: 指数退避 (1s → 2s → 4s)
- 可重试异常: Connection, timeout, Network, Lock, SQLException

### v1.3.0 (2026-02-28)

**新增功能：**
- ✨ **线程池优化**: 统一线程池配置，根据任务类型（IO密集）优化参数
- ✨ **线程池监控**: 实时监控线程池状态，支持自动告警
- ✨ **监控接口**: 新增 `/api/monitor/thread-pools` 查询线程池指标

**技术优化：**
- 🔧 新增 `ThreadPoolConfig`：统一线程池配置类
- 🔧 新增 `ThreadPoolMonitorService`：线程池监控服务
- 🔧 新增 `MonitorController`：监控接口控制器
- 🔧 修复原有 `newCachedThreadPool` 无限线程风险
- 🔧 优化拒绝策略：CallerRunsPolicy（数据可靠性）+ AbortPolicy（系统保护）

**线程池配置：**
```yaml
thread:
  pool:
    sse: {core-size: 32, max-size: 64, queue-capacity: 64}
    vector: {core-size: 8, max-size: 16, queue-capacity: 20}
    persist: {core-size: 32, max-size: 64, queue-capacity: 200}
```

### v1.2.0 (2026-02-28)

**新增功能：**
- ✨ **语义压缩**: 智能对话摘要，自动压缩长对话历史，节省 Token
- ✨ **独立摘要表**: 新增 `chat_summary` 表，与会话一对一关联
- ✨ **异步压缩**: 后台异步处理，不阻塞用户响应
- ✨ **增量更新**: 摘要增量演进，保留关键信息

**技术优化：**
- 🔧 新增 `ConversationSummaryService`：专门的压缩服务
- 🔧 新增 `AsyncConfig`：异步任务配置，独立线程池
- 🔧 Redis 分布式锁：会话级别互斥，防止并发压缩
- 🔧 摘要版本追溯：支持版本号和压缩进度统计

**数据库变更：**
- 新增 `chat_summary` 表：存储对话摘要（session_id 唯一）
- `chat_message` 表新增 `compressed` 字段：标记是否已压缩

**配置项新增：**
```yaml
conversation:
  summary:
    enabled: true
    compress-threshold: 10
    compress-batch-size: 10
    lock-timeout-seconds: 30
```

### v1.1.0 (2026-02-27)

**新增功能：**
- ✨ **对话持久化**: Redis + MySQL 混合存储，支持对话历史持久化
- ✨ **会话管理**: 新增会话列表、会话信息查询接口
- ✨ **版本控制**: 文档上传支持版本管理，非阻塞更新
- ✨ **高并发优化**: 异步 MySQL 写入、Lua 脚本原子操作、Pipeline 批量读取

**技术优化：**
- 🔧 升级 MyBatis-Plus 至 3.5.9
- 🔧 添加 Redis 缓存层支持
- 🔧 添加 MySQL 数据存储支持
- 🔧 添加 Prometheus Mock 模式

**数据库变更：**
- 新增 `chat_session` 表：存储会话元数据
- 新增 `chat_message` 表：存储对话消息
- `doc_metadata` 表新增字段：`chunk_count`、`is_current`


### v1.0.0
- 初始版本发布

**版本**: v1.4.0
**作者**: 1karu32s
**原作者**: chief
**许可证**: MIT
