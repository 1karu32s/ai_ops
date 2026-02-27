# SuperBizAgent

> 基于 Spring Boot + AI Agent 的智能问答与运维系统

## 📖 项目简介

企业级智能业务代理系统，包含两大核心模块：

### 1. RAG 智能问答
集成 Milvus 向量数据库和阿里云 DashScope，提供基于检索增强生成的智能问答能力，支持多轮对话和流式输出。

### 2. AIOps 智能运维
基于 AI Agent 的自动化运维系统，采用 Planner-Executor-Replanner 架构，实现告警分析、日志查询、智能诊断和报告生成。

## 🚀 核心特性

- ✅ **RAG 问答**: 向量检索 + 多轮对话 + 流式输出
- ✅ **AIOps 运维**: 智能诊断 + 多 Agent 协作 + 自动报告
- ✅ **工具集成**: 文档检索、告警查询、日志分析、时间工具
- ✅ **会话持久化**: Redis + MySQL 混合存储，异步写入，高并发优化
- ✅ **版本控制**: 文档上传支持版本管理，非阻塞更新
- ✅ **Web 界面**: 提供测试界面和 RESTful API

## 🛠️ 技术栈

| 技术 | 版本 | 说明 |
|------|------|------|
| Java | 17 | 开发语言 |
| Spring Boot | 3.2.0 | 应用框架 |
| MyBatis-Plus | 3.5.9 | ORM 框架 |
| Spring AI | - | AI Agent 框架 |
| DashScope | 2.17.0 | 阿里云 AI 服务 |
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
│   │   └── FileUploadController.java  # 文件上传控制器 ⭐
│   ├── service/
│   │   ├── ChatService.java           # 对话服务 ⭐
│   │   ├── ConversationService.java  # 对话持久化服务 ⭐ NEW
│   │   ├── RedisCacheService.java     # Redis 缓存服务 ⭐ NEW
│   │   ├── AiOpsService.java          # AIOps 服务 ⭐
│   │   ├── RagService.java            # RAG 服务
│   │   └── Vector*.java               # 向量服务
│   ├── entity/                         # 实体类
│   │   ├── ChatSession.java           # 会话实体 ⭐ NEW
│   │   ├── ChatMessage.java           # 消息实体 ⭐ NEW
│   │   └── DocMetadata.java           # 文档元数据 ⭐
│   ├── mapper/                         # MyBatis Mapper
│   │   ├── ChatSessionMapper.java     # 会话 Mapper ⭐ NEW
│   │   ├── ChatMessageMapper.java     # 消息 Mapper ⭐ NEW
│   │   └── DocMetadataMapper.java     # 文档 Mapper ⭐
│   ├── agent/tool/                    # Agent 工具集
│   │   ├── DateTimeTools.java         # 时间工具
│   │   ├── InternalDocsTools.java     # 文档检索
│   │   ├── QueryMetricsTools.java     # 告警查询
│   │   └── QueryLogsTools.java        # 日志查询
│   ├── config/                        # 配置类
│   │   ├── RedisConfig.java           # Redis 配置 ⭐ NEW
│   │   └── DocumentChunkConfig.java   # 文档分片配置
│   └── util/
│       └── FileUpdateLockManager.java # 文件更新锁管理 ⭐ NEW
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

### 文件版本控制 ⭐ NEW

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

## 📈 更新日志

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
**版本**: v1.1.1
**作者**: 1karu32s
**许可证**: MIT

### v1.0.0
- 初始版本发布

**版本**: v1.1.0
**作者**: chief
**许可证**: MIT
